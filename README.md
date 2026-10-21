# Chasm: The Rift — J2ME Edition (Nokia S60 & Sony Ericsson)

A Java ME (`CLDC 1.1` / `MIDP 2.0`) port of the 1997 DOS 3D first-person shooter **Chasm: The Rift** (Action Forms), engineered specifically for **Nokia S60** and **Sony Ericsson** keypad feature phones.

## Key Engine Architecture

1. **C3D2/C3B BSP-Portal World Renderer (`PortalRenderer`)**:
   - Adapted from `CovertOps3D-RE`'s BSP-portal engine (`PortalRenderer`, `CustomLevelLoader`, `CustomMaterialSet`, `BSPNode`, `Sector`, `WallDefinition`, `RenderUtils`).
   - Compiles original Chasm levels from `CSM.BIN` (`MAP.01` → level 1 "Power Station", `MAP.05` → level 2 "Around Pyramids", `MAP.06` → level 3 "Pyramid of Shmuher", `MAP.99` → level 4 Time Chasm Sanctum) into T-junction-free `C3B1` binary BSP-portal packages (`/res/levels/level1.c3b` .. `/res/levels/level4.c3b`) with `0` BSP partition mismatches and 16-color 4-bit `BMP4` paletted wall, floor/ceiling flat, and skybox textures (`/res/textures/custom/materials.c3m`).

2. **Software 3D Polygon Renderer Without a Z-Buffer (`PolyRenderer3D`)**:
   - Zero Z-buffer allocation or per-pixel depth buffer reads/writes.
   - Integrates directly into `PortalRenderer`'s back-to-front visible sector loop, sorting 3D entities per sector by view depth and sorting front-facing triangles per model back-to-front (Painter's Algorithm).
   - Clips every rasterized 3D polygon scanline against the active sector's exact portal column occlusion window (`[Sector.floorClip[x], Sector.ceilingClip[x]]`).
   - Supports four shading modes: Vertex Colors / Vertex Painting, Flat Shading, Flat + Vertex Colors, and affine Texture Mapping from the original model atlases (see *Polygonal 3D render modes* below).

3. **Authentic `.CAR` Multi-Part Dismemberment System (`Model3D` & `GameWorld`)**:
   - Preserves all 8 original `.CAR` body-part polygon bitmask groups (`1=LeftHand`, `2=LeftHandSeparated`, `4=RightHand`, `8=RightHandSeparated`, `16=Head`, `32=HeadSeparated`, `64=Body`, `128=WeaponFire`) and all 3 embedded detached body-part submodels (`submodels[0..2]`: severed Right Arm + Weapon, severed Left Arm, and severed Head).
   - Damaging enemies progressively severs their Right Arm + Weapon (disabling ranged attacks and exposing the bloody right shoulder stump), Left Arm (disabling all attacks and exposing the left shoulder stump), and Head (decapitation), while spawning the corresponding 3D detached limb submodels that fly through the air and come to rest on the sector floor.

4. **General MIDI Music & Chasm WAV Sound Effects (`SoundManager`)**:
   - General MIDI tracks (`/res/mus_menu.mid`, `/res/mus_lvl1..4.mid`) and 8 kHz mono PCM WAV sound effects (`/res/sfx_*.wav`, 14 files) built from the original Chasm sound archive.

## Release Artifacts

- `/releases/ChasmTheRift.jar` — Preverified J2ME MIDlet suite (`CLDC-1.1` / `MIDP-2.0`)
- `/releases/ChasmTheRift.jad` — JAD descriptor

## Building from Source

```bash
./build.sh
```


## Controls (keypad)

| Key | Action |
|-----|--------|
| 2 / 8 | Forward / backward |
| 4 / 6 | Turn left / right |
| 1 / 3 | Strafe left / right |
| 5 | Fire |
| 7 | Use: open / interact; next level once the sector is clear |
| 9 | Level map (automap) toggle |
| * / # | Previous / next weapon |
| 0 | Jump |
| Left soft key | Pause menu |

The main menu's **CONTROLS & HELP** entry shows the same table on the device.

## Polygonal 3D render modes

Monsters, detached limbs, weapons and props use `PolyRenderer3D` (no Z-buffer, painter's order). Cycle the mode from the main menu or pause menu:

| Mode | HUD tag | Description |
|------|---------|-------------|
| VERTEX COLOR | VC | Gouraud-interpolated per-vertex colours (vertex painting) |
| FLAT | FL | One flat colour per polygon with directional shading |
| FLAT+VERTEX | FV | One colour per polygon: the average of its vertex colours |
| TEXTURE | TX | Affine texture mapping from the model's palette atlas (texel 0/255 uses the flat colour) |

A hit flashes the enemy with an additive red glow for a few ticks instead of overwriting its colours.

## Performance notes

- Sound effects are played by a background audio worker with cached, pre-realized players. The frame loop never calls `Manager.createPlayer()` or `realize()`, which removed the shot micro-stalls on S60 / Sony Ericsson phones.
- Projectile weapons (Crossbow, Saw-Disk, Megazapper) damage only on impact (no duplicate hitscan).

## Known limitations

- Doors are not compiled from the MAP data yet, so `7` only advances the level after the sector is cleared.
- Some portal walls still show angle-dependent distortion; the root cause has not been isolated.
- Transparent grate textures are drawn as opaque (no masked mid-texture pass yet).
- The first build's staircase floor steps are only partially restored.

## Blood J2ME prototype

The Blood assets archive is treated as user-provided game data. `tools/blood_assets.py`
can inspect its RFF directory, decode Blood v6/v7 MAP headers plus core sector,
wall, and sprite geometry (including RFF/map XOR layers), extract selected RFF
entries, and convert ART tiles into indexed BMP8 files while preserving palette
indices. Keep generated assets outside Git (for example, under `/tmp/blood-art`):

```bash
python3 -m venv /tmp/blood-venv
/tmp/blood-venv/bin/pip install -r tools/requirements-blood.txt
/tmp/blood-venv/bin/python tools/blood_assets.py inspect Blood.7z
/tmp/blood-venv/bin/python tools/blood_assets.py inspect-map Blood.7z E1M1.MAP
/tmp/blood-venv/bin/python tools/blood_assets.py export-art Blood.7z /tmp/blood-e1m1-art --map E1M1.MAP
/tmp/blood-venv/bin/python tools/blood_assets.py extract-rff Blood.7z /tmp/blood-maps --pattern '*.MAP'
/tmp/blood-venv/bin/python tools/blood_assets.py extract-rff Blood.7z /tmp/blood-sound --container sounds.rff --pattern '*.MID'
/tmp/blood-venv/bin/python tools/blood_to_c3b.py Blood.7z all /tmp/blood-allmaps
```

The ART export includes `tiles.json` with original tile IDs, dimensions,
animations, and source ART file names; `--map` restricts it to tiles referenced
by that map. RFF resources such as the Blood MIDIs can also be extracted with
`extract-rff --container sounds.rff --pattern '*.MID'`. The MAP decoder has been
exercised against all 43 maps in the provided archive. `tools/blood_to_c3b.py`
can compile one map or the full map set, converting sector polygons,
portal walls, floor/ceiling materials, BSP leaves, and spawn point to the J2ME
engine's C3B format. The generated textures are resized and quantized for the
engine's 4-bit feature-phone renderer.

Build the Blood exploration MIDlet with the same LLM-J2ME-SUITE/JDK toolchain
used by `build.sh` for Chasm. The default map is E1M1; `BLOOD_MAP` can select
another map present in `Blood.7z`:

```bash
./build.sh blood
BLOOD_MAP=E2M1.MAP ./build.sh blood
```

This produces `releases/BloodJ2ME.jar` and its JAD descriptor. The Blood canvas
loads the converted geometry and supports keypad movement, turning, and strafing.
This is a playable **map-exploration prototype**, not feature-complete Blood yet:
Blood actors/sprite animation, weapons, sector events, slopes, masked walls,
sounds/music, saves, and campaign progression are still outstanding. The source
port is used as a format/behavior reference; its desktop engine is not copied
into the constrained Java ME runtime.
