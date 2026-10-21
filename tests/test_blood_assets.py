import struct
import tempfile
import unittest
from pathlib import Path

from tools.blood_assets import decode_blood_map, read_art, read_rff, write_bmp8


def rff_xor(data, key_offset):
    return bytes(value ^ (((key_offset + index) >> 1) & 0xff)
                 for index, value in enumerate(data))


def map_xor(data, key):
    return bytes(value ^ ((key + index) & 0xff)
                 for index, value in enumerate(data))


def make_v7_map():
    key = int.from_bytes(b'Matt', 'little')
    map_revision = 9
    header = bytearray(37)
    struct.pack_into('<iii', header, 0, 1000, 2000, 3000)
    struct.pack_into('<hh', header, 12, 512, 0)
    struct.pack_into('<h', header, 16, 0)  # one sky offset
    struct.pack_into('<I', header, 27, map_revision)
    struct.pack_into('<HHH', header, 31, 1, 3, 0)

    encrypted_header = map_xor(header, key)
    size_table = bytearray(128)
    struct.pack_into('<III', size_table, 64, 56, 24, 60)
    sky_offsets = map_xor(b'\0\0', 2)

    sector = bytearray(40)
    struct.pack_into('<HHii', sector, 0, 0, 3, -4096, 0)
    struct.pack_into('<H', sector, 16, 10)
    struct.pack_into('<H', sector, 24, 11)
    struct.pack_into('<h', sector, 38, -1)

    walls = bytearray()
    for i, (x, y) in enumerate(((0, 0), (1024, 0), (1024, 1024))):
        wall = bytearray(32)
        struct.pack_into('<ii', wall, 0, x, y)
        struct.pack_into('<hhh', wall, 8, (i + 1) % 3, -1, -1)
        struct.pack_into('<H', wall, 16, 20 + i)
        struct.pack_into('<h', wall, 30, -1)
        walls.extend(map_xor(wall, (map_revision * 40) | key))

    return (b'BLM\x1a' + struct.pack('<H', 0x0700) + encrypted_header +
            map_xor(size_table, 3) + sky_offsets +
            map_xor(sector, map_revision * 40) + bytes(walls))


class BloodAssetTests(unittest.TestCase):
    def test_decode_crypted_blood_map_geometry(self):
        level = decode_blood_map(make_v7_map())
        self.assertEqual(level['start'], {'x': 1000, 'y': 2000, 'z': 3000,
                                         'angle': 512, 'sector': 0})
        self.assertEqual(len(level['sectors']), 1)
        self.assertEqual(len(level['walls']), 3)
        self.assertEqual(level['tile_ids'], [10, 11, 20, 21, 22])
        self.assertEqual(level['walls'][1]['x'], 1024)

    def test_read_art_column_major_pixels(self):
        # Tile 7 is a 2x2 image: ART stores each column top-to-bottom.
        art = (struct.pack('<4I', 1, 1, 7, 7) +
               struct.pack('<HHI', 2, 2, 0) + bytes((1, 2, 3, 4)))
        count, tiles = read_art(art)
        self.assertEqual(count, 1)
        self.assertEqual(tiles[0]['id'], 7)
        self.assertEqual(tiles[0]['pixels'], bytes((1, 2, 3, 4)))

    def test_read_art_rejects_truncated_pixels(self):
        art = struct.pack('<4I', 1, 1, 0, 0) + struct.pack('<HHI', 4, 4, 0) + b'\0'
        with self.assertRaisesRegex(ValueError, 'truncated ART pixels'):
            read_art(art)

    def test_rff_directory_and_resource_encryption(self):
        payload_plain = b'PALETTE DATA'
        payload = rff_xor(payload_plain, 0)
        fat_offset = 16 + len(payload)
        record = bytearray(48)
        struct.pack_into('<II', record, 16, 16, len(payload))
        record[32] = 0x10
        record[33:36] = b'DAT'
        record[36:44] = b'PALETTE '
        struct.pack_into('<I', record, 44, 42)
        fat = rff_xor(bytes(record), fat_offset)
        archive = b'RFF\x1a' + struct.pack('<III', 0x300, fat_offset, 1) + payload + fat

        revision, resources = read_rff(archive)
        self.assertEqual(revision, 0x300)
        self.assertEqual(len(resources), 1)
        self.assertEqual(resources[0]['name'], 'PALETTE.DAT')
        self.assertEqual(resources[0]['data'], payload_plain)
        self.assertEqual(resources[0]['file_id'], 42)

    def test_rff_rejects_invalid_fat(self):
        header = b'RFF\x1a' + struct.pack('<III', 0x300, 0xfffffff0, 10)
        with self.assertRaisesRegex(ValueError, 'directory is outside'):
            read_rff(header)

    def test_write_indexed_bmp8(self):
        palette = [(i, i, i) for i in range(256)]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'tile.bmp'
            write_bmp8(path, 2, 2, bytes((1, 2, 3, 4)), palette)
            bmp = path.read_bytes()
        self.assertEqual(bmp[:2], b'BM')
        self.assertEqual(struct.unpack_from('<i', bmp, 18)[0], 2)
        self.assertEqual(struct.unpack_from('<i', bmp, 22)[0], 2)
        self.assertEqual(struct.unpack_from('<H', bmp, 28)[0], 8)
        pixel_offset = struct.unpack_from('<I', bmp, 10)[0]
        # BMP rows are bottom-up and padded to four bytes.
        self.assertEqual(bmp[pixel_offset:pixel_offset + 8], bytes((3, 4, 0, 0, 1, 2, 0, 0)))


if __name__ == '__main__':
    unittest.main()
