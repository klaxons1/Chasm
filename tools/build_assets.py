#!/usr/bin/env python3
"""
Asset Extraction & C3D2/C3B Portal Compiler Pipeline for Chasm: The Rift J2ME Port.
- Converts wall, floor/ceiling, and sky textures from tex.zip & CSM.BIN into 16-color
  4-bit BMP4 textures (/textures/custom/*.bmp) + /textures/custom/materials.c3m.
- Compiles Chasm levels (MAP.01, MAP.05, MAP.06, MAP.99 = Time Chasm Sanctum) into
  CovertOps3D-RE's C3B1 BSP/Portal format (/levels/level1.c3b .. /levels/level4.c3b)
  via c3d2_core.py from the CovertOps3D-RE clone.
- Extracts 100% complete .CAR 3D monster models WITH their 3 embedded detached limb
  submodels (RightHand, LeftHand, Head) and .3O/.ANI 3D weapons/items/props into
  /models.bin using int16 vertex coordinates, Flat Shading colors, and Vertex Painting.
- Extracts Chasm WAV sound effects and generates General MIDI music tracks.
"""

import os
import sys
import io
import math
import zlib
import wave
import struct
import zipfile
from collections import deque, Counter

ROOT_DIR = "/home/user/Chasm"
RES_DIR = os.path.join(ROOT_DIR, "res")
TEX_CUSTOM_DIR = os.path.join(RES_DIR, "textures", "custom")
LEVELS_DIR = os.path.join(RES_DIR, "levels")
TEX_ZIP = os.path.join(ROOT_DIR, "tex.zip")
SFX_ZIP = os.path.join(ROOT_DIR, "PC Computer - Chasm The Rift - Sound Effects.zip")
CSM_BIN = "/tmp/ChasmTheRift/CSM.BIN"

sys.path.insert(0, "/tmp/CovertOps3D-RE/scripts")
import c3d2_core
from png_to_bmp4 import to_indexed_15, write_bmp4

os.makedirs(RES_DIR, exist_ok=True)
os.makedirs(TEX_CUSTOM_DIR, exist_ok=True)
os.makedirs(LEVELS_DIR, exist_ok=True)


# ---------------------------------------------------------------------------
# 1. Read CSM.BIN Archive & CHASM2.PAL
# ---------------------------------------------------------------------------
class CsmArchive:
    def __init__(self, path):
        self.f = open(path, "rb")
        magic = self.f.read(4)
        assert magic == b"CSid", f"Invalid CSM.BIN magic: {magic}"
        count, = struct.unpack("<H", self.f.read(2))
        self.entries = {}
        for _ in range(count):
            rec = self.f.read(21)
            nlen = rec[0]
            name = rec[1:1 + nlen].split(b"\x00")[0].decode("ascii", errors="replace").strip().upper()
            length, offset = struct.unpack("<II", rec[13:21])
            self.entries[name] = (length, offset)

    def read(self, name):
        key = name.strip().upper()
        if key not in self.entries:
            raise KeyError(f"File {key} not found in CSM.BIN")
        length, offset = self.entries[key]
        self.f.seek(offset)
        return self.f.read(length)

    def has(self, name):
        return name.strip().upper() in self.entries


csm = CsmArchive(CSM_BIN)


def load_palette():
    raw = csm.read("CHASM2.PAL")
    pal = []
    pal_bytes = bytearray(768)
    for i in range(256):
        r = raw[i * 3 + 0]
        g = raw[i * 3 + 1]
        b = raw[i * 3 + 2]
        rf = ((r / 63.0) - 0.5) * 1.08 + 0.5
        gf = ((g / 63.0) - 0.5) * 1.08 + 0.5
        bf = ((b / 63.0) - 0.5) * 1.08 + 0.5
        ri = max(0, min(255, int(round(rf * 255.0))))
        gi = max(0, min(255, int(round(gf * 255.0))))
        bi = max(0, min(255, int(round(bf * 255.0))))
        pal.append((ri, gi, bi))
        pal_bytes[i * 3 + 0] = ri
        pal_bytes[i * 3 + 1] = gi
        pal_bytes[i * 3 + 2] = bi
    with open(os.path.join(RES_DIR, "pal.bin"), "wb") as f:
        f.write(pal_bytes)
    print(f"[OK] Wrote pal.bin ({len(pal_bytes)} bytes)")
    return pal


PALETTE = load_palette()


# ---------------------------------------------------------------------------
# 2. Pure-Python Indexed PNG Decoder for tex.zip
# ---------------------------------------------------------------------------
def decode_png_rgb_grid(data):
    w, h, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    pos = 8
    idat = bytearray()
    plte = None
    while pos < len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        chunk_data = data[pos + 8:pos + 8 + length]
        if ctype == b"PLTE":
            plte = chunk_data
        elif ctype == b"IDAT":
            idat.extend(chunk_data)
        pos += 12 + length
    raw = zlib.decompress(bytes(idat))
    bpp = 1 if color_type == 3 else (3 if color_type == 2 else 4)
    stride = w * bpp
    out = bytearray(h * stride)
    for y in range(h):
        ftype = raw[y * (stride + 1)]
        row = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        prev = out[(y - 1) * stride:y * stride] if y > 0 else bytes(stride)
        if ftype == 1:
            for i in range(bpp, stride):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                row[i] = (row[i] + ((a + b) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = row

    rgb_rows = []
    for y in range(h):
        r_row = []
        for x in range(w):
            if color_type == 3:
                idx = out[y * w + x]
                if plte and idx * 3 + 2 < len(plte):
                    r_row.append((plte[idx * 3], plte[idx * 3 + 1], plte[idx * 3 + 2]))
                else:
                    r_row.append(PALETTE[idx])
            else:
                off = (y * w + x) * bpp
                r_row.append((out[off], out[off + 1], out[off + 2]))
        rgb_rows.append(r_row)
    return w, h, rgb_rows


def resample_rgb_grid(w, h, rows, target_w, target_h):
    out = []
    for ty in range(target_h):
        sy = min(h - 1, (ty * h) // target_h)
        row = []
        for tx in range(target_w):
            sx = min(w - 1, (tx * w) // target_w)
            r, g, b = rows[sy][sx]
            # Avoid pure black (0,0,0) in opaque wall/flat textures so index 0 isn't transparent
            if r < 8 and g < 8 and b < 8:
                r, g, b = 12, 12, 16
            row.append((r, g, b))
        out.append(row)
    return out


def save_rgb_as_bmp4(rgb_rows, out_path):
    th = len(rgb_rows)
    tw = len(rgb_rows[0])
    flat_rgba = []
    for row in rgb_rows:
        for r, g, b in row:
            flat_rgba.append((r, g, b, 255))
    colors, indices = to_indexed_15(flat_rgba)
    write_bmp4(out_path, tw, th, colors, indices)


# ---------------------------------------------------------------------------
# 3. Build C3D2 Custom BMP4 Materials + /textures/custom/materials.c3m
# ---------------------------------------------------------------------------
def build_c3d2_materials():
    zf = zipfile.ZipFile(TEX_ZIP, "r")
    png_map = {}
    for name in zf.namelist():
        if name.lower().endswith(".png"):
            png_map[os.path.basename(name).upper()] = name

    wall_candidates = [
        # Episode 1: Industrial / Military Base (1..10)
        "WALL00.CEL.PNG", "WALL01.CEL.PNG", "WALL02.CEL.PNG", "WALL03.CEL.PNG",
        "WALL04.CEL.PNG", "WALL05.CEL.PNG", "WALL07.CEL.PNG", "WALL09.CEL.PNG",
        "WALL12.CEL.PNG", "WALL14.CEL.PNG",
        # Episode 2: Ancient Egypt / Temple of Seth (11..18)
        "WALL22.CEL.PNG", "WALL23.CEL.PNG", "WALL24.CEL.PNG", "WALL25.CEL.PNG",
        "WALL26.CEL.PNG", "WALL28.CEL.PNG", "WALL30.CEL.PNG", "WALL33.CEL.PNG",
        # Episode 3: Medieval / Time Chasm Sanctum + Doors & Switches (19..24)
        "WALL40.CEL.PNG", "WALL42.CEL.PNG", "WALL45.CEL.PNG", "WALL48.CEL.PNG",
        "WALL52.CEL.PNG", "WALL55.CEL.PNG",
    ]
    all_pngs = sorted(png_map.keys())
    manifest_lines = ["# Chasm: The Rift C3D2 Material Set"]

    for slot in range(1, 25):
        cand = wall_candidates[slot - 1]
        if cand not in png_map:
            cand = all_pngs[(slot * 17) % len(all_pngs)]
        w, h, rows = decode_png_rgb_grid(zf.read(png_map[cand]))
        grid64 = resample_rgb_grid(w, h, rows, 64, 64)
        fname = f"w{slot:02d}.bmp"
        save_rgb_as_bmp4(grid64, os.path.join(TEX_CUSTOM_DIR, fname))
        manifest_lines.append(f"wall.{slot}={fname}")

    # Extract 12 authentic 64x64 Floor/Ceiling flat textures from FLOORS.01 & FLOORS.05
    floors_01 = csm.read("FLOORS.01")
    floors_05 = csm.read("FLOORS.05")
    hdr_sz = 64
    tile_sz = 86 * 64  # 64x64 + mipmaps
    flat_sources = [
        (floors_01, 0), (floors_01, 1), (floors_01, 2), (floors_01, 5),
        (floors_01, 9), (floors_01, 10), (floors_01, 16), (floors_01, 18),
        (floors_05, 0), (floors_05, 2), (floors_05, 5), (floors_05, 29),
    ]
    for slot, (fblob, tidx) in enumerate(flat_sources, start=1):
        off = hdr_sz + tidx * tile_sz
        if off + 4096 > len(fblob):
            off = hdr_sz
        raw_tile = fblob[off:off + 4096]
        rows = []
        for y in range(64):
            r_row = []
            for x in range(64):
                r, g, b = PALETTE[raw_tile[y * 64 + x]]
                if r < 8 and g < 8 and b < 8:
                    r, g, b = 12, 12, 16
                r_row.append((r, g, b))
            rows.append(r_row)
        fname = f"f{slot:02d}.bmp"
        save_rgb_as_bmp4(rows, os.path.join(TEX_CUSTOM_DIR, fname))
        manifest_lines.append(f"flat.{slot}={fname}")

    # Skybox texture (64x128 BMP4)
    sky_candidates = [k for k in csm.entries if k.startswith("SKY") and k.endswith(".CEL")]
    sky_rows = []
    if sky_candidates:
        sdata = csm.read(sky_candidates[0])
        sw, sh = struct.unpack("<HH", sdata[2:6])
        spix = sdata[0x310:]
        for ty in range(128):
            sy = min(sh - 1, (ty * sh) // 128)
            r_row = []
            for tx in range(64):
                sx = min(sw - 1, (tx * sw) // 64)
                idx = spix[sy * sw + sx] if sy * sw + sx < len(spix) else 0
                r, g, b = PALETTE[idx]
                if r < 8 and g < 8 and b < 8:
                    r, g, b = 18, 22, 36
                r_row.append((r, g, b))
            sky_rows.append(r_row)
    else:
        for ty in range(128):
            sky_rows.append([(24, 34, 58 + (ty // 3))] * 64)

    save_rgb_as_bmp4(sky_rows, os.path.join(TEX_CUSTOM_DIR, "sky.bmp"))
    manifest_lines.append("sky=sky.bmp")

    manifest_path = os.path.join(TEX_CUSTOM_DIR, "materials.c3m")
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write("\n".join(manifest_lines) + "\n")
    print(f"[OK] Wrote C3D2 material set: 24 walls, 12 flats, 1 sky -> {manifest_path}")


# ---------------------------------------------------------------------------
# 4. Extract 100% Complete .CAR & .3O 3D Models + Dismemberment Submodels
# ---------------------------------------------------------------------------
def enhance_color(r, g, b, boost=1.18):
    lum = 0.299 * r + 0.587 * g + 0.114 * b
    if lum < 22.0 and max(r, g, b) > 0:
        scale = 26.0 / max(1.0, lum)
        r, g, b = r * scale, g * scale, b * scale
        lum = 26.0
    r = lum + (r - lum) * 1.25
    g = lum + (g - lum) * 1.25
    b = lum + (b - lum) * 1.25
    return (
        max(14, min(255, int(round(r * boost)))),
        max(14, min(255, int(round(g * boost)))),
        max(14, min(255, int(round(b * boost)))),
    )


def sample_uv_neighborhood(tex, tex_w, tex_h, u_px, v_px, radius=1):
    r_sum, g_sum, b_sum, cnt = 0, 0, 0, 0
    for dy in range(-radius, radius + 1):
        vy = max(0, min(tex_h - 1, v_px + dy))
        for dx in range(-radius, radius + 1):
            ux = (u_px + dx) % tex_w
            idx = tex[vy * tex_w + ux]
            if idx != 0 and idx != 255:
                pr, pg, pb = PALETTE[idx]
                r_sum += pr
                g_sum += pg
                b_sum += pb
                cnt += 1
    if cnt == 0:
        idx = tex[max(0, min(tex_h - 1, v_px)) * tex_w + (u_px % tex_w)]
        return enhance_color(*PALETTE[idx])
    return enhance_color(r_sum // cnt, g_sum // cnt, b_sum // cnt)


def sample_triangle_flat_color(tex, tex_w, tex_h, uvs):
    (u0, v0), (u1, v1), (u2, v2) = uvs
    r_sum, g_sum, b_sum, cnt = 0, 0, 0, 0
    weights = [
        (1, 1, 1), (2, 1, 1), (1, 2, 1), (1, 1, 2),
        (3, 1, 1), (1, 3, 1), (1, 1, 3), (2, 2, 1), (1, 2, 2), (2, 1, 2)
    ]
    for w0, w1, w2 in weights:
        wt = w0 + w1 + w2
        u = int((u0 * w0 + u1 * w1 + u2 * w2) // wt) % tex_w
        v = max(0, min(tex_h - 1, int((v0 * w0 + v1 * w1 + v2 * w2) // wt)))
        idx = tex[v * tex_w + u]
        if idx != 0 and idx != 255:
            pr, pg, pb = PALETTE[idx]
            r_sum += pr
            g_sum += pg
            b_sum += pb
            cnt += 1
    if cnt == 0:
        return sample_uv_neighborhood(tex, tex_w, tex_h, u0, v0, 2)
    return enhance_color(r_sum // cnt, g_sum // cnt, b_sum // cnt)


def compute_face_normal(p0, p1, p2):
    ux, uy, uz = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
    vx, vy, vz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
    nx = uy * vz - uz * vy
    ny = uz * vx - ux * vz
    nz = ux * vy - uy * vx
    length = math.sqrt(nx * nx + ny * ny + nz * nz)
    if length < 1e-6:
        return 0, 0, 127
    return (
        max(-127, min(127, int(round((nx / length) * 127.0)))),
        max(-127, min(127, int(round((ny / length) * 127.0)))),
        max(-127, min(127, int(round((nz / length) * 127.0)))),
    )


def parse_polygons_exact(poly_bytes, pcount, vcount, tex, tex_h, base_verts, is_car=True, v_shift=0):
    """
    Parses 100% of polygons from a .CAR or .3O model without decimation.
    - Maps group_id == 0 -> 64 (Body), preserving 1, 2, 4, 8, 16, 32, 128 bitmask.
    - Triangulates quads as (v2, v1, v0) and (v0, v3, v2) matching Chasm-Reverse.
    - Computes both Flat Shading color and per-vertex Vertex Painting colors.
    """
    tex_w = 64
    tris = []
    zs = [v[2] for v in base_verts] if base_verts else [0, 1024]
    z_min, z_max = min(zs), max(zs)
    z_span = max(1.0, z_max - z_min)

    for p in range(pcount):
        off = p * 32
        v0, v1, v2, v3 = struct.unpack("<4H", poly_bytes[off:off + 8])
        uv_raw = struct.unpack("<8H", poly_bytes[off + 8:off + 24])
        group_id, flags, v_offset = struct.unpack("<BBH", poly_bytes[off + 28:off + 32])
        gmask = 64 if group_id == 0 else group_id

        pts_uv = []
        for k in range(4):
            if is_car:
                u_px = (uv_raw[k * 2] >> 8) % tex_w
                v_px = max(0, min(tex_h - 1, (uv_raw[k * 2 + 1] + 4 * v_offset) >> 8))
            else:
                u_px = uv_raw[k * 2] % tex_w
                v_px = max(0, min(tex_h - 1, uv_raw[k * 2 + 1] + v_offset + v_shift))
            pts_uv.append((u_px, v_px))

        # Exact Chasm-Reverse triangulation: (v2, v1, v0) and (v0, v3, v2)
        tri_defs = [(v2, v1, v0, (pts_uv[2], pts_uv[1], pts_uv[0]))]
        if v3 < vcount and v3 != 0xFFFF and v3 != v2 and v3 != v0:
            tri_defs.append((v0, v3, v2, (pts_uv[0], pts_uv[3], pts_uv[2])))

        for ia, ib, ic, tri_uv in tri_defs:
            if ia >= vcount or ib >= vcount or ic >= vcount:
                continue
            pa, pb, pc = base_verts[ia], base_verts[ib], base_verts[ic]
            nx, ny, nz = compute_face_normal(pa, pb, pc)

            fr, fg, fb = sample_triangle_flat_color(tex, tex_w, tex_h, tri_uv)

            vcols = []
            for idx_v, (u_px, v_px) in zip((ia, ib, ic), tri_uv):
                vr, vg, vb = sample_uv_neighborhood(tex, tex_w, tex_h, u_px, v_px, radius=1)
                vz_pos = base_verts[idx_v][2]
                ao = 0.78 + 0.34 * ((vz_pos - z_min) / z_span)
                vr = max(12, min(255, int(vr * ao)))
                vg = max(12, min(255, int(vg * ao)))
                vb = max(12, min(255, int(vb * ao)))
                vcols.append((vr, vg, vb))

            tuv = (tri_uv[0][0], tri_uv[1][0], tri_uv[2][0],
                   tri_uv[0][1], tri_uv[1][1], tri_uv[2][1])
            tris.append((ia, ib, ic, gmask, flags & 0xFF, (nx, ny, nz), (fr, fg, fb), vcols, tuv))

    return tris


def extract_car_model_with_submodels(car_name):
    data = csm.read(car_name)
    # Exact CARHeader: animations[20] at offset 0..40 (in BYTES), submodels_animations[3][2] at 40..52
    anim_byte_sizes = struct.unpack("<20H", data[0:40])
    sub_anim_byte_sizes = [struct.unpack("<2H", data[40 + i * 4:44 + i * 4]) for i in range(3)]
    vcount, pcount, texels = struct.unpack("<3H", data[0x4866:0x486C])
    tex_h = max(1, texels // 64)
    tex = data[0x486C:0x486C + texels]
    v_base_off = 0x486C + texels

    anim_starts = [0] * 20
    anim_counts = [0] * 20
    cur_f = 0
    for a in range(20):
        cnt = anim_byte_sizes[a] // (vcount * 6)
        anim_starts[a] = cur_f
        anim_counts[a] = cnt
        cur_f += cnt
    total_main_frames = cur_f

    def read_raw_frame(f_idx):
        f_idx = max(0, min(total_main_frames - 1, f_idx))
        off = v_base_off + f_idx * vcount * 6
        return [struct.unpack("<3h", data[off + v * 6:off + (v + 1) * 6]) for v in range(vcount)]

    # Normalize Z so feet stand on floor (or slight hover for WMAN)
    f0_raw = read_raw_frame(0)
    z_min_0 = min(v[2] for v in f0_raw)
    hover = 220 if car_name.upper() == "WMAN.CAR" else 0

    def normalize_verts(raw_verts):
        # Keep raw 1/2048 scale as int16 so 2048 = 1 tile (64 C3D height units -> >> 5)
        out = []
        for vx, vy, vz in raw_verts:
            out.append((vx, vy, (vz - z_min_0) + hover))
        return out

    def pick_anim_frames(anim_candidates, num_pick):
        for a in anim_candidates:
            if anim_counts[a] > 0:
                st = anim_starts[a]
                ln = anim_counts[a]
                if num_pick == 1:
                    return [st + ln // 2]
                return [st + (i * (ln - 1)) // (num_pick - 1) for i in range(num_pick)]
        return [0] * num_pick

    # 10 keyframes per monster:
    # 0..1: Idle (2 frames)
    # 2..4: Walk cycle (3 frames)
    # 5..6: Attack (2 frames: windup, strike/fire)
    # 7:    Pain / Limb-severed stagger (1 frame)
    # 8..9: Death (2 frames: falling, dead on floor)
    kf_indices = (
        pick_anim_frames([2, 3, 4, 0], 2) +
        pick_anim_frames([0, 1], 3) +
        pick_anim_frames([5, 6, 7, 8], 2) +
        pick_anim_frames([11, 12, 9, 10], 1) +
        pick_anim_frames([16, 17, 18], 2)
    )

    frame0_verts = normalize_verts(read_raw_frame(kf_indices[0]))
    tris = parse_polygons_exact(
        data[0x66:0x66 + pcount * 32], pcount, vcount, tex, tex_h, frame0_verts, is_car=True
    )
    frames = [normalize_verts(read_raw_frame(fi)) for fi in kf_indices]

    # Extract the 3 detached limb submodels (0=RightHand, 1=LeftHand, 2=Head)
    sub_off = v_base_off + total_main_frames * vcount * 6
    submodels = []
    for i in range(3):
        s_sz = sub_anim_byte_sizes[i][0] + sub_anim_byte_sizes[i][1]
        if s_sz == 0:
            submodels.append((0, [], []))
            continue
        sv_cnt, sp_cnt = struct.unpack("<HH", data[sub_off + 0x4800:sub_off + 0x4804])
        sf0 = sub_anim_byte_sizes[i][0] // (sv_cnt * 6)
        sf1 = sub_anim_byte_sizes[i][1] // (sv_cnt * 6)
        sf_tot = sf0 + sf1
        sv_base = sub_off + 0x4806

        def read_sub_frame(sfi):
            sfi = max(0, min(sf_tot - 1, sfi))
            soff = sv_base + sfi * sv_cnt * 6
            return [struct.unpack("<3h", data[soff + v * 6:soff + (v + 1) * 6]) for v in range(sv_cnt)]

        # 3 keyframes per detached limb: 0=airborne spin A, 1=airborne spin B, 2=resting on floor
        s_picks = [0, max(0, sf0 // 2), max(0, sf_tot - 1)]
        s_frames = [read_sub_frame(p) for p in s_picks]
        s_tris = parse_polygons_exact(
            data[sub_off:sub_off + sp_cnt * 32], sp_cnt, sv_cnt, tex, tex_h, s_frames[0], is_car=True
        )
        submodels.append((sv_cnt, s_tris, s_frames))
        sub_off += 0x4806 + s_sz

    return vcount, tris, frames, submodels, tex, tex_h


def extract_3o_weapon_model(model_name, stat_ani, shoot_ani):
    data = csm.read(model_name)
    vc, pc, th_raw = struct.unpack("<3H", data[0x4800:0x4806])
    v_shift = th_raw & ~1023
    tex_h = max(1, th_raw & 1023)
    tex = data[0x4806:0x4806 + 64 * tex_h]

    def read_ani_frames(aname):
        if not aname or not csm.has(aname):
            return [[struct.unpack("<3h", data[0x3200 + v * 6:0x3200 + (v + 1) * 6]) for v in range(vc)]]
        adata = csm.read(aname)
        fc = max(1, (len(adata) - 2) // (vc * 6))
        out = []
        for fi in range(fc):
            off = 2 + fi * vc * 6
            out.append([struct.unpack("<3h", adata[off + v * 6:off + (v + 1) * 6]) for v in range(vc)])
        return out

    idle_all = read_ani_frames(stat_ani)
    shoot_all = read_ani_frames(shoot_ani)
    # 4 keyframes: 0=Idle, 1=Fire start, 2=Peak recoil, 3=Recovery
    frames = [
        idle_all[0],
        shoot_all[min(len(shoot_all) - 1, max(0, len(shoot_all) // 4))],
        shoot_all[min(len(shoot_all) - 1, max(0, len(shoot_all) // 2))],
        shoot_all[min(len(shoot_all) - 1, max(0, (3 * len(shoot_all)) // 4))],
    ]
    tris = parse_polygons_exact(
        data[0x00:pc * 32], pc, vc, tex, tex_h, frames[0], is_car=False, v_shift=v_shift
    )
    return vc, tris, frames, [], tex, tex_h


def extract_3o_world_model(model_name, scale=1.0):
    data = csm.read(model_name)
    vc, pc, th_raw = struct.unpack("<3H", data[0x4800:0x4806])
    v_shift = th_raw & ~1023
    tex_h = max(1, th_raw & 1023)
    tex = data[0x4806:0x4806 + 64 * tex_h]

    raw_verts = [struct.unpack("<3h", data[0x3200 + v * 6:0x3200 + (v + 1) * 6]) for v in range(vc)]
    z_min = min(v[2] for v in raw_verts)
    verts = [(int(vx * scale), int(vy * scale), int((vz - z_min) * scale)) for vx, vy, vz in raw_verts]
    tris = parse_polygons_exact(
        data[0x00:pc * 32], pc, vc, tex, tex_h, verts, is_car=False, v_shift=v_shift
    )
    return vc, tris, [verts], [], tex, tex_h


def serialize_mesh(vc, tris, frames, tex=b"", tex_h=0):
    """
    Mesh record (big-endian):
      u16 vCount, u16 triCount, u16 frameCount
      u16 texH, texH*64 bytes palette indices (64 texels wide; texH=0 -> reuse parent texture)
      per triangle: 3 x u8 vertex index, u8 group, u8 flags, 3 x s8 normal, 3 x u8 flat RGB,
                    3 x RGB vertex colours, 3 x u8 texture U, 3 x u16 texture V
      frames: vCount x 3 x s16 per frame
    """
    buf = bytearray()
    buf.extend(struct.pack(">HHH", vc, len(tris), len(frames)))
    tex_bytes = bytes(tex[:64 * tex_h]) if tex_h > 0 else b""
    if tex_h > 0 and len(tex_bytes) < 64 * tex_h:
        tex_bytes = tex_bytes + bytes(64 * tex_h - len(tex_bytes))
    buf.extend(struct.pack(">H", tex_h if tex_h > 0 else 0))
    buf.extend(tex_bytes)
    for ia, ib, ic, gmask, flags, (nx, ny, nz), (fr, fg, fb), vcols, tuv in tris:
        buf.extend(struct.pack(">BBBBBbbbBBB", ia, ib, ic, gmask & 0xFF, flags & 0xFF, nx, ny, nz, fr, fg, fb))
        for vr, vg, vb in vcols:
            buf.extend(struct.pack(">BBB", vr, vg, vb))
        u0, u1, u2, v0, v1, v2 = tuv
        buf.extend(struct.pack(">BBBHHH", u0 & 0xFF, u1 & 0xFF, u2 & 0xFF,
                               max(0, min(65535, v0)), max(0, min(65535, v1)), max(0, min(65535, v2))))
    for fverts in frames:
        for vx, vy, vz in fverts:
            buf.extend(struct.pack(">hhh", max(-32767, min(32767, vx)), max(-32767, min(32767, vy)), max(-32767, min(32767, vz))))
    return buf


def build_models_pack():
    monster_names = [
        "MONGF.CAR",     # 0: Mong Soldier
        "WMAN.CAR",      # 1: WingMan Gargoyle
        "SPIDER.CAR",    # 2: Giant Spider
        "TT_TOWER.CAR",  # 3: Auto-Turret
        "FAUST1.CAR",    # 4: Faust Cyber-Trooper
        "DEADMAN1.CAR",  # 5: Undead Mummy/Zombie
        "PANT1.CAR",     # 6: Punisher Executioner
        "JOKER6.CAR",    # 7: Joker Blade-Jester
        "SCELET3.CAR",   # 8: Skeleton Warrior
        "LION4.CAR",     # 9: Guardian Lion
        "VIKING4.CAR",   # 10: Viking Warlord
    ]
    weapon_specs = [
        ("OGUN6.3O",  "OGUN6.ANI",   "OGATCK.ANI"),   # 11: Double-Barrel Rifle
        ("RIF_H5.3O", "RIF_H2.ANI",  "RIF_H1F.ANI"),  # 12: Super Shotgun
        ("V8H.3O",    "VSTAT7H.ANI", "VFIRE7H.ANI"),  # 13: Volcano Chaingun
        ("DISK.3O",   "DSTAT.ANI",   "DATTACK.ANI"),  # 14: Saw-Blade Launcher
        ("CROSS.3O",  "CROSS.ANI",   "CROSSA.ANI"),   # 15: Energy Crossbow
        ("GR4.3O",    "GR_STAT.ANI", "GR_FIRE.ANI"),  # 16: Grenade Launcher
    ]
    prop_specs = [
        ("LIFE.3O",     1.3),  # 17: Medkit
        ("BIGLIFE.3O",  1.3),  # 18: MegaHealth
        ("ARMOR3.3O",   1.3),  # 19: Body Armor
        ("HELMET1.3O",  1.4),  # 20: Helmet
        ("BAG.3O",      1.3),  # 21: Ammo Pack
        ("W_RIFLE.3O",  1.3),  # 22: Super Shotgun Pickup
        ("W_VCANO.3O",  1.3),  # 23: Volcano Pickup
        ("WDISK.3O",    1.3),  # 24: Blade Pickup
        ("WCROSS.3O",   1.3),  # 25: Crossbow Pickup
        ("W_GR.3O",     1.3),  # 26: Grenade Pickup
        ("R_CARD.3O",   1.5),  # 27: Red Key
        ("G_CARD.3O",   1.5),  # 28: Green Key
        ("B_CARD.3O",   1.5),  # 29: Blue Key
        ("BAREL1.3O",   1.1),  # 30: Explosive Barrel
        ("FARAON.3O",   0.85), # 31: Pharaoh Statue
        ("OBELISK.3O",  0.55), # 32: Obelisk / Pillar
        ("GIB1.3O",     1.3),  # 33: Flying Gore Gib
        ("JDISK.3O",    1.2),  # 34: Saw-Disk Projectile
        ("SWORD.3O",    1.2),  # 35: Projectile / Rocket / Bolt
    ]

    all_models = []
    for mname in monster_names:
        all_models.append(extract_car_model_with_submodels(mname))
    for mname, st_ani, sh_ani in weapon_specs:
        all_models.append(extract_3o_weapon_model(mname, st_ani, sh_ani))
    for mname, sc in prop_specs:
        all_models.append(extract_3o_world_model(mname, scale=sc))

    out = bytearray()
    out.extend(struct.pack(">H", len(all_models)))
    total_tris = 0
    total_sub_tris = 0
    for vc, tris, frames, subs, tex, tex_h in all_models:
        total_tris += len(tris)
        out.extend(serialize_mesh(vc, tris, frames, tex, tex_h))
        out.append(len(subs))
        # Detached limbs reuse the parent's texture atlas (texH = 0 in their record).
        for svc, stris, sframes in subs:
            total_sub_tris += len(stris)
            out.extend(serialize_mesh(svc, stris, sframes))

    models_path = os.path.join(RES_DIR, "models.bin")
    with open(models_path, "wb") as f:
        f.write(out)
    print(f"[OK] Wrote models.bin ({len(all_models)} models, {total_tris} main tris + {total_sub_tris} detached limb tris, {len(out)} bytes)")


# ---------------------------------------------------------------------------
# 5. Compile Chasm Maps into C3B1 BSP/Portal Levels + Entity Sidecars
# ---------------------------------------------------------------------------
SCALE = 96  # 1 Chasm cell = 96 C3D units

CHASM_TO_LOCAL_MON = {
    1: 7,   # Joker
    2: 2,   # Spider
    3: 1,   # WingMan
    4: 8,   # Skeleton
    5: 6,   # Punisher
    6: 0,   # Mong
    7: 4,   # Faust
    8: 3,   # Turret
    9: 4,   # SFag -> Faust
    10: 5,  # DeadMan
    11: 9,  # Hog -> Lion
    12: 6,  # Alien -> Punisher
    13: 10, # Mincer -> Viking
    14: 7,  # Jumper -> Joker
    15: 10, # Viking
    16: 9,  # Lion
}

CHASM_TO_LOCAL_ITEM = {
    22: 17, 23: 18, 21: 19, 24: 20, 20: 21,
    1: 22, 2: 23, 3: 24, 4: 25, 5: 26,
    8: 21, 9: 21, 10: 21, 11: 21, 12: 21, 13: 21,
    17: 27, 18: 28, 19: 29,
}


def compile_chasm_map_to_c3b(level_idx, map_num, max_cells, wall_base_slot, flat_base_slot):
    mdata = csm.read(f"MAP.{map_num:02d}")
    rdata = csm.read(f"RESOURCE.{map_num:02d}").decode("ascii", errors="ignore")
    ceils = mdata[0x23001:0x24001]
    floors = mdata[0x24001:0x25001]
    lights = mdata[0x25001:0x26001]

    # Parse static 3O model filenames from RESOURCE.XX
    model_files = {}
    lines = [l.strip() for l in rdata.splitlines()]
    i = 0
    while i < len(lines) and ";Models" not in lines[i]:
        i += 1
    i += 1
    m_idx = 0
    while i < len(lines) and ";Walls" not in lines[i]:
        line = lines[i]
        if line and not line.startswith(";"):
            parts = line.split()
            if len(parts) >= 10:
                model_files[m_idx] = parts[9].upper()
            m_idx += 1
        i += 1

    # Parse lights and monsters from MAP.XX (0x27001)
    l_cnt, = struct.unpack("<H", mdata[0x27001:0x27003])
    m_off = 0x27003 + l_cnt * 12
    m_cnt, = struct.unpack("<H", mdata[m_off:m_off + 2])
    spawn_candidates = []
    raw_monsters = []
    for k in range(m_cnt):
        eb = mdata[m_off + 2 + k * 8:m_off + 2 + (k + 1) * 8]
        px, py, mid, mang, diff = struct.unpack("<3H2B", eb)
        fx, fy = px / 256.0, py / 256.0
        if mid == 100:
            spawn_candidates.append((fx, fy, mang & 7))
        elif mid > 100:
            raw_monsters.append((CHASM_TO_LOCAL_MON.get(mid - 100, 0), fx, fy, mang & 7))

    # Pick the spawn candidate and cardinal direction with the longest open line of sight
    best_open = -1
    spawn_fx, spawn_fy, c3d_ang = 31.5, 58.5, 2048
    fwd_dx, fwd_dy = 0, -1
    for sfx, sfy, _ in (spawn_candidates if spawn_candidates else [(31.5, 58.5, 2)]):
        tsx, tsy = int(sfx), int(sfy)
        if not (0 <= tsx < 64 and 0 <= tsy < 64) or floors[tsx * 64 + tsy] >= 62:
            continue
        for (dx, dy), cand_ang in [((0, 1), 0), ((1, 0), 1024), ((0, -1), 2048), ((-1, 0), 3072)]:
            dist = 0
            while (0 <= tsx + dx * (dist + 1) < 64 and 0 <= tsy + dy * (dist + 1) < 64 and
                   floors[(tsx + dx * (dist + 1)) * 64 + (tsy + dy * (dist + 1))] < 62):
                dist += 1
            if dist > best_open:
                best_open = dist
                spawn_fx, spawn_fy = tsx + 0.5, tsy + 0.5
                c3d_ang = cand_ang
                fwd_dx, fwd_dy = dx, dy

    # Parse wall textures, items, and static 3O props/stairs from 0x18001
    cell_wall_tex = {}
    cell_floor_step = {}
    cell_ceil_low = {}
    raw_items = []
    raw_props = []
    walls_off = 0x18001
    for gx in range(64):
        for gy in range(64):
            w = mdata[walls_off + (gy + gx * 64) * 11:walls_off + (gy + gx * 64 + 1) * 11]
            tid, sz, unk, c00, c01, c10, c11 = struct.unpack("<3B4H", w)
            if tid == 0:
                continue
            wx0, wy0 = c00 / 256.0, c01 / 256.0
            wx1, wy1 = c10 / 256.0, c11 / 256.0
            if tid < 128:
                mx, my = int((wx0 + wx1) * 0.5), int((wy0 + wy1) * 0.5)
                slot = wall_base_slot + (tid % 8)
                for dx in range(-1, 2):
                    for dy in range(-1, 2):
                        cell_wall_tex[(mx + dx, my + dy)] = slot
            elif 131 <= tid < 163:
                raw_items.append((CHASM_TO_LOCAL_ITEM.get(tid - 131, 17), wx0, wy0))
            elif 163 <= tid < 227:
                mfname = model_files.get(tid - 163, "")
                cx, cy = max(0, min(63, int(wx0))), max(0, min(63, int(wy0)))
                if any(s in mfname for s in ("STAI", "S_TAIR", "PYRST", "P_1S", "P_2S", "BOX~", "TABLE")):
                    cell_floor_step[(cx, cy)] = 12
                elif any(s in mfname for s in ("BALK", "PTL", "POTOL", "AR_0", "DOMED", "SVOD")):
                    cell_ceil_low[(cx, cy)] = 84
                elif "BAREL" in mfname:
                    raw_props.append((30, wx0, wy0, unk & 7))
                elif "FARAON" in mfname:
                    raw_props.append((31, wx0, wy0, unk & 7))
                elif any(s in mfname for s in ("OBELISK", "COLUMN", "COLU_", "PIER", "PALM", "VASE")):
                    raw_props.append((32, wx0, wy0, unk & 7))

    sx, sy = int(spawn_fx), int(spawn_fy)
    visited = set([(sx, sy)])
    q = deque([(sx, sy)])
    while q and len(visited) < max_cells:
        cx, cy = q.popleft()
        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nx, ny = cx + dx, cy + dy
            if 0 <= nx < 64 and 0 <= ny < 64 and (nx, ny) not in visited:
                if floors[nx * 64 + ny] < 62:
                    visited.add((nx, ny))
                    q.append((nx, ny))

    # Re-evaluate best spawn cell and cardinal view direction strictly inside visited C3B sectors
    best_vis_open = -1
    for (tsx, tsy) in sorted(visited):
        for (dx, dy), cand_ang in [((0, 1), 0), ((1, 0), 1024), ((0, -1), 2048), ((-1, 0), 3072)]:
            dist = 0
            while (tsx + dx * (dist + 1), tsy + dy * (dist + 1)) in visited:
                dist += 1
            if dist > best_vis_open:
                best_vis_open = dist
                spawn_fx, spawn_fy = tsx + 0.5, tsy + 0.5
                c3d_ang = cand_ang
                fwd_dx, fwd_dy = dx, dy

    # Greedy rectangle merge into convex clockwise sectors
    unassigned = set(visited)
    rects = []
    cell_to_rect = {}
    for (x0, y0) in sorted(visited, key=lambda p: (p[1], p[0])):
        if (x0, y0) not in unassigned:
            continue
        sky = (ceils[x0 * 64 + y0] == 62)
        fstep = cell_floor_step.get((x0, y0), 0)
        x1 = x0 + 1
        while (x1, y0) in unassigned and (ceils[x1 * 64 + y0] == 62) == sky and cell_floor_step.get((x1, y0), 0) == fstep and (x1 - x0) < 7:
            x1 += 1
        y1 = y0 + 1
        while (y1 - y0) < 7:
            ok = True
            for tx in range(x0, x1):
                if (tx, y1) not in unassigned or (ceils[tx * 64 + y1] == 62) != sky or cell_floor_step.get((tx, y1), 0) != fstep:
                    ok = False
                    break
            if ok:
                y1 += 1
            else:
                break
        sec_id = len(rects)
        for ty in range(y0, y1):
            for tx in range(x0, x1):
                unassigned.remove((tx, ty))
                cell_to_rect[(tx, ty)] = sec_id
        rects.append((x0, y0, x1, y1, sky, fstep))

    def to_xz(gx, gy):
        return ((gx - 32) * SCALE, (gy - 32) * SCALE)

    vert_map = {}
    vertices = []

    def get_v(gx, gy):
        pt = to_xz(gx, gy)
        if pt not in vert_map:
            vert_map[pt] = len(vertices)
            vertices.append(pt)
        return vert_map[pt]

    sectors = []
    for sec_id, (x0, y0, x1, y1, sky, fstep) in enumerate(rects):
        ft = flat_base_slot + (floors[x0 * 64 + y0] % 4)
        ct = 51 if sky else (flat_base_slot + (ceils[x0 * 64 + y0] % 4))
        amb = lights[x0 * 64 + y0]
        light = max(9, min(15, 15 - (amb // 4)))
        fl_h = fstep
        cl_h = 128 if sky else cell_ceil_low.get((x0, y0), 96)
        sectors.append(dict(floor=fl_h, ceil=cl_h, ceil_tex=ct, floor_tex=ft,
                            light_packed=light << 4, tag=0, type=0))

    surfaces = []
    walls = []

    def add_surface(sec_id, tex_id):
        idx = len(surfaces)
        surfaces.append(dict(ox=0, oy=0, upper=tex_id, lower=tex_id, main=tex_id, sector=sec_id))
        return idx

    def pick_wall_tex(gx, gy):
        return cell_wall_tex.get((gx, gy), wall_base_slot + ((gx + gy) % 8))

    for sec_id, (x0, y0, x1, y1, sky, fstep) in enumerate(rects):
        # 1. West edge: x = x0, y goes y0 -> y1
        y = y0
        while y < y1:
            nb = cell_to_rect.get((x0 - 1, y), -1)
            y_end = y + 1
            while y_end < y1 and cell_to_rect.get((x0 - 1, y_end), -1) == nb:
                y_end += 1
            sv, ev = get_v(x0, y), get_v(x0, y_end)
            wtex = pick_wall_tex(x0, y)
            if nb < 0:
                sf = add_surface(sec_id, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=1, type=0, special=0, front=sf, back=-1))
            elif sec_id < nb:
                sf_f = add_surface(sec_id, wtex)
                sf_b = add_surface(nb, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=4, type=0, special=0, front=sf_f, back=sf_b))
            y = y_end

        # 2. North edge: y = y1, x goes x0 -> x1
        x = x0
        while x < x1:
            nb = cell_to_rect.get((x, y1), -1)
            x_end = x + 1
            while x_end < x1 and cell_to_rect.get((x_end, y1), -1) == nb:
                x_end += 1
            sv, ev = get_v(x, y1), get_v(x_end, y1)
            wtex = pick_wall_tex(x, y1)
            if nb < 0:
                sf = add_surface(sec_id, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=1, type=0, special=0, front=sf, back=-1))
            elif sec_id < nb:
                sf_f = add_surface(sec_id, wtex)
                sf_b = add_surface(nb, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=4, type=0, special=0, front=sf_f, back=sf_b))
            x = x_end

        # 3. East edge: x = x1, y goes y1 -> y0
        y = y1
        while y > y0:
            nb = cell_to_rect.get((x1, y - 1), -1)
            y_end = y - 1
            while y_end > y0 and cell_to_rect.get((x1, y_end - 1), -1) == nb:
                y_end -= 1
            sv, ev = get_v(x1, y), get_v(x1, y_end)
            wtex = pick_wall_tex(x1, y - 1)
            if nb < 0:
                sf = add_surface(sec_id, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=1, type=0, special=0, front=sf, back=-1))
            elif sec_id < nb:
                sf_f = add_surface(sec_id, wtex)
                sf_b = add_surface(nb, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=4, type=0, special=0, front=sf_f, back=sf_b))
            y = y_end

        # 4. South edge: y = y0, x goes x1 -> x0
        x = x1
        while x > x0:
            nb = cell_to_rect.get((x - 1, y0 - 1), -1)
            x_end = x - 1
            while x_end > x0 and cell_to_rect.get((x_end - 1, y0 - 1), -1) == nb:
                x_end -= 1
            sv, ev = get_v(x, y0), get_v(x_end, y0)
            wtex = pick_wall_tex(x - 1, y0)
            if nb < 0:
                sf = add_surface(sec_id, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=1, type=0, special=0, front=sf, back=-1))
            elif sec_id < nb:
                sf_f = add_surface(sec_id, wtex)
                sf_b = add_surface(nb, wtex)
                walls.append(dict(sv=sv, ev=ev, flags=4, type=0, special=0, front=sf_f, back=sf_b))
            x = x_end

    lvl = c3d2_core.LEGACY.Level()
    lvl.vertices = vertices
    lvl.walls = walls
    lvl.surfaces = surfaces
    lvl.sectors = sectors

    px_c3d = int((spawn_fx - 32.0) * SCALE)
    pz_c3d = int((spawn_fy - 32.0) * SCALE)
    lvl.objects = [dict(x=px_c3d, z=pz_c3d, angle=c3d_ang, type=1, param=0)]

    report = c3d2_core.LEGACY.rebuild_derived(lvl, validate=True)
    out_c3b = os.path.join(LEVELS_DIR, f"level{level_idx}.c3b")
    c3d2_core.dump_c3b(lvl, "/textures/custom/materials.c3m", out_c3b, None)

    # Filter monsters, items, and props to visited sectors (or place inside sector centers)
    sector_centers = []
    for (x0, y0, x1, y1, sky, fstep) in rects:
        cx = (x0 + x1) * 0.5
        cy = (y0 + y1) * 0.5
        if math.hypot(cx - spawn_fx, cy - spawn_fy) > 2.2:
            sector_centers.append((cx, cy))

    # Place signature guard monster directly in front of player spawn along open corridor
    lead_mtype = 0 if level_idx == 1 else (1 if level_idx == 2 else (9 if level_idx == 3 else 10))
    lead_mx = int(((spawn_fx + fwd_dx * 1.4) - 32.0) * SCALE)
    lead_mz = int(((spawn_fy + fwd_dy * 1.4) - 32.0) * SCALE)
    valid_monsters = [(lead_mtype, lead_mx, lead_mz, 0)]
    for mtype, fx, fy, mang in raw_monsters:
        if (int(fx), int(fy)) in visited and math.hypot(fx - spawn_fx, fy - spawn_fy) > 1.8:
            valid_monsters.append((mtype, int((fx - 32.0) * SCALE), int((fy - 32.0) * SCALE), mang))
    # Ensure a rich roster of monsters across sectors
    while len(valid_monsters) < 16 and sector_centers:
        cx, cy = sector_centers[(len(valid_monsters) * 3) % len(sector_centers)]
        mtype = (level_idx * 2 + len(valid_monsters)) % 11
        valid_monsters.append((mtype, int((cx - 32.0) * SCALE), int((cy - 32.0) * SCALE), len(valid_monsters) & 7))
    valid_monsters = valid_monsters[:24]

    valid_items = []
    for itype, fx, fy in raw_items:
        if (int(fx), int(fy)) in visited:
            valid_items.append((itype, int((fx - 32.0) * SCALE), int((fy - 32.0) * SCALE)))
    # Ensure weapons, medkits, armor, ammo, and keys are available in the level
    essential_items = [22, 23, 24, 25, 26, 17, 19, 21, 27, 28, 18, 20]
    for idx_e, itype in enumerate(essential_items):
        if idx_e < len(sector_centers):
            cx, cy = sector_centers[(idx_e * 2 + 1) % len(sector_centers)]
            valid_items.append((itype, int((cx - 32.0) * SCALE) + 16, int((cy - 32.0) * SCALE) - 16))
    valid_items = valid_items[:28]

    valid_props = []
    for ptype, fx, fy, pang in raw_props:
        if (int(fx), int(fy)) in visited and math.hypot(fx - spawn_fx, fy - spawn_fy) > 1.5:
            valid_props.append((ptype, int((fx - 32.0) * SCALE), int((fy - 32.0) * SCALE), pang))
    for idx_p in range(min(8, len(sector_centers))):
        cx, cy = sector_centers[(idx_p * 4 + 2) % len(sector_centers)]
        ptype = 30 if (idx_p % 2 == 0) else (31 if level_idx >= 2 else 32)
        valid_props.append((ptype, int((cx - 32.0) * SCALE) - 20, int((cy - 32.0) * SCALE) + 20, idx_p & 7))
    valid_props = valid_props[:16]

    # Pick farthest sector center as level exit portal coordinates
    exit_cx, exit_cy = spawn_fx, spawn_fy - 4.0
    best_d = -1.0
    for cx, cy in sector_centers:
        d = math.hypot(cx - spawn_fx, cy - spawn_fy)
        if d > best_d:
            best_d = d
            exit_cx, exit_cy = cx, cy
    exit_x_c3d = int((exit_cx - 32.0) * SCALE)
    exit_z_c3d = int((exit_cy - 32.0) * SCALE)

    ent_buf = bytearray()
    ent_buf.extend(struct.pack(">hhhhh", px_c3d, pz_c3d, c3d_ang, exit_x_c3d, exit_z_c3d))
    ent_buf.extend(struct.pack(">H", len(valid_monsters)))
    for mtype, mx, mz, mang in valid_monsters:
        ent_buf.extend(struct.pack(">BhhB", mtype, mx, mz, mang))
    ent_buf.extend(struct.pack(">H", len(valid_items)))
    for itype, ix, iz in valid_items:
        ent_buf.extend(struct.pack(">Bhh", itype, ix, iz))
    ent_buf.extend(struct.pack(">H", len(valid_props)))
    for ptype, px, pz, pang in valid_props:
        ent_buf.extend(struct.pack(">BhhB", ptype, px, pz, pang))

    ent_path = os.path.join(LEVELS_DIR, f"entities{level_idx}.bin")
    with open(ent_path, "wb") as f:
        f.write(ent_buf)

    info = c3d2_core.read_c3b(out_c3b)
    print(f"[OK] Level {level_idx} (MAP.{map_num:02d}): sectors={info['sectors']}, walls={info['walls']}, "
          f"nodes={info['nodes']}, bsp_err={len(report.fail_samples)}, "
          f"monsters={len(valid_monsters)}, items={len(valid_items)}, props={len(valid_props)}, "
          f"c3b={info['total_size']}B")


def build_all_levels():
    compile_chasm_map_to_c3b(1, 1, max_cells=220, wall_base_slot=1,  flat_base_slot=1)
    compile_chasm_map_to_c3b(2, 5, max_cells=220, wall_base_slot=9,  flat_base_slot=5)
    compile_chasm_map_to_c3b(3, 6, max_cells=220, wall_base_slot=11, flat_base_slot=5)
    compile_chasm_map_to_c3b(4, 99, max_cells=160, wall_base_slot=17, flat_base_slot=9)


# ---------------------------------------------------------------------------
# 6. Extract & Downsample WAV Sound Effects from Sound Effects ZIP
# ---------------------------------------------------------------------------
def build_sound_effects():
    sfx_map = {
        "sfx_shotgun.wav": ("SHOOT1.WAV", 0.55),
        "sfx_supershot.wav": ("RIFLE.WAV", 0.65),
        "sfx_chaingun.wav": ("RIFLECUT.WAV", 0.35),
        "sfx_disk.wav": ("DISKTHRW.WAV", 0.50),
        "sfx_crossbow.wav": ("EXP_ARB2.WAV", 0.55),
        "sfx_explode.wav": ("EXPLODE5.WAV", 0.75),
        "sfx_door.wav": ("DOOR_MOV.WAV", 0.60),
        "sfx_switch.wav": ("SWITCH1.WAV", 0.45),
        "sfx_pickup.wav": ("ITEMUP.WAV", 0.45),
        "sfx_pain.wav": ("JUMP_NO.WAV", 0.50),
        "sfx_monpain.wav": ("MEETEXP.WAV", 0.50),
        "sfx_monalert.wav": ("WM_UP.WAV", 0.55),
        "sfx_mondie.wav": ("DEATH.WAV", 0.65),
        "sfx_gib.wav": ("MEETFALL.WAV", 0.50),
    }
    target_rate = 8000
    with zipfile.ZipFile(SFX_ZIP, "r") as z:
        z_lookup = {}
        for zn in z.namelist():
            if zn.upper().endswith(".WAV"):
                z_lookup[os.path.basename(zn).strip().upper()] = zn
        for out_name, (src_name, max_dur) in sfx_map.items():
            zpath = z_lookup[src_name.upper()]
            raw_wav = z.read(zpath)
            with wave.open(io.BytesIO(raw_wav), "rb") as w_in:
                in_rate = w_in.getframerate()
                n_frames = w_in.getnframes()
                sampwidth = w_in.getsampwidth()
                n_channels = w_in.getnchannels()
                frames = w_in.readframes(n_frames)

            # Convert input samples to unsigned 8-bit mono
            samples_u8 = bytearray()
            step_bytes = sampwidth * n_channels
            for i in range(0, len(frames), step_bytes):
                if sampwidth == 1:
                    samples_u8.append(frames[i])
                else:
                    s16, = struct.unpack("<h", frames[i:i + 2])
                    samples_u8.append(max(0, min(255, (s16 >> 8) + 128)))

            out_len = min(int(max_dur * target_rate), max(1, int(len(samples_u8) * target_rate / in_rate)))
            out_pcm = bytearray(out_len)
            for i in range(out_len):
                src_i = min(len(samples_u8) - 1, int(i * in_rate / target_rate))
                out_pcm[i] = samples_u8[src_i]

            out_path = os.path.join(RES_DIR, out_name)
            with wave.open(out_path, "wb") as w_out:
                w_out.setnchannels(1)
                w_out.setsampwidth(1)
                w_out.setframerate(target_rate)
                w_out.writeframes(bytes(out_pcm))
            print(f"[OK] Wrote {out_name:18s} ({os.path.getsize(out_path):5d} bytes from {src_name})")


build_sound_effects()


# ---------------------------------------------------------------------------
# 7. Generate Multi-Channel General MIDI Soundtrack (.mid)
# ---------------------------------------------------------------------------
def encode_vlq(val):
    out = [val & 0x7F]
    val >>= 7
    while val > 0:
        out.append((val & 0x7F) | 0x80)
        val >>= 7
    out.reverse()
    return bytes(out)


def create_midi_track(tempo_bpm, prog_lead, prog_bass, prog_pad, melody_notes, bass_notes, pad_chords):
    events = []  # (tick, bytes)
    us_per_beat = int(60000000 // tempo_bpm)
    events.append((0, b"\xFF\x51\x03" + struct.pack(">I", us_per_beat)[1:]))
    # Program changes: Ch 0 = Lead, Ch 1 = Bass, Ch 2 = Pad
    events.append((0, bytes([0xC0, prog_lead])))
    events.append((0, bytes([0xC1, prog_bass])))
    events.append((0, bytes([0xC2, prog_pad])))
    # Set channel volumes
    events.append((0, bytes([0xB0, 7, 108])))
    events.append((0, bytes([0xB1, 7, 114])))
    events.append((0, bytes([0xB2, 7, 88])))
    events.append((0, bytes([0xB9, 7, 105])))

    tpb = 120  # ticks per quarter note

    # Schedule melody (Channel 0)
    t = 0
    for note, dur, vel in melody_notes:
        if note > 0:
            events.append((t, bytes([0x90, note, vel])))
            events.append((t + max(10, dur - 8), bytes([0x80, note, 0])))
        t += dur

    total_ticks = t

    # Schedule bassline (Channel 1) & industrial drums (Channel 9)
    t = 0
    bi = 0
    step_idx = 0
    while t < total_ticks:
        bnote, bdur = bass_notes[bi % len(bass_notes)]
        bi += 1
        events.append((t, bytes([0x91, bnote, 104])))
        events.append((t + max(10, bdur - 10), bytes([0x81, bnote, 0])))
        # Industrial percussion on Channel 9
        if step_idx % 4 == 0:
            events.append((t, bytes([0x99, 36, 115])))  # Bass Drum
            events.append((t + 40, bytes([0x89, 36, 0])))
        elif step_idx % 4 == 2:
            events.append((t, bytes([0x99, 38, 105])))  # Electric Snare
            events.append((t + 40, bytes([0x89, 38, 0])))
        events.append((t, bytes([0x99, 42, 78])))       # Closed Hi-Hat
        events.append((t + 25, bytes([0x89, 42, 0])))
        t += bdur
        step_idx += 1

    # Schedule dark atmospheric pad chords (Channel 2)
    t = 0
    ci = 0
    while t < total_ticks:
        chord, cdur = pad_chords[ci % len(pad_chords)]
        ci += 1
        for cn in chord:
            events.append((t, bytes([0x92, cn, 76])))
            events.append((min(total_ticks, t + cdur - 12), bytes([0x82, cn, 0])))
        t += cdur

    events.sort(key=lambda e: (e[0], e[1][0] & 0xF0))
    trk = bytearray()
    last_tick = 0
    for tick, data in events:
        dt = max(0, tick - last_tick)
        last_tick = tick
        trk.extend(encode_vlq(dt))
        trk.extend(data)
    trk.extend(encode_vlq(0))
    trk.extend(b"\xFF\x2F\x00")

    midi = bytearray()
    midi.extend(b"MThd")
    midi.extend(struct.pack(">IHHH", 6, 0, 1, tpb))
    midi.extend(b"MTrk")
    midi.extend(struct.pack(">I", len(trk)))
    midi.extend(trk)
    return bytes(midi)


def build_midi_soundtrack():
    # 1. Main Menu Theme: Dark Chasm Rift Industrial Overture (D minor)
    menu_mel = [
        (62, 120, 100), (65, 120, 96), (69, 240, 105), (67, 120, 95), (65, 120, 92),
        (64, 240, 98), (61, 240, 94), (62, 360, 106), (0, 120, 0),
        (62, 120, 102), (65, 120, 98), (70, 240, 108), (69, 120, 100), (65, 120, 95),
        (64, 180, 98), (65, 60, 92), (61, 240, 96), (62, 480, 108),
    ] * 2
    menu_bass = [(38, 60), (38, 60), (50, 60), (38, 60), (41, 60), (41, 60), (40, 60), (37, 60)]
    menu_pads = [((50, 53, 57), 480), ((46, 50, 55), 480), ((45, 49, 52), 480), ((50, 53, 57), 480)]

    # 2. Level 1 (Power Station): Driving Military Industrial Synth-Metal (E Phrygian)
    lvl1_mel = [
        (64, 60, 105), (64, 60, 95), (67, 120, 108), (65, 120, 104), (64, 120, 102),
        (71, 120, 110), (70, 120, 106), (67, 120, 100), (65, 120, 98),
        (64, 60, 105), (65, 60, 102), (67, 120, 108), (72, 120, 112), (71, 120, 108),
        (67, 120, 102), (65, 120, 98), (64, 240, 106),
    ] * 3
    lvl1_bass = [(40, 60), (40, 60), (41, 60), (40, 60), (43, 60), (40, 60), (41, 60), (39, 60)]
    lvl1_pads = [((52, 55, 59), 480), ((53, 57, 60), 480), ((55, 59, 62), 480), ((51, 54, 59), 480)]

    # 3. Level 2 (Around Pyramids): Exotic Egyptian Phrygian Dominant Theme (A Phrygian Dominant: A Bb C# D E F G)
    lvl2_mel = [
        (69, 120, 104), (70, 120, 100), (73, 180, 110), (74, 60, 104), (73, 120, 102), (70, 120, 96),
        (69, 240, 106), (67, 120, 96), (70, 120, 100),
        (73, 120, 108), (74, 120, 110), (76, 180, 114), (77, 60, 108), (76, 120, 104), (73, 120, 100),
        (70, 120, 98), (69, 360, 108),
    ] * 3
    lvl2_bass = [(45, 60), (45, 60), (46, 60), (45, 60), (49, 60), (50, 60), (46, 60), (43, 60)]
    lvl2_pads = [((57, 61, 64), 480), ((58, 62, 65), 480), ((55, 58, 62), 480), ((57, 61, 64), 480)]

    # 4. Level 3 (Pyramid of Shmuher): Dark Catacomb Ritual Theme (C Harmonic Minor)
    lvl3_mel = [
        (60, 120, 102), (63, 120, 100), (67, 120, 106), (66, 120, 104), (67, 120, 108), (68, 120, 104),
        (67, 240, 102), (63, 120, 96), (62, 120, 96),
        (60, 120, 104), (68, 120, 108), (67, 120, 104), (63, 120, 100), (62, 120, 98), (59, 120, 96),
        (60, 480, 108),
    ] * 3
    lvl3_bass = [(36, 60), (36, 60), (39, 60), (43, 60), (44, 60), (43, 60), (38, 60), (35, 60)]
    lvl3_pads = [((48, 51, 55), 480), ((44, 48, 51), 480), ((43, 47, 50), 480), ((48, 51, 55), 480)]

    # 5. Level 4 (Time Chasm Sanctum): Intense Gothic-Alien Boss Finale (D Harmonic Minor)
    lvl4_mel = [
        (74, 60, 112), (70, 60, 104), (69, 60, 106), (65, 60, 100),
        (73, 60, 110), (69, 60, 102), (67, 60, 104), (64, 60, 98),
        (62, 120, 110), (65, 120, 108), (69, 120, 112), (74, 120, 116),
        (73, 120, 112), (70, 120, 108), (69, 240, 114),
    ] * 3
    lvl4_bass = [(38, 60), (50, 60), (38, 60), (46, 60), (37, 60), (49, 60), (41, 60), (45, 60)]
    lvl4_pads = [((50, 53, 57), 480), ((46, 50, 53), 480), ((45, 49, 52), 480), ((50, 53, 57), 480)]

    tracks = [
        ("mus_menu.mid", 112, 81, 38, 95, menu_mel, menu_bass, menu_pads),
        ("mus_lvl1.mid", 136, 30, 38, 89, lvl1_mel, lvl1_bass, lvl1_pads),
        ("mus_lvl2.mid", 124, 104, 39, 92, lvl2_mel, lvl2_bass, lvl2_pads),
        ("mus_lvl3.mid", 118, 80, 38, 95, lvl3_mel, lvl3_bass, lvl3_pads),
        ("mus_lvl4.mid", 144, 30, 39, 91, lvl4_mel, lvl4_bass, lvl4_pads),
    ]
    for fname, bpm, p0, p1, p2, mel, bas, pad in tracks:
        mbytes = create_midi_track(bpm, p0, p1, p2, mel, bas, pad)
        out_path = os.path.join(RES_DIR, fname)
        with open(out_path, "wb") as f:
            f.write(mbytes)
        print(f"[OK] Wrote {fname:14s} ({len(mbytes):5d} bytes, {bpm} BPM)")


build_midi_soundtrack()


# ---------------------------------------------------------------------------
# 4b. UI pack (main-menu logo, tiled menu background, HUD bar), restored from the first build
# ---------------------------------------------------------------------------
def build_ui_pack():
    """
    /ui.bin: three images, each as u16 width, u16 height, then width*height palette indices.
    Index 255 is transparent. Order: LOADING.CEL logo (128x76), M_TILE1.CEL background tile (64x64),
    STATUS2.CEL HUD strip (160x32).
    """
    ui_buf = bytearray()
    for uname, tw, th in [("LOADING.CEL", 128, 76), ("M_TILE1.CEL", 64, 64), ("STATUS2.CEL", 160, 32)]:
        cdata = csm.read(uname)
        _, sw, sh = struct.unpack("<3H", cdata[:6])
        spix = cdata[800:800 + sw * sh]
        ui_buf.extend(struct.pack(">HH", tw, th))
        for y in range(th):
            sy = (y * sh) // th
            for x in range(tw):
                sx = (x * sw) // tw
                ui_buf.append(spix[sy * sw + sx])
    with open(os.path.join(RES_DIR, "ui.bin"), "wb") as f:
        f.write(ui_buf)
    print(f"[OK] Wrote ui.bin ({len(ui_buf)} bytes)")



if __name__ == "__main__":
    build_c3d2_materials()
    build_models_pack()
    build_ui_pack()
    build_all_levels()
    print("[SUCCESS] All Chasm C3D2/C3B & 3D polygonal assets built into /home/user/Chasm/res/")
