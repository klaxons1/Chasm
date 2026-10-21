import struct
import tempfile
import unittest
from pathlib import Path

from tools.blood_to_c3b import compile_map


class BloodToC3BTests(unittest.TestCase):
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
                          'next_wall': next_wall, 'x_pan': 0, 'y_pan': 0})
        map_data = {'version': 0x0700, 'map_revision': 1,
                    'start': {'x': 1024, 'y': 1024, 'z': 0, 'angle': 0, 'sector': 0},
                    'sectors': sectors, 'walls': walls, 'sprites': []}
        tile_index = {
            10: {'id': 10, 'width': 64, 'height': 64,
                 'pixels': bytes([4]) * 4096},
            20: {'id': 20, 'width': 64, 'height': 64,
                 'pixels': bytes([7]) * 4096},
        }
        palette = [(i, i, i) for i in range(256)]
        with tempfile.TemporaryDirectory() as folder:
            report = compile_map(map_data, tile_index, palette, folder, 'TEST.MAP')
            c3b = Path(folder) / 'blood/levels/test.c3b'
            data = c3b.read_bytes()
            self.assertEqual(data[:4], b'C3B1')
            self.assertEqual(report['sectors'], 2)
            self.assertEqual(report['wall_materials'], 1)
            self.assertEqual(report['flat_materials'], 1)
            self.assertEqual(Path(folder, 'blood/textures/test/w001.bmp').read_bytes()[:2], b'BM')

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

            # Verify every fixed-record region ends exactly at the PVS block,
            # matching CustomLevelLoader's little-endian C3B1 read order.
            offset = 26 + material_length
            offset += counts[0] * 4 + counts[1] * 12 + counts[2] * 10
            offset += counts[3] * 10 + counts[4] * 12 + counts[5] * 12
            offset += counts[6] * 6 + counts[7] * 9
            pvs_size = struct.unpack_from('<I', data, offset)[0]
            self.assertEqual(pvs_size, (counts[4] * counts[4] + 7) // 8)
            self.assertEqual(offset + 4 + pvs_size, len(data))


if __name__ == '__main__':
    unittest.main()
