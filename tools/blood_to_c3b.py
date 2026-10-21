#!/usr/bin/env python3
"""Compile one Blood MAP plus its indexed ART materials into the J2ME C3B format.

This is the first-pass geometry/material bridge for the Blood J2ME port. It
keeps generated, proprietary game-data derivatives in a caller-selected build
folder rather than adding extracted Blood assets to Git. Interactive map logic,
sloped floors, animated sectors, masked walls and Blood sprite actors are not
converted yet.

Usage:
    python3 tools/blood_to_c3b.py Blood.7z E1M1.MAP /tmp/blood-e1m1
    python3 tools/blood_to_c3b.py Blood.7z all /tmp/blood-allmaps
"""
import argparse
import json
import struct
import sys
from pathlib import Path

try:
    from .blood_assets import (BloodAssetError, _palette_from_rff, _load_archive_entries,
                               decode_blood_map, read_art, read_rff, write_bmp8)
except ImportError:
    from blood_assets import (BloodAssetError, _palette_from_rff, _load_archive_entries,
                              decode_blood_map, read_art, read_rff, write_bmp8)


HORIZONTAL_DIVISOR = 64
VERTICAL_DIVISOR = 1024


def _tile_pixels(tile, palette, width, height):
    src_w, src_h = tile['width'], tile['height']
    if not src_w or not src_h:
        return bytearray(width * height)
    source = tile['pixels']
    out = bytearray(width * height)
    for y in range(height):
        sy = min(src_h - 1, y * src_h // height)
        for x in range(width):
            sx = min(src_w - 1, x * src_w // width)
            out[y * width + x] = source[sx * src_h + sy]
    return out


def _wall_dimensions(tile):
    # Preserve ART texel dimensions. The runtime wraps non-power-of-two widths
    # and samples nonstandard heights in a generic path, so resampling here
    # would distort the authored material and its Build repeat scale.
    return tile['width'], tile['height']


def _wall_vertical_pan_pixels(pan, dimension):
    # Build stores vertical panning as a fraction of the ART tile height.
    return (int(pan) * int(dimension)) >> 8


def _blood_light_level(shade):
    # Blood shade 0 is the unmodified BLOOD.PAL lookup. Center it on Chasm's
    # neutral exposure (level 8); positive shade darkens, negative shade brightens.
    return max(0, min(15, 8 - int(shade) // 8))


def _art_tile_index(art_data):
    result = {}
    for name, data in sorted(art_data.items()):
        if not name.endswith('.ART'):
            continue
        _count, tiles = read_art(data)
        for tile in tiles:
            result[tile['id']] = tile
    return result


def _u16(value):
    return struct.pack('<H', value & 0xffff)


def _i16(value):
    if value < -32768 or value > 32767:
        raise BloodAssetError('coordinate/index exceeds C3B signed-short range: %d' % value)
    return struct.pack('<h', value)


def stack_sector_links(map_data):
    """Return Blood upper/lower stack sector pairs from linked marker sprites."""
    sector_count = len(map_data['sectors'])
    upper = [-1] * sector_count
    lower = [-1] * sector_count
    uppers = {}
    lowers = {}
    for sprite in map_data.get('sprites', []):
        marker_type = sprite.get('lotag')
        if marker_type not in (11, 12) or sprite.get('extra', 0) <= 0:
            continue
        sector_id = sprite.get('sector', -1)
        if sector_id < 0 or sector_id >= sector_count:
            continue
        marker_id = sprite.get('data1', 0)
        markers = uppers if marker_type == 11 else lowers
        markers.setdefault(marker_id, []).append(sector_id)

    for marker_id, upper_sectors in uppers.items():
        lower_sectors = lowers.get(marker_id, [])
        for upper_sector, lower_sector in zip(upper_sectors, lower_sectors):
            upper[upper_sector] = lower_sector
            lower[lower_sector] = upper_sector
    return upper, lower


def compile_map(map_data, tile_index, palette, output_dir, map_name):
    output_dir = Path(output_dir)
    level_dir = output_dir / 'blood' / 'levels'
    resource_map_name = Path(map_name).stem.lower()
    material_dir = output_dir / 'blood' / 'textures' / resource_map_name
    level_dir.mkdir(parents=True, exist_ok=True)
    material_dir.mkdir(parents=True, exist_ok=True)

    wall_tiles = sorted(set(wall['tile'] for wall in map_data['walls']))
    flat_tiles = sorted(set(tile for sector in map_data['sectors']
                            for tile in (sector['floor_tile'], sector['ceiling_tile'])))
    # Slot zero is reserved for absent/missing resources; actual manifest slots
    # are positive signed bytes (1..127) in this runtime.
    wall_tiles = [tile for tile in wall_tiles if tile != 0]
    flat_tiles = [tile for tile in flat_tiles if tile != 0]
    if len(wall_tiles) > 127 or len(flat_tiles) > 127:
        raise BloodAssetError('%s needs %d wall and %d flat materials; current J2ME '
                              'material slots support at most 127 of each' %
                              (map_name, len(wall_tiles), len(flat_tiles)))
    wall_slots = {tile: i + 1 for i, tile in enumerate(wall_tiles)}
    flat_slots = {tile: i + 1 for i, tile in enumerate(flat_tiles)}

    material_lines = ['# Generated from %s; do not commit proprietary tile images.' % map_name]
    for tile_id, slot in wall_slots.items():
        tile = tile_index.get(tile_id)
        if tile is None:
            raise BloodAssetError('ART tile %d referenced by %s is missing' % (tile_id, map_name))
        width, height = _wall_dimensions(tile)
        pixels = _tile_pixels(tile, palette, width, height)
        filename = 'w%03d.bmp' % slot
        write_bmp8(material_dir / filename, width, height, pixels, palette)
        material_lines.append('wall.%d=%s' % (slot, filename))
    for tile_id, slot in flat_slots.items():
        tile = tile_index.get(tile_id)
        if tile is None:
            raise BloodAssetError('ART tile %d referenced by %s is missing' % (tile_id, map_name))
        pixels = _tile_pixels(tile, palette, 64, 64)
        filename = 'f%03d.bmp' % slot
        write_bmp8(material_dir / filename, 64, 64, pixels, palette)
        material_lines.append('flat.%d=%s' % (slot, filename))
    manifest = material_dir / 'materials.c3m'
    manifest.write_text('\n'.join(material_lines) + '\n', encoding='utf-8')

    sector_count = len(map_data['sectors'])
    wall_count = len(map_data['walls'])
    vertices = []
    vertex_map = {}

    def get_vertex(wall_index):
        wall = map_data['walls'][wall_index]
        point = (int(round(wall['x'] / float(HORIZONTAL_DIVISOR))),
                 int(round(wall['y'] / float(HORIZONTAL_DIVISOR))))
        if point not in vertex_map:
            vertex_map[point] = len(vertices)
            vertices.append(point)
        return vertex_map[point]

    sector_for_wall = [-1] * wall_count
    segments_by_sector = [[] for _ in range(sector_count)]
    for sector_id, sector in enumerate(map_data['sectors']):
        first = sector['wall_start']
        count = sector['wall_count']
        if first + count > wall_count:
            raise BloodAssetError('%s sector %d references walls out of range' % (map_name, sector_id))
        for wall_id in range(first, first + count):
            sector_for_wall[wall_id] = sector_id
            segments_by_sector[sector_id].append(wall_id)
    if any(sector_id < 0 for sector_id in sector_for_wall):
        raise BloodAssetError('%s contains walls not owned by a sector' % map_name)
    # Build/Blood stores loops with the opposite winding from Chasm's C3B
    # sector leaves. Reverse each loop before emission; the renderer treats
    # front-facing segment endpoint order as significant for projection.
    for wall_ids in segments_by_sector:
        wall_ids.reverse()
    if len(vertices) > 32767:
        raise BloodAssetError('%s exceeds C3B vertex limit' % map_name)

    surfaces = []
    definitions = []
    for wall_id, wall in enumerate(map_data['walls']):
        sector_id = sector_for_wall[wall_id]
        wall_slot = wall_slots.get(wall['tile'], 0)
        front_surface = len(surfaces)
        wall_tile = tile_index.get(wall['tile'])
        wall_height = wall_tile['height'] if wall_tile is not None else 64
        surfaces.append((wall_slot, sector_id,
                         wall.get('x_pan', 0) & 0xff,
                         _wall_vertical_pan_pixels(wall.get('y_pan', 0), wall_height),
                         wall.get('x_repeat', 8) or 8, wall.get('y_repeat', 8) or 8,
                         _blood_light_level(wall.get('shade', 0))))
        neighbor_id = wall['next_sector']
        back_surface = -1
        if 0 <= neighbor_id < sector_count:
            back_surface = len(surfaces)
            next_wall = wall['next_wall']
            back_wall = map_data['walls'][next_wall] if 0 <= next_wall < wall_count else wall
            back_tile = back_wall['tile']
            back_slot = wall_slots.get(back_tile, wall_slot)
            back_art_tile = tile_index.get(back_tile)
            back_height = back_art_tile['height'] if back_art_tile is not None else wall_height
            surfaces.append((back_slot, neighbor_id,
                             back_wall.get('x_pan', 0) & 0xff,
                             _wall_vertical_pan_pixels(back_wall.get('y_pan', 0), back_height),
                             back_wall.get('x_repeat', 8) or 8,
                             back_wall.get('y_repeat', 8) or 8,
                             _blood_light_level(back_wall.get('shade', 0))))
        definitions.append((get_vertex(wall_id), get_vertex(wall['point2']),
                            front_surface, back_surface,
                            4 if back_surface >= 0 else 1))
        segments_by_sector[sector_id]

    if len(surfaces) > 32767:
        raise BloodAssetError('%s exceeds C3B surface limit' % map_name)

    # One BSP leaf per Build sector. Build a balanced coordinate hierarchy for
    # back-to-front ordering; portal visibility is separately represented by
    # the all-visible PVS while gameplay discovers the containing polygon.
    centers = []
    for sector_id, wall_ids in enumerate(segments_by_sector):
        if not wall_ids:
            raise BloodAssetError('%s sector %d is empty' % (map_name, sector_id))
        pts = [map_data['walls'][i] for i in wall_ids]
        centers.append((sum(w['x'] for w in pts) // len(pts),
                         sum(w['y'] for w in pts) // len(pts)))
    nodes = []

    def build_tree(ids):
        if len(ids) == 1:
            return -ids[0] - 1
        xs = [centers[i][0] for i in ids]
        ys = [centers[i][1] for i in ids]
        axis = 0 if max(xs) - min(xs) >= max(ys) - min(ys) else 1
        ordered = sorted(ids, key=lambda i: centers[i][axis])
        middle = len(ordered) // 2
        left_ids, right_ids = ordered[:middle], ordered[middle:]
        left = build_tree(left_ids)
        right = build_tree(right_ids)
        left_value = centers[ordered[middle - 1]][axis]
        right_value = centers[ordered[middle]][axis]
        split = int(round((left_value + right_value) / (2.0 * HORIZONTAL_DIVISOR)))
        if axis == 0:
            start_x, start_z, delta_x, delta_z = split, 0, 0, 1
        else:
            start_x, start_z, delta_x, delta_z = 0, split, 1, 0
        node_index = len(nodes)
        nodes.append((start_x, start_z, delta_x, delta_z, left, right))
        return node_index

    root = build_tree(list(range(sector_count)))
    if len(nodes) > 32767:
        raise BloodAssetError('%s exceeds C3B node limit' % map_name)

    # Copy map geometry and reduce Blood's vertical scale (Build uses a 16:1
    # vertical-to-horizontal ratio). Chasm's collision/render loop expects an
    # upward-positive floor-to-ceiling interval.
    sectors = []
    for sector in map_data['sectors']:
        floor_h = int(round(-sector['floor_z'] / float(VERTICAL_DIVISOR)))
        ceiling_h = int(round(-sector['ceiling_z'] / float(VERTICAL_DIVISOR)))
        if ceiling_h <= floor_h:
            ceiling_h = floor_h + 1
        floor_slot = flat_slots.get(sector['floor_tile'], 0)
        ceiling_slot = flat_slots.get(sector['ceiling_tile'], 0)
        flags = (1 if sector['ceiling_flags'] & 1 else 0) | (2 if sector['floor_flags'] & 1 else 0)
        light = _blood_light_level(sector['floor_shade'])
        sectors.append((floor_h, ceiling_h, floor_slot, ceiling_slot, light, flags,
                        sector['lotag'], 0))

    upper_stack, lower_stack = stack_sector_links(map_data)
    has_stack_links = any(sector >= 0 for sector in upper_stack)

    # All pairs are visible in this first pass so distant portal-connected
    # sectors are never lost to an incomplete hand-built visibility solution.
    pair_count = sector_count * sector_count
    pvs = bytes([0xff]) * ((pair_count + 7) // 8)

    output_level = level_dir / (Path(map_name).stem.lower() + '.c3b')
    output_level.parent.mkdir(parents=True, exist_ok=True)
    with output_level.open('wb') as out:
        out.write(b'C3B1')
        # Header bit 1 marks Blood-specific packed pan/repeat fields and
        # distance-ordered sector rendering; bit 2 adds per-sector upper/lower
        # room-over-room links after the sector records.
        out.write(bytes((1, 2 | (4 if has_stack_links else 0))))
        out.write(_i16(root))
        for count in (len(vertices), len(definitions), 1, len(surfaces), sector_count,
                      len(nodes), sector_count, wall_count):
            out.write(_u16(count))
        material_path = ('/blood/textures/' + resource_map_name + '/materials.c3m').encode('utf-8')
        out.write(_u16(len(material_path)))
        out.write(material_path)
        for x, z in vertices:
            out.write(_i16(x))
            out.write(_i16(z))
        for start, end, front, back, flags in definitions:
            out.write(_i16(start))
            out.write(_i16(end))
            out.write(_i16(front))
            out.write(_i16(back))
            out.write(bytes((flags & 0xff, 0, 0, 0)))
        # C3B1 stores inline world-origin objects after vertices and wall
        # definitions, exactly where CustomLevelLoader consumes them.
        spawn = map_data['start']
        out.write(_i16(int(round(spawn['x'] / float(HORIZONTAL_DIVISOR)))))
        out.write(_i16(int(round(spawn['y'] / float(HORIZONTAL_DIVISOR)))))
        out.write(_i16((spawn['angle'] * 2) & 4095))
        out.write(_i16(1))  # world-origin object type
        out.write(_i16(spawn.get('sector', 0)))  # Blood start-sector hint
        for surface_slot, sector_id, xpan, ypan, xrepeat, yrepeat, wall_light in surfaces:
            out.write(_u16(((xrepeat & 0xff) << 8) | (xpan & 0xff)))
            out.write(_u16(((yrepeat & 0xff) << 8) | (ypan & 0xff)))
            out.write(bytes((surface_slot, surface_slot, surface_slot, wall_light)))
            out.write(_u16(sector_id))
        for floor_h, ceiling_h, floor_slot, ceiling_slot, light, flags, tag, kind in sectors:
            out.write(_i16(floor_h))
            out.write(_i16(ceiling_h))
            out.write(bytes((floor_slot, ceiling_slot, light, flags)))
            out.write(_i16(tag))
            out.write(_i16(kind))
        if has_stack_links:
            for sector_id in range(sector_count):
                out.write(_i16(upper_stack[sector_id]))
                out.write(_i16(lower_stack[sector_id]))
        for start_x, start_z, delta_x, delta_z, front, back in nodes:
            out.write(_i16(start_x))
            out.write(_i16(start_z))
            out.write(_i16(delta_x))
            out.write(_i16(delta_z))
            out.write(_i16(front))
            out.write(_i16(back))
        segment_offset = 0
        for sector_id, wall_ids in enumerate(segments_by_sector):
            out.write(_i16(sector_id))
            out.write(_i16(segment_offset))
            out.write(_i16(len(wall_ids)))
            segment_offset += len(wall_ids)
        for sector_id, wall_ids in enumerate(segments_by_sector):
            for wall_id in wall_ids:
                wall = map_data['walls'][wall_id]
                # PortalRenderer projects front-facing segments left-to-right.
                # Blood's Build winding is opposite the C3B front-face winding,
                # so reverse segment endpoints while leaving the collision wall
                # definition and the polygon's cyclic ordering intact.
                out.write(_i16(get_vertex(wall['point2'])))
                out.write(_i16(get_vertex(wall_id)))
                out.write(_i16(wall_id))
                out.write(b'\0')  # front-facing record for this sector's wall
                # Wall panning is carried once by its surface; do not add it a
                # second time through WallSegment.textureOffset.
                out.write(_i16(0))
        out.write(struct.pack('<I', len(pvs)))
        out.write(pvs)

    # Export a JSON provenance/index sidecar for QA, not game runtime.
    report = {'map': map_name, 'version': map_data['version'],
              'sectors': sector_count, 'walls': wall_count,
              'vertices': len(vertices), 'wall_materials': len(wall_slots),
              'flat_materials': len(flat_slots), 'sprites_skipped': len(map_data['sprites']),
              'room_over_room_links': sum(1 for sector in upper_stack if sector >= 0),
              'c3b': str(output_level.relative_to(output_dir)),
              'texture_ids': {'wall': wall_slots, 'flat': flat_slots}}
    (level_dir / (Path(map_name).stem.lower() + '.json')).write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('map_or_all', help='RFF map resource such as E1M1.MAP, or "all"')
    parser.add_argument('output', type=Path, help='generated resource root (not Git)')
    args = parser.parse_args(argv)
    try:
        extracted = _load_archive_entries(args.archive, ['blood.rff', 'tiles*.art'])
        _revision, resources = read_rff(extracted['BLOOD.RFF'])
        palette = _palette_from_rff(extracted['BLOOD.RFF'])
        tile_index = _art_tile_index(extracted)
        if args.map_or_all.lower() == 'all':
            maps = [entry for entry in resources if entry['name'].endswith('.MAP')]
            reports = [compile_map(decode_blood_map(entry['data']), tile_index,
                                   palette, args.output, entry['name'])
                       for entry in maps]
            print(json.dumps({'compiled_maps': len(reports),
                              'c3b_bytes': sum((args.output / report['c3b']).stat().st_size
                                               for report in reports),
                              'maps': [report['map'] for report in reports]}, indent=2))
        else:
            map_resource = next((entry for entry in resources
                                 if entry['name'] == args.map_or_all.rsplit('/', 1)[-1].upper()), None)
            if map_resource is None:
                raise BloodAssetError('MAP resource not found: ' + args.map_or_all)
            report = compile_map(decode_blood_map(map_resource['data']), tile_index,
                                 palette, args.output, map_resource['name'])
            print(json.dumps(report, indent=2))
    except (BloodAssetError, OSError) as exc:
        parser.error(str(exc))


if __name__ == '__main__':
    sys.exit(main())
