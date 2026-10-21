import struct
import tempfile
import unittest
from pathlib import Path

from tools.blood_to_c3b import _blood_light_level, compile_map, stack_sector_links


class BloodToC3BTests(unittest.TestCase):
    def test_blood_shade_maps_zero_to_neutral(self):
        self.assertEqual(_blood_light_level(0), 8)
        self.assertEqual(_blood_light_level(32), 4)
        self.assertEqual(_blood_light_level(-32), 12)
        self.assertEqual(_blood_light_level(127), 0)
        self.assertEqual(_blood_light_level(-128), 15)

    def test_compile_small_two_sector_map(self):
        sectors = [
            {'wall_start': 0, 'wall_count': 4, 'floor_z': 0, 'ceiling_z': -32768,
             'floor_tile': 10, 'ceiling_tile': 10, 'ceiling_flags': 0,
             'floor_flags': 0, 'floor_shade': 0, 'lotag': 0},
            {'wall_start': 4, 'wall_count': 4, 'floor_z': 0, 'ceiling_z': -32768,
             'floor_tile': 10, 'ceiling_tile': 10, 'ceiling_flags': 0,
             'floor_flags': 0, 'floor_shade': 0, 'lotag': 0},
        ]
        coords = [(0, 0), (4096, 0), (4096, 4096), (0, 4096),
                  (4096, 0), (8192, 0), (8192, 4096), (4096, 4096)]
        walls = []
        for i, (x, y) in enumerate(coords):
            next_sector = 1 if i in (1, 7) else -1
            next_wall = 7 if i == 1 else (1 if i == 7 else -1)
            walls.append({'x': x, 'y': y, 'point2': (i // 4) * 4 + ((i + 1) % 4),
                          'tile': 20, 'next_sector': next_sector,
                          'next_wall': next_wall, 'x_pan': 64 if i == 0 else 0,
                          'y_pan': 128 if i == 0 else 0,
                          'x_repeat': 4 if i == 0 else 8,
                          'y_repeat': 16 if i == 0 else 8})
        map_data = {'version': 0x0700, 'map_revision': 1,
                    'start': {'x': 1024, 'y': 1024, 'z': 0, 'angle': 0, 'sector': 0},
                    'sectors': sectors, 'walls': walls,
                    'sprites': [{'lotag': 11, 'sector': 0, 'data1': 7, 'extra': 1},
                                {'lotag': 12, 'sector': 1, 'data1': 7, 'extra': 1}]}
        self.assertEqual(stack_sector_links(map_data), ([1, -1], [-1, 0]))
        tile_index = {
            10: {'id': 10, 'width': 64, 'height': 64,
                 'pixels': bytes([4]) * 4096},
            20: {'id': 20, 'width': 48, 'height': 32,
                 'pixels': bytes([7]) * (48 * 32)},
        }
        palette = [(i, i, i) for i in range(256)]
        with tempfile.TemporaryDirectory() as folder:
            report = compile_map(map_data, tile_index, palette, folder, 'TEST.MAP')
            c3b = Path(folder) / 'blood/levels/test.c3b'
            data = c3b.read_bytes()
            self.assertEqual(data[:4], b'C3B1')
            self.assertEqual(data[5], 6)  # Blood map + stack-link format flags
            self.assertEqual(report['sectors'], 2)
            self.assertEqual(report['room_over_room_links'], 1)
            self.assertEqual(report['wall_materials'], 1)
            self.assertEqual(report['flat_materials'], 1)
            wall_bmp = Path(folder, 'blood/textures/test/w001.bmp').read_bytes()
            self.assertEqual(wall_bmp[:2], b'BM')
            self.assertEqual(struct.unpack_from('<ii', wall_bmp, 18), (48, 32))
            self.assertEqual(struct.unpack_from('<H', wall_bmp, 28)[0], 8)
            pixel_offset = struct.unpack_from('<I', wall_bmp, 10)[0]
            self.assertEqual(pixel_offset, 14 + 40 + 256 * 4)
            # BMP8 preserves the original palette index rather than reducing
            # the Blood ART tile to a hand-picked 16-color approximation.
            self.assertEqual(wall_bmp[pixel_offset], 7)
            palette_entry = 54 + 7 * 4
            self.assertEqual(wall_bmp[palette_entry:palette_entry + 4], bytes((7, 7, 7, 0)))

            # C3B header counts are little-endian: V, W, objects, surfaces,
            # sectors, BSP nodes, leaves, segments.
            counts = struct.unpack_from('<8H', data, 8)
            self.assertEqual(counts[0], 6)
            self.assertEqual(counts[1], 8)
            self.assertEqual(counts[2], 1)
            self.assertEqual(counts[4:7], (2, 1, 2))
            self.assertEqual(counts[7], 8)
            material_length = struct.unpack_from('<H', data, 24)[0]
            material_path = data[26:26 + material_length].decode('utf-8')
            self.assertEqual(material_path, '/blood/textures/test/materials.c3m')
            object_offset = 26 + material_length + counts[0] * 4 + counts[1] * 12
            self.assertEqual(struct.unpack_from('<5h', data, object_offset),
                             (16, 16, 0, 1, 0))
            surface_offset = object_offset + counts[2] * 10
            # Repeat occupies the high byte; horizontal pan is in texels and
            # vertical pan is converted from Build's fractional encoding.
            self.assertEqual(struct.unpack_from('<HH', data, surface_offset),
                             (0x0440, 0x1010))
            self.assertEqual(data[surface_offset + 7], 8)  # wall shade 0 -> neutral
            sector_offset = surface_offset + counts[3] * 10
            self.assertEqual(data[sector_offset + 6], 8)  # Blood shade 0 is neutral palette
            self.assertEqual(struct.unpack_from('<4h', data, sector_offset + counts[4] * 12),
                             (1, -1, -1, 0))

            # Verify every fixed-record region ends exactly at the PVS block,
            # matching CustomLevelLoader's little-endian C3B1 read order.
            offset = 26 + material_length
            offset += counts[0] * 4 + counts[1] * 12 + counts[2] * 10
            offset += counts[3] * 10 + counts[4] * 12 + counts[4] * 4 + counts[5] * 12
            offset += counts[6] * 6 + counts[7] * 9
            pvs_size = struct.unpack_from('<I', data, offset)[0]
            self.assertEqual(pvs_size, (counts[4] * counts[4] + 7) // 8)
            self.assertEqual(offset + 4 + pvs_size, len(data))


if __name__ == '__main__':
    unittest.main()
