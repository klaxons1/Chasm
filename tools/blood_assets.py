#!/usr/bin/env python3
"""Extract and convert Blood (Build engine) resources for the Chasm J2ME port.

The original game data is user-supplied in Blood.7z and must not be checked in
as unpacked assets. This tool writes generated files to a caller-selected output
folder (keep generated files outside Git, e.g. under /tmp). It understands the original ART tile format
and Blood's RFF resource directory; no image or archive library is required
apart from py7zr for reading the outer 7z file.

Examples:
    python3 tools/blood_assets.py inspect Blood.7z
    python3 tools/blood_assets.py inspect-map Blood.7z E1M1.MAP
    python3 tools/blood_assets.py export-art Blood.7z /tmp/blood-e1m1 --map E1M1.MAP
    python3 tools/blood_assets.py extract-rff Blood.7z /tmp/blood-rff --pattern '*.MAP'
"""

import argparse
import fnmatch
import json
import struct
import sys
import tempfile
from pathlib import Path


class BloodAssetError(ValueError):
    """Raised for malformed or unsupported Blood resource data."""


def _u16(data, offset):
    return struct.unpack_from('<H', data, offset)[0]


def _u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def _rff_xor(data, key_offset):
    out = bytearray(data)
    for i in range(len(out)):
        out[i] ^= ((key_offset + i) >> 1) & 0xff
    return bytes(out)


def read_rff(data):
    """Return (revision, resources), where each resource has name and bytes.

    RFF v3 directories are XOR-obfuscated. Individual resources carrying flag
    0x10 have their first 256 bytes obfuscated as well, as in BuildGDX's RFF
    reader. The parser checks all offsets and lengths before slicing.
    """
    if len(data) < 16 or data[:4] != b'RFF\x1a':
        raise BloodAssetError('not a Blood RFF archive')
    revision, fat_offset, count = struct.unpack_from('<III', data, 4)
    if revision not in (0x300, 0x301):
        raise BloodAssetError('unsupported RFF revision 0x%08x' % revision)
    fat_size = count * 48
    if count > 100000 or fat_offset > len(data) or fat_size > len(data) - fat_offset:
        raise BloodAssetError('RFF directory is outside the archive')
    fat = data[fat_offset:fat_offset + fat_size]
    if revision == 0x300:
        fat = _rff_xor(fat, fat_offset)
    else:
        fat = _rff_xor(fat, fat_offset + fat_offset * (revision & 0xff))

    resources = []
    for i in range(count):
        rec = fat[i * 48:(i + 1) * 48]
        offset, size = struct.unpack_from('<II', rec, 16)
        flags = rec[32]
        ext = rec[33:36].decode('ascii', 'replace').rstrip('\x00 ')
        stem = rec[36:44].decode('ascii', 'replace').rstrip('\x00 ')
        name = (stem + ('.' + ext if ext else '')).upper()
        if offset > len(data) or size > len(data) - offset:
            raise BloodAssetError('RFF resource %s is outside the archive' % name)
        payload = data[offset:offset + size]
        if flags & 0x10:
            payload = _rff_xor(payload[:min(256, size)], 0) + payload[min(256, size):]
        resources.append({'name': name, 'offset': offset, 'size': size,
                          'flags': flags, 'file_id': _u32(rec, 44), 'data': payload})
    return revision, resources


def read_art(data):
    """Decode an ART v1 file into (declared_tile_count, tile_records).

    ART stores dimensions and animation metadata in parallel arrays, followed
    by pixel planes in Build's column-major order.
    """
    if len(data) < 16:
        raise BloodAssetError('ART file is shorter than its header')
    version, num_tiles, first, last = struct.unpack_from('<4I', data, 0)
    if version != 1:
        raise BloodAssetError('unsupported ART version %d' % version)
    if last < first or last - first + 1 > 65536:
        raise BloodAssetError('invalid ART tile range %d..%d' % (first, last))
    count = last - first + 1
    metadata_end = 16 + count * 8
    if metadata_end > len(data):
        raise BloodAssetError('truncated ART metadata')
    widths = [_u16(data, 16 + i * 2) for i in range(count)]
    heights_at = 16 + count * 2
    heights = [_u16(data, heights_at + i * 2) for i in range(count)]
    anim_at = 16 + count * 4
    anims = [_u32(data, anim_at + i * 4) for i in range(count)]
    cursor = metadata_end
    tiles = []
    for i, (width, height) in enumerate(zip(widths, heights)):
        size = width * height
        if size > len(data) - cursor:
            raise BloodAssetError('truncated ART pixels for tile %d' % (first + i))
        pixels = data[cursor:cursor + size]
        cursor += size
        tiles.append({'id': first + i, 'width': width, 'height': height,
                      'anim': anims[i], 'pixels': pixels})
    return num_tiles, tiles


def _decrypt_map_block(data, key):
    return bytes(value ^ ((key + index) & 0xff) for index, value in enumerate(data))


def _need(data, offset, size, label):
    if offset < 0 or size < 0 or offset > len(data) or size > len(data) - offset:
        raise BloodAssetError('truncated Blood MAP while reading ' + label)


def decode_blood_map(data):
    """Decode the geometry records in a Blood v6/v7 MAP file.

    Blood v7 wraps standard Build sector/wall/sprite records in a small header
    and applies record-specific XOR keys. Extended XSPRITE/XWALL/XSECTOR data
    is skipped using record sizes from the map header; core geometry remains
    available for conversion to the Chasm C3B world representation.
    """
    if len(data) < 6 or data[:4] != b'BLM\x1a':
        raise BloodAssetError('not a Blood BLM map')
    version = _u16(data, 4)
    major = version & 0xff00
    if major not in (0x0600, 0x0700):
        raise BloodAssetError('unsupported Blood MAP version 0x%04x' % version)
    crypted = major == 0x0700
    cursor = 6
    _need(data, cursor, 37, 'map header')
    header = data[cursor:cursor + 37]
    cursor += 37
    if crypted:
        header = _decrypt_map_block(header, int.from_bytes(b'Matt', 'little'))

    start = {'x': struct.unpack_from('<i', header, 0)[0],
             'y': struct.unpack_from('<i', header, 4)[0],
             'z': struct.unpack_from('<i', header, 8)[0],
             'angle': struct.unpack_from('<h', header, 12)[0],
             'sector': struct.unpack_from('<h', header, 14)[0]}
    sky_bits = struct.unpack_from('<h', header, 16)[0]
    map_revision = struct.unpack_from('<I', header, 27)[0]
    sector_count, wall_count, sprite_count = struct.unpack_from('<HHH', header, 31)
    if not sector_count or not wall_count or sector_count > 4096 or wall_count > 32767 or sprite_count > 32767:
        raise BloodAssetError('invalid Blood MAP geometry counts')
    x_sprite_size, x_wall_size, x_sector_size = 56, 24, 60

    if crypted:
        _need(data, cursor, 128, 'encrypted map size table')
        sizes = _decrypt_map_block(data[cursor:cursor + 128], wall_count)
        cursor += 128
        x_sprite_size, x_wall_size, x_sector_size = struct.unpack_from('<III', sizes, 64)
        if not (0 < x_sprite_size <= 4096 and 0 < x_wall_size <= 4096
                and 0 < x_sector_size <= 4096):
            raise BloodAssetError('invalid Blood MAP extended-record sizes')

    if sky_bits < 0 or sky_bits > 8:
        raise BloodAssetError('invalid Blood MAP sky tile count')
    sky_bytes = (1 << sky_bits) * 2
    _need(data, cursor, sky_bytes, 'sky offsets')
    sky = data[cursor:cursor + sky_bytes]
    cursor += sky_bytes
    if crypted:
        _decrypt_map_block(sky, sky_bytes)  # validate the encoded section size

    sectors = []
    sector_key = map_revision * 40
    for i in range(sector_count):
        _need(data, cursor, 40, 'sector %d' % i)
        raw = data[cursor:cursor + 40]
        cursor += 40
        if crypted:
            raw = _decrypt_map_block(raw, sector_key)
        sectors.append({
            'wall_start': _u16(raw, 0), 'wall_count': _u16(raw, 2),
            'ceiling_z': struct.unpack_from('<i', raw, 4)[0],
            'floor_z': struct.unpack_from('<i', raw, 8)[0],
            'ceiling_flags': _u16(raw, 12), 'floor_flags': _u16(raw, 14),
            'ceiling_tile': _u16(raw, 16), 'floor_tile': _u16(raw, 24),
            'ceiling_shade': struct.unpack_from('<b', raw, 20)[0],
            'floor_shade': struct.unpack_from('<b', raw, 28)[0],
            'lotag': struct.unpack_from('<h', raw, 34)[0], 'hitag': struct.unpack_from('<h', raw, 36)[0],
            'extra': struct.unpack_from('<h', raw, 38)[0]})
        if sectors[-1]['extra'] > 0:
            _need(data, cursor, x_sector_size, 'extended sector %d' % i)
            cursor += x_sector_size

    walls = []
    wall_key = (map_revision * 40) | int.from_bytes(b'Matt', 'little')
    for i in range(wall_count):
        _need(data, cursor, 32, 'wall %d' % i)
        raw = data[cursor:cursor + 32]
        cursor += 32
        if crypted:
            raw = _decrypt_map_block(raw, wall_key)
        walls.append({'x': struct.unpack_from('<i', raw, 0)[0],
                      'y': struct.unpack_from('<i', raw, 4)[0],
                      'point2': _u16(raw, 8), 'next_wall': struct.unpack_from('<h', raw, 10)[0],
                      'next_sector': struct.unpack_from('<h', raw, 12)[0],
                      'flags': _u16(raw, 14), 'tile': _u16(raw, 16),
                      'overlay_tile': _u16(raw, 18), 'shade': struct.unpack_from('<b', raw, 20)[0],
                      'x_repeat': raw[22], 'y_repeat': raw[23],
                      'x_pan': raw[24], 'y_pan': raw[25], 'extra': struct.unpack_from('<h', raw, 30)[0]})
        if walls[-1]['extra'] > 0:
            _need(data, cursor, x_wall_size, 'extended wall %d' % i)
            cursor += x_wall_size

    sprites = []
    sprite_key = (map_revision * 44) | int.from_bytes(b'Matt', 'little')
    for i in range(sprite_count):
        _need(data, cursor, 44, 'sprite %d' % i)
        raw = data[cursor:cursor + 44]
        cursor += 44
        if crypted:
            raw = _decrypt_map_block(raw, sprite_key)
        sprites.append({'x': struct.unpack_from('<i', raw, 0)[0],
                        'y': struct.unpack_from('<i', raw, 4)[0],
                        'z': struct.unpack_from('<i', raw, 8)[0],
                        'flags': _u16(raw, 12), 'tile': _u16(raw, 14),
                        'sector': struct.unpack_from('<h', raw, 24)[0],
                        'status': struct.unpack_from('<h', raw, 26)[0],
                        'angle': struct.unpack_from('<h', raw, 28)[0],
                        'extra': struct.unpack_from('<h', raw, 42)[0]})
        if sprites[-1]['extra'] > 0:
            _need(data, cursor, x_sprite_size, 'extended sprite %d' % i)
            cursor += x_sprite_size

    tiles = set()
    for sector in sectors:
        tiles.add(sector['ceiling_tile'])
        tiles.add(sector['floor_tile'])
    for wall in walls:
        tiles.add(wall['tile'])
        if wall['overlay_tile']:
            tiles.add(wall['overlay_tile'])
    for sprite in sprites:
        tiles.add(sprite['tile'])
    return {'version': version, 'map_revision': map_revision, 'start': start,
            'sectors': sectors, 'walls': walls, 'sprites': sprites,
            'tile_ids': sorted(tiles), 'bytes_consumed': cursor}


def parse_tile_ranges(spec):
    if not spec:
        return None
    result = set()
    for part in spec.split(','):
        part = part.strip()
        if not part:
            continue
        bounds = part.split('-', 1)
        try:
            start = int(bounds[0], 0)
            end = int(bounds[1], 0) if len(bounds) == 2 else start
        except ValueError as exc:
            raise BloodAssetError('bad tile range: ' + part) from exc
        if start < 0 or end < start or end > 65535:
            raise BloodAssetError('tile range outside 0..65535: ' + part)
        result.update(range(start, end + 1))
    return result


def _palette_from_rff(archive_data):
    _revision, resources = read_rff(archive_data)
    entry = next((item for item in resources if item['name'] == 'BLOOD.PAL'), None)
    if entry is None:
        entry = next((item for item in resources if item['name'] == 'PALETTE.DAT'), None)
    if entry is None:
        raise BloodAssetError('BLOOD.PAL not found in blood.rff')
    if len(entry['data']) < 768:
        raise BloodAssetError('Blood palette resource is truncated')
    colors = []
    for i in range(256):
        r, g, b = entry['data'][3 * i:3 * i + 3]
        # Build palettes usually store 6-bit channels; tolerate 8-bit sources.
        if max(r, g, b) <= 63:
            r, g, b = r * 255 // 63, g * 255 // 63, b * 255 // 63
        colors.append((r, g, b))
    return colors


def write_bmp8(path, width, height, pixels, palette):
    """Write indexed, uncompressed 8-bit BMP while preserving palette indices."""
    if width <= 0 or height <= 0:
        raise BloodAssetError('cannot write a zero-sized tile')
    if len(pixels) != width * height or len(palette) != 256:
        raise BloodAssetError('invalid indexed image dimensions or palette')
    stride = (width + 3) & ~3
    raster = bytearray()
    for y in range(height - 1, -1, -1):
        row = bytearray(pixels[y * width:(y + 1) * width])
        row.extend(b'\0' * (stride - width))
        raster.extend(row)
    palette_bytes = bytearray()
    for r, g, b in palette:
        palette_bytes.extend((b, g, r, 0))
    offset = 14 + 40 + len(palette_bytes)
    file_size = offset + len(raster)
    header = struct.pack('<2sIHHI', b'BM', file_size, 0, 0, offset)
    info = struct.pack('<IiiHHIIiiII', 40, width, height, 1, 8, 0,
                       len(raster), 2835, 2835, 256, 256)
    Path(path).write_bytes(header + info + palette_bytes + raster)


def _load_archive_entries(archive_path, requested):
    try:
        import py7zr
    except ImportError as exc:
        raise BloodAssetError('py7zr is required; install with: python3 -m pip install py7zr') from exc
    with py7zr.SevenZipFile(str(archive_path), mode='r') as archive:
        names = [name for name in archive.getnames() if not name.endswith('/')]
        selected = []
        for name in names:
            basename = name.rsplit('/', 1)[-1].upper()
            if any(fnmatch.fnmatch(basename, pattern.upper()) for pattern in requested):
                selected.append(name)
        if not selected:
            raise BloodAssetError('no matching files in ' + str(archive_path))
        with tempfile.TemporaryDirectory(prefix='blood-input-') as temp:
            archive.extract(path=temp, targets=selected)
            root = Path(temp)
            return {name.rsplit('/', 1)[-1].upper(): (root / name).read_bytes()
                    for name in selected}


def command_inspect(args):
    entries = _load_archive_entries(args.archive, [args.container, '*.art']
                                    if args.container.upper() == 'BLOOD.RFF'
                                    else [args.container])
    revision, resources = read_rff(entries[args.container.upper()])
    art_count = sum(1 for name in entries if name.endswith('.ART'))
    print('Blood archive: %s, %s ART files, RFF revision 0x%03x, %s resources' %
          (args.container, art_count, revision, len(resources)))
    for item in resources:
        print('%-16s %8d bytes flags=0x%02x' % (item['name'], item['size'], item['flags']))


def _find_rff_resource(resources, filename):
    wanted = filename.rsplit('/', 1)[-1].upper()
    return next((item for item in resources if item['name'] == wanted), None)


def command_inspect_map(args):
    entries = _load_archive_entries(args.archive, ['blood.rff'])
    _revision, resources = read_rff(entries['BLOOD.RFF'])
    item = _find_rff_resource(resources, args.map)
    if item is None:
        raise BloodAssetError('map not found in BLOOD.RFF: ' + args.map)
    map_data = decode_blood_map(item['data'])
    result = {'name': item['name'], 'version': map_data['version'],
              'map_revision': map_data['map_revision'], 'start': map_data['start'],
              'sectors': len(map_data['sectors']), 'walls': len(map_data['walls']),
              'sprites': len(map_data['sprites']), 'tile_ids': map_data['tile_ids']}
    print(json.dumps(result, indent=2))


def command_extract_rff(args):
    entries = _load_archive_entries(args.archive, [args.container])
    _revision, resources = read_rff(entries[args.container.upper()])
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    patterns = [p.strip().upper() for p in args.pattern]
    written = 0
    for item in resources:
        if patterns and not any(fnmatch.fnmatch(item['name'], p) for p in patterns):
            continue
        (output / item['name']).write_bytes(item['data'])
        written += 1
    print('Extracted %d RFF resources to %s' % (written, output))


def command_export_art(args):
    archive_entries = _load_archive_entries(args.archive, ['blood.rff', 'tiles*.art'])
    palette = _palette_from_rff(archive_entries['BLOOD.RFF'])
    wanted = parse_tile_ranges(args.tiles)
    if args.map:
        _revision, resources = read_rff(archive_entries['BLOOD.RFF'])
        item = _find_rff_resource(resources, args.map)
        if item is None:
            raise BloodAssetError('map not found in BLOOD.RFF: ' + args.map)
        map_tiles = set(decode_blood_map(item['data'])['tile_ids'])
        wanted = map_tiles if wanted is None else wanted.intersection(map_tiles)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    exported = 0
    index = []
    for name, art_data in sorted(archive_entries.items()):
        if not name.endswith('.ART'):
            continue
        _num_tiles, tiles = read_art(art_data)
        for tile in tiles:
            if wanted is not None and tile['id'] not in wanted:
                continue
            if tile['width'] == 0 or tile['height'] == 0:
                index.append({'tile': tile['id'], 'source': name,
                              'width': 0, 'height': 0,
                              'animation': tile['anim'], 'file': None})
                continue
            # Convert column-major ART pixels to the row-major BMP convention.
            width, height, source = tile['width'], tile['height'], tile['pixels']
            rows = bytearray(width * height)
            for x in range(width):
                for y in range(height):
                    rows[y * width + x] = source[x * height + y]
            filename = 'tile_%05d.bmp' % tile['id']
            write_bmp8(output / filename, width, height, rows, palette)
            index.append({'tile': tile['id'], 'source': name,
                          'width': width, 'height': height,
                          'animation': tile['anim'], 'file': filename})
            exported += 1
    (output / 'tiles.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8')
    print('Exported %d indexed tiles and tiles.json to %s' % (exported, output))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)
    inspect = subparsers.add_parser('inspect', help='list RFF resource entries')
    inspect.add_argument('archive', type=Path)
    inspect.add_argument('--container', choices=('blood.rff', 'sounds.rff', 'gui.rff'),
                         default='blood.rff', help='RFF container inside the 7z')
    inspect.set_defaults(func=command_inspect)
    map_info = subparsers.add_parser('inspect-map', help='decode a Blood MAP and list its referenced tiles')
    map_info.add_argument('archive', type=Path)
    map_info.add_argument('map', help='map resource name, e.g. E1M1.MAP')
    map_info.set_defaults(func=command_inspect_map)
    extract = subparsers.add_parser('extract-rff', help='extract selected RFF entries')
    extract.add_argument('archive', type=Path)
    extract.add_argument('output', type=Path)
    extract.add_argument('--container', choices=('blood.rff', 'sounds.rff', 'gui.rff'),
                         default='blood.rff', help='RFF container inside the 7z')
    extract.add_argument('--pattern', action='append', default=[], 
                         help='case-insensitive wildcard, repeatable (e.g. *.MAP)')
    extract.set_defaults(func=command_extract_rff)
    export = subparsers.add_parser('export-art', help='convert ART tiles to indexed BMP8')
    export.add_argument('archive', type=Path)
    export.add_argument('output', type=Path)
    export.add_argument('--tiles', help='comma/range filter, e.g. 0-255,512')
    export.add_argument('--map', help='export only tiles referenced by this MAP resource')
    export.set_defaults(func=command_export_art)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except (BloodAssetError, OSError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == '__main__':
    sys.exit(main())
