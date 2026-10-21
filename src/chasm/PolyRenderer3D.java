package chasm;

/**
 * Software 3D Polygon Renderer WITHOUT a Z-buffer for Chasm: The Rift.
 *
 * Architectural design:
 *  1. World geometry (sectors, walls, floors, ceilings, portals) is rendered
 *     by CovertOps3D-RE's PortalRenderer, which records per-sector portal
 *     clipping windows (floorClipHistory / ceilingClipHistory) and traverses
 *     sectors back-to-front for entity rendering.
 *  2. Within each sector, PolyRenderer3D sorts 3D entities back-to-front by
 *     view-space depth (Painter's Algorithm).
 *  3. For each 3D model (monsters with dismemberable body-part masks, detached
 *     flying/fallen body-part submodels, 3D items, 3D props, rockets/blades,
 *     and first-person 3D weapons), PolyRenderer3D:
 *       - Interpolates keyframe vertices in 16.16 fixed-point
 *       - Transforms vertices to camera view space
 *       - Culls back-facing triangles using 2D/3D orientation
 *       - Sorts visible triangles back-to-front by view-space depth (Painter's
 *         Algorithm — no Z-buffer array is allocated or used anywhere)
 *       - Rasterizes triangles clipped against the current sector's portal
 *         column bounds [Sector.floorClip[x], Sector.ceilingClip[x]]
 *  4. Supports both shading modes required:
 *       - SHADE_VERTEX_PAINT (0): Gouraud-interpolated Vertex Colors / Vertex Painting
 *       - SHADE_FLAT (1): Single-color polygon Flat Shading
 */
public final class PolyRenderer3D {
    public static final int SHADE_VERTEX_PAINT = 0;
    public static final int SHADE_FLAT = 1;
    /** Flat-shaded triangles coloured from their averaged vertex colours. */
    public static final int SHADE_FLAT_VERTEX = 2;
    /** Affine texture-mapped triangles sampled from the model's 64-wide palette atlas. */
    public static final int SHADE_TEXTURE = 3;
    public static final int SHADE_MODE_COUNT = 4;
    public static final String[] SHADE_NAMES = { "VERTEX COLOR", "FLAT", "FLAT+VERTEX", "TEXTURE" };

    // 1 raw vertex unit (1/2048 Chasm cell) in 16.16 fixed-point C3D units:
    // 56 C3D units per cell -> (56 << 16) / 2048 = 1792
    private static final int MODEL_TO_WORLD_FP = 1792;
    private static final int NEAR_CLIP_FP = 6 << 16;

    public int shadingMode = SHADE_VERTEX_PAINT;

    private final Model3D[] models;

    // Reusable per-model vertex transformation buffers (max 256 vertices)
    private final int[] vx = new int[256];
    private final int[] vy = new int[256];
    private final int[] vz = new int[256];
    private final int[] scrX = new int[256];
    private final int[] scrY = new int[256];

    // Reusable triangle sorting buffers (max 384 triangles per model)
    private final int[] visTriIdx = new int[384];
    private final int[] visTriDepth = new int[384];

    // Reusable per-sector entity sorting buffers (max 96 entities per sector)
    private static final int ENT_MONSTER   = 1;
    private static final int ENT_LIMB      = 2;
    private static final int ENT_ITEM      = 3;
    private static final int ENT_PROP      = 4;
    private static final int ENT_PROJ      = 5;

    private final int[] entType = new int[96];
    private final int[] entIdx  = new int[96];
    private final int[] entDepth = new int[96];

    /** RGB lookup for texture atlas palette indices (loaded from /pal.bin). */
    private final int[] texRGB = new int[256];

    public PolyRenderer3D(Model3D[] models) {
        this.models = models;
        loadTexturePalette();
    }

    private void loadTexturePalette() {
        java.io.InputStream is = null;
        try {
            is = PolyRenderer3D.class.getResourceAsStream("/pal.bin");
            byte[] pal = new byte[768];
            int got = 0;
            if (is != null) {
                while (got < 768) {
                    int n = is.read(pal, got, 768 - got);
                    if (n <= 0) break;
                    got += n;
                }
            }
            for (int i = 0; i < 256; i++) {
                if (got >= 768) {
                    texRGB[i] = ((pal[i * 3] & 0xFF) << 16) | ((pal[i * 3 + 1] & 0xFF) << 8) | (pal[i * 3 + 2] & 0xFF);
                } else {
                    texRGB[i] = (i << 16) | (i << 8) | i;
                }
            }
        } catch (Exception e) {
            for (int i = 0; i < 256; i++) texRGB[i] = (i << 16) | (i << 8) | i;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ex) {}
            }
        }
    }

    /** Cycles to the next polygon shading mode (used by the pause menu and main menu). */
    public void cycleShadingMode() {
        shadingMode = (shadingMode + 1) % SHADE_MODE_COUNT;
    }

    public Model3D getModel(int id) {
        if (models == null || id < 0 || id >= models.length) return null;
        return models[id];
    }

    /**
     * Called by PortalRenderer during its back-to-front sector pass.
     * Sector.floorClip[x] and Sector.ceilingClip[x] already hold the exact
     * portal occlusion window for this sector.
     */
    public void renderSectorEntities(
            GameWorld world,
            Sector sector,
            int cameraX,
            int cameraY,
            int cameraZ,
            int sinAngle,
            int cosAngle,
            int[] screenBuffer) {

        int count = 0;

        // 1. Collect Monsters in this sector
        for (int i = 0; i < world.monCount && count < 96; i++) {
            if (world.monState[i] == GameWorld.MSTATE_INACTIVE) continue;
            if (world.monSector[i] != sector) continue;
            int rx = world.monX[i] - cameraX;
            int rz = world.monZ[i] - cameraZ;
            int depth = (int) (((long) sinAngle * rx + (long) cosAngle * rz) >> 16);
            if (depth > NEAR_CLIP_FP) {
                entType[count] = ENT_MONSTER;
                entIdx[count] = i;
                entDepth[count] = depth;
                count++;
            }
        }

        // 2. Collect Detached Body Parts (severed limbs/heads from dismemberment)
        for (int i = 0; i < GameWorld.MAX_LIMBS && count < 96; i++) {
            if (!world.limbActive[i]) continue;
            if (world.limbSector[i] != sector) continue;
            int rx = world.limbX[i] - cameraX;
            int rz = world.limbZ[i] - cameraZ;
            int depth = (int) (((long) sinAngle * rx + (long) cosAngle * rz) >> 16);
            if (depth > NEAR_CLIP_FP) {
                entType[count] = ENT_LIMB;
                entIdx[count] = i;
                entDepth[count] = depth;
                count++;
            }
        }

        // 3. Collect 3D Items (pickups)
        for (int i = 0; i < world.itemCount && count < 96; i++) {
            if (!world.itemActive[i]) continue;
            if (world.itemSector[i] != sector) continue;
            int rx = world.itemX[i] - cameraX;
            int rz = world.itemZ[i] - cameraZ;
            int depth = (int) (((long) sinAngle * rx + (long) cosAngle * rz) >> 16);
            if (depth > NEAR_CLIP_FP) {
                entType[count] = ENT_ITEM;
                entIdx[count] = i;
                entDepth[count] = depth;
                count++;
            }
        }

        // 4. Collect 3D Props (barrels, crates, columns)
        for (int i = 0; i < world.propCount && count < 96; i++) {
            if (!world.propActive[i]) continue;
            if (world.propSector[i] != sector) continue;
            int rx = world.propX[i] - cameraX;
            int rz = world.propZ[i] - cameraZ;
            int depth = (int) (((long) sinAngle * rx + (long) cosAngle * rz) >> 16);
            if (depth > NEAR_CLIP_FP) {
                entType[count] = ENT_PROP;
                entIdx[count] = i;
                entDepth[count] = depth;
                count++;
            }
        }

        // 5. Collect Projectiles & Gibs
        for (int i = 0; i < GameWorld.MAX_PROJ && count < 96; i++) {
            if (!world.projActive[i]) continue;
            if (world.projSector[i] != sector) continue;
            int rx = world.projX[i] - cameraX;
            int rz = world.projZ[i] - cameraZ;
            int depth = (int) (((long) sinAngle * rx + (long) cosAngle * rz) >> 16);
            if (depth > NEAR_CLIP_FP) {
                entType[count] = ENT_PROJ;
                entIdx[count] = i;
                entDepth[count] = depth;
                count++;
            }
        }

        if (count == 0) return;

        // Sort entities in this sector BACK-TO-FRONT (largest depth first)
        for (int i = 1; i < count; i++) {
            int tType = entType[i];
            int tIdx = entIdx[i];
            int tDepth = entDepth[i];
            int j = i - 1;
            while (j >= 0 && entDepth[j] < tDepth) {
                entType[j + 1] = entType[j];
                entIdx[j + 1] = entIdx[j];
                entDepth[j + 1] = entDepth[j];
                j--;
            }
            entType[j + 1] = tType;
            entIdx[j + 1] = tIdx;
            entDepth[j + 1] = tDepth;
        }

        SectorData sectorData = sector.getSectorData();
        int sectorLight = sectorData != null ? sectorData.getLightLevel() : 5;
        // Map sectorLight (0..8) to a brightness factor 140..256
        int shadeFactor = 148 + (sectorLight * 13);
        if (PortalRenderer.gunFireLighting) {
            shadeFactor += 28;
        }
        if (shadeFactor > 256) shadeFactor = 256;

        int floorHFixed = (sectorData != null ? sectorData.floorHeight : 0) << 16;
        // cameraY passed by PortalRenderer is -cameraHeight (negative of world height in 16.16)
        int camWorldH = -cameraY;

        try {
        for (int i = 0; i < count; i++) {
            int type = entType[i];
            int idx = entIdx[i];

            if (type == ENT_MONSTER) {
                int mType = world.monType[idx];
                Model3D model = getModel(mType);
                if (model != null) {
                    // Brief additive blood-red glow while the hit flash is active (never a solid red override)
                    int tint = world.monPainTimer[idx] > 0 ? 0x6A1408 : 0;
                    renderWorldModel(
                        model,
                        world.monX[idx], floorHFixed, world.monZ[idx],
                        world.monAngle[idx],
                        world.monFrameA[idx], world.monFrameB[idx], world.monInterp[idx],
                        world.monPartMask[idx],
                        MODEL_TO_WORLD_FP,
                        cameraX, camWorldH, cameraZ, sinAngle, cosAngle,
                        shadeFactor, tint, true, screenBuffer
                    );
                }
            } else if (type == ENT_LIMB) {
                Model3D parent = getModel(world.limbMonsterType[idx]);
                int subIdx = world.limbSubmodelIdx[idx];
                if (parent != null && parent.submodels != null && subIdx >= 0 && subIdx < parent.submodels.length) {
                    Model3D sub = parent.submodels[subIdx];
                    int limbFrame = (world.limbY[idx] > world.limbFloorY[idx])
                            ? ((world.levelTick >> 1) & 1)
                            : 2;
                    renderWorldModel(
                        sub,
                        world.limbX[idx], world.limbY[idx], world.limbZ[idx],
                        world.limbAngle[idx],
                        limbFrame, limbFrame, 0,
                        0xFF,
                        (MODEL_TO_WORLD_FP * 3) >> 1,
                        cameraX, camWorldH, cameraZ, sinAngle, cosAngle,
                        shadeFactor, 0, true, screenBuffer
                    );
                }
            } else if (type == ENT_ITEM) {
                Model3D model = getModel(world.itemModelId[idx]);
                if (model != null) {
                    int spinAngle = MathUtils.wrapAngle(world.levelTick * 3600 + idx * 50000);
                    int bobH = floorHFixed + (MathUtils.fastSin(MathUtils.wrapAngle(world.levelTick * 8000 + idx * 40000)) * 3);
                    renderWorldModel(
                        model,
                        world.itemX[idx], bobH, world.itemZ[idx],
                        spinAngle,
                        0, 0, 0,
                        0xFF,
                        MODEL_TO_WORLD_FP,
                        cameraX, camWorldH, cameraZ, sinAngle, cosAngle,
                        236, 0, true, screenBuffer
                    );
                }
            } else if (type == ENT_PROP) {
                Model3D model = getModel(world.propModelId[idx]);
                if (model != null) {
                    renderWorldModel(
                        model,
                        world.propX[idx], floorHFixed, world.propZ[idx],
                        world.propAngle[idx],
                        0, 0, 0,
                        0xFF,
                        MODEL_TO_WORLD_FP,
                        cameraX, camWorldH, cameraZ, sinAngle, cosAngle,
                        shadeFactor, 0, true, screenBuffer
                    );
                }
            } else if (type == ENT_PROJ) {
                Model3D model = getModel(world.projModelId[idx]);
                if (model != null) {
                    int pid = world.projModelId[idx];
                    int scale = (pid == GameWorld.PROJ_GIB) ? (MODEL_TO_WORLD_FP * 3) >> 2 : MODEL_TO_WORLD_FP;
                    int pAngle = (pid == GameWorld.PROJ_DISK)
                            ? MathUtils.wrapAngle(world.projAngle[idx] + world.projSpin[idx])
                            : world.projAngle[idx];
                    renderWorldModel(
                        model,
                        world.projX[idx], world.projY[idx], world.projZ[idx],
                        pAngle,
                        0, 0, 0,
                        0xFF,
                        scale,
                        cameraX, camWorldH, cameraZ, sinAngle, cosAngle,
                        248, 0, true, screenBuffer
                    );
                }
            }
        }
        } catch (RuntimeException e) {
            // A malformed entity must never freeze the frame: skip the rest of this sector.
        }
    }

    /**
     * Renders a 3D model in world space using Painter's Algorithm (no Z-buffer),
     * clipped to the active sector's portal columns [Sector.floorClip, Sector.ceilingClip].
     */
    public void renderWorldModel(
            Model3D model,
            int worldX, int worldY, int worldZ,
            int modelAngle,
            int frameA, int frameB, int interp256,
            int partMask,
            int scaleFP,
            int cameraX, int camWorldH, int cameraZ,
            int sinCam, int cosCam,
            int shadeFactor, int tintRGB,
            boolean usePortalClips,
            int[] screenBuffer) {

        int relX = worldX - cameraX;
        int relZ = worldZ - cameraZ;

        int baseVX = (int) (((long) cosCam * relX - (long) sinCam * relZ) >> 16);
        int baseVZ = (int) (((long) sinCam * relX + (long) cosCam * relZ) >> 16);
        if (baseVZ <= NEAR_CLIP_FP) return;
        // Empty submodels (e.g. .CAR monsters without a separable head) have no frames:
        // skipping them avoids a divide-by-zero in the frame index modulo below.
        if (model == null || model.vCount <= 0 || model.fCount <= 0 || model.frameVerts == null) return;

        // Quick horizontal frustum reject with generous radius
        int radiusFP = 48 << 16;
        if (baseVX + radiusFP < -baseVZ || baseVX - radiusFP > baseVZ) return;

        int baseVY = camWorldH - worldY;

        int vc = model.vCount;
        if (vc > 256) vc = 256;

        int fA = frameA % model.fCount;
        int fB = frameB % model.fCount;
        if (fA < 0) fA = 0;
        if (fB < 0) fB = 0;
        short[] vertsA = model.frameVerts[fA];
        short[] vertsB = model.frameVerts[fB];
        int invInterp = 256 - interp256;

        // Relative rotation of model in view space:
        // When model faces camera, its forward (-my) points toward -viewDepth,
        // so +my points along +viewDepth and +mx points along +viewX when relAngle == 0.
        int relAngle = MathUtils.wrapAngle(modelAngle - LevelLoader.gameWorld.playerBody.rotation);
        // Model-to-view rotation must use the same handedness as the camera transform
        // (viewX = c*X - s*Z, viewZ = s*X + c*Z); otherwise static props appear to spin
        // when the camera turns.
        int sinM = -MathUtils.fastSin(relAngle);
        int cosM = MathUtils.fastCos(relAngle);

        int halfW = PortalRenderer.HALF_VIEWPORT_WIDTH;
        int halfH = PortalRenderer.HALF_VIEWPORT_HEIGHT;

        for (int v = 0; v < vc; v++) {
            int i3 = v * 3;
            int mx, my, mz;
            if (interp256 == 0 || fA == fB) {
                mx = vertsA[i3];
                my = vertsA[i3 + 1];
                mz = vertsA[i3 + 2];
            } else {
                mx = (vertsA[i3] * invInterp + vertsB[i3] * interp256) >> 8;
                my = (vertsA[i3 + 1] * invInterp + vertsB[i3 + 1] * interp256) >> 8;
                mz = (vertsA[i3 + 2] * invInterp + vertsB[i3 + 2] * interp256) >> 8;
            }

            // Scale from 1/2048 model units to 16.16 C3D units
            int sxFP = mx * scaleFP;
            int syFP = my * scaleFP;
            int szFP = mz * scaleFP;

            // Right-handed rotation around vertical axis:
            // rx = sx*cosM - sy*sinM, rz = sx*sinM + sy*cosM
            int rx = (int) (((long) sxFP * cosM - (long) syFP * sinM) >> 16);
            int rz = (int) (((long) sxFP * sinM + (long) syFP * cosM) >> 16);

            int pvx = baseVX + rx;
            int pvz = baseVZ + rz;
            int pvy = baseVY - szFP;

            if (pvz < NEAR_CLIP_FP) pvz = NEAR_CLIP_FP;

            vx[v] = pvx;
            vy[v] = pvy;
            vz[v] = pvz;

            // Project using PortalRenderer's exact 90-degree FOV focal length (HALF_VIEWPORT_WIDTH = 120)
            scrX[v] = halfW + (int) (((long) pvx * halfW) / pvz);
            scrY[v] = halfH + (int) (((long) pvy * halfW) / pvz);
        }

        // Collect visible front-facing triangles matching partMask
        int tc = model.tCount;
        int visCount = 0;
        byte[] tV0 = model.triV0;
        byte[] tV1 = model.triV1;
        byte[] tV2 = model.triV2;
        byte[] tGrp = model.triGroup;
        byte[] tFlg = model.triFlags;

        for (int t = 0; t < tc && visCount < 384; t++) {
            int g = tGrp[t] & 0xFF;
            if ((g & partMask) == 0) continue;

            int i0 = tV0[t] & 0xFF;
            int i1 = tV1[t] & 0xFF;
            int i2 = tV2[t] & 0xFF;

            int x0 = scrX[i0], y0 = scrY[i0];
            int x1 = scrX[i1], y1 = scrY[i1];
            int x2 = scrX[i2], y2 = scrY[i2];

            // 2D cross product for right-handed (vx, vy, vz) projection:
            // Front-facing triangles have cross < 0.
            int cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
            boolean twoSided = (tFlg[t] & 1) != 0;
            if (!twoSided) {
                if (cross >= 0) continue;
            } else {
                if (cross == 0) continue;
            }

            // Screen bounding-box cull
            int minX = x0 < x1 ? (x0 < x2 ? x0 : x2) : (x1 < x2 ? x1 : x2);
            int maxX = x0 > x1 ? (x0 > x2 ? x0 : x2) : (x1 > x2 ? x1 : x2);
            if (maxX < 0 || minX >= PortalRenderer.VIEWPORT_WIDTH) continue;

            int minY = y0 < y1 ? (y0 < y2 ? y0 : y2) : (y1 < y2 ? y1 : y2);
            int maxY = y0 > y1 ? (y0 > y2 ? y0 : y2) : (y1 > y2 ? y1 : y2);
            if (maxY < 0 || minY >= PortalRenderer.VIEWPORT_HEIGHT) continue;

            visTriIdx[visCount] = t;
            visTriDepth[visCount] = vz[i0] + vz[i1] + vz[i2];
            visCount++;
        }

        if (visCount == 0) return;

        // Sort visible triangles BACK-TO-FRONT by depth sum (Painter's Algorithm)
        for (int i = 1; i < visCount; i++) {
            int idx = visTriIdx[i];
            int d = visTriDepth[i];
            int j = i - 1;
            while (j >= 0 && visTriDepth[j] < d) {
                visTriIdx[j + 1] = visTriIdx[j];
                visTriDepth[j + 1] = visTriDepth[j];
                j--;
            }
            visTriIdx[j + 1] = idx;
            visTriDepth[j + 1] = d;
        }

        // Distance attenuation (subtle darkening at long distances)
        int distAtten = shadeFactor - (baseVZ >> 19);
        if (distAtten < 88) distAtten = 88;
        if (distAtten > 256) distAtten = 256;

        // Rasterize triangles back-to-front
        for (int k = 0; k < visCount; k++) {
            int t = visTriIdx[k];
            int i0 = tV0[t] & 0xFF;
            int i1 = tV1[t] & 0xFF;
            int i2 = tV2[t] & 0xFF;

            // Directional normal shading
            int ny = model.triNY[t];
            int nz = model.triNZ[t];
            int dirShade = 216 + (nz >> 2) - (ny >> 3);
            if (dirShade < 140) dirShade = 140;
            if (dirShade > 268) dirShade = 268;
            int totalShade = (distAtten * dirShade) >> 8;
            if (totalShade > 256) totalShade = 256;

            drawModelTriangle(model, t, i0, i1, i2, totalShade, tintRGB, usePortalClips, screenBuffer);
        }
    }

    /**
     * Renders the first-person 3D weapon overlay (.3O + .ANI) using Painter's
     * Algorithm (no Z-buffer).
     */
    public void renderViewWeapon(
            Model3D weaponModel,
            int frameA, int frameB, int interp256,
            int bobX, int bobY,
            boolean muzzleFlash,
            int[] screenBuffer) {

        if (weaponModel == null) return;
        int vc = weaponModel.vCount;
        if (vc > 256) vc = 256;

        int fA = frameA % weaponModel.fCount;
        int fB = frameB % weaponModel.fCount;
        short[] vertsA = weaponModel.frameVerts[fA];
        short[] vertsB = weaponModel.frameVerts[fB];
        int invInterp = 256 - interp256;

        // Chasm-Reverse weapon view transform (1/2048 model units):
        // g_weapon_shift = (0.2, 0.7, -0.45) -> (+410, +1434, -922). Model +Y is forward (depth),
        // +X is screen right, model Z is up. Anything with depth < WEAPON_NEAR is behind the lens.
        final int halfW = PortalRenderer.HALF_VIEWPORT_WIDTH;
        final int halfH = PortalRenderer.HALF_VIEWPORT_HEIGHT;
        final int WEAPON_NEAR = 120;
        for (int v = 0; v < vc; v++) {
            int i3 = v * 3;
            int mx, my, mz;
            if (interp256 == 0 || fA == fB) {
                mx = vertsA[i3];
                my = vertsA[i3 + 1];
                mz = vertsA[i3 + 2];
            } else {
                mx = (vertsA[i3] * invInterp + vertsB[i3] * interp256) >> 8;
                my = (vertsA[i3 + 1] * invInterp + vertsB[i3 + 1] * interp256) >> 8;
                mz = (vertsA[i3 + 2] * invInterp + vertsB[i3 + 2] * interp256) >> 8;
            }
            int x = mx + 410;
            int d = my + 1434;          // depth along the view axis
            int z = mz - 922;           // vertical
            vx[v] = x;
            vy[v] = z;
            vz[v] = d;
            if (d > 0) {
                scrX[v] = halfW + bobX + (x * halfW) / d;
                scrY[v] = halfH + bobY - (z * halfW) / d;
            } else {
                scrX[v] = -100000;
                scrY[v] = -100000;
            }
        }

        int tc = weaponModel.tCount;
        int visCount = 0;
        byte[] tV0 = weaponModel.triV0;
        byte[] tV1 = weaponModel.triV1;
        byte[] tV2 = weaponModel.triV2;
        byte[] tGrp = weaponModel.triGroup;
        byte[] tFlg = weaponModel.triFlags;

        for (int t = 0; t < tc && visCount < 384; t++) {
            int g = tGrp[t] & 0xFF;
            if (g == Model3D.MASK_WEAPON_FIRE && !muzzleFlash) continue;

            int i0 = tV0[t] & 0xFF;
            int i1 = tV1[t] & 0xFF;
            int i2 = tV2[t] & 0xFF;

            // Whole-triangle near-plane rejection (rear stock / shoulder geometry).
            if (vz[i0] < WEAPON_NEAR || vz[i1] < WEAPON_NEAR || vz[i2] < WEAPON_NEAR) continue;

            // True 3D back-face test in view space (camera at origin):
            // keep when cross(v1-v0, v2-v0) . (cam - v0) >= 0, as in Chasm-Reverse.
            boolean twoSided = (tFlg[t] & 1) != 0;
            if (!twoSided) {
                long ux = vx[i1] - vx[i0], uy = vz[i1] - vz[i0], uz = vy[i1] - vy[i0];
                long wx = vx[i2] - vx[i0], wy = vz[i2] - vz[i0], wz = vy[i2] - vy[i0];
                long cx = uy * wz - uz * wy;
                long cy = uz * wx - ux * wz;
                long cz = ux * wy - uy * wx;
                long facing = cx * (-vx[i0]) + cy * (-vz[i0]) + cz * (-vy[i0]);
                if (facing < 0) continue;
            }

            visTriIdx[visCount] = t;
            visTriDepth[visCount] = vz[i0] + vz[i1] + vz[i2];
            visCount++;
        }

        // Painter's Algorithm: sort triangles back-to-front by depth sum
        for (int i = 1; i < visCount; i++) {
            int idx = visTriIdx[i];
            int d = visTriDepth[i];
            int j = i - 1;
            while (j >= 0 && visTriDepth[j] < d) {
                visTriIdx[j + 1] = visTriIdx[j];
                visTriDepth[j + 1] = visTriDepth[j];
                j--;
            }
            visTriIdx[j + 1] = idx;
            visTriDepth[j + 1] = d;
        }

        int baseShade = muzzleFlash ? 256 : 228;
        int tint = muzzleFlash ? 0x203008 : 0;

        for (int k = 0; k < visCount; k++) {
            int t = visTriIdx[k];
            int i0 = tV0[t] & 0xFF;
            int i1 = tV1[t] & 0xFF;
            int i2 = tV2[t] & 0xFF;

            int nz = weaponModel.triNZ[t];
            int shade = baseShade + (nz >> 2);
            if (shade < 160) shade = 160;
            if (shade > 256) shade = 256;

            drawModelTriangle(weaponModel, t, i0, i1, i2, shade, tint, false, screenBuffer);
        }
    }

    /**
     * Applies directional/distance shade, then an ADDITIVE flash colour (tintRGB).
     * Additive flashes brighten the polygon instead of overwriting its vertex colours,
     * so a hit reads as a short red glow rather than a solid red enemy.
     */
    /**
     * Rasterizes one projected triangle according to the active shading mode:
     *   SHADE_VERTEX_PAINT  Gouraud-interpolated vertex colours
     *   SHADE_FLAT          one flat colour per polygon
     *   SHADE_FLAT_VERTEX   one colour per polygon = average of its vertex colours
     *   SHADE_TEXTURE       affine texture mapping from the model atlas (flat colour for holes)
     */
    private void drawModelTriangle(Model3D model, int t, int i0, int i1, int i2,
                                   int totalShade, int tintRGB, boolean usePortalClips, int[] screenBuffer) {
        int mode = shadingMode;
        if (mode == SHADE_TEXTURE && model.texData == null) {
            mode = SHADE_FLAT;
        }
        if (mode == SHADE_TEXTURE) {
            int fallback = applyShadeAndTint(model.triFlatRGB[t], totalShade, tintRGB);
            fillTriangleTextured(
                scrX[i0], scrY[i0], model.triU[t * 3] & 0xFF, model.triV[t * 3] & 0xFFFF,
                scrX[i1], scrY[i1], model.triU[t * 3 + 1] & 0xFF, model.triV[t * 3 + 1] & 0xFFFF,
                scrX[i2], scrY[i2], model.triU[t * 3 + 2] & 0xFF, model.triV[t * 3 + 2] & 0xFFFF,
                model.texData, model.texH, texRGB, totalShade, fallback, tintRGB,
                usePortalClips, screenBuffer
            );
        } else if (mode == SHADE_VERTEX_PAINT) {
            int c0 = applyShadeAndTint(model.triVertRGB0[t], totalShade, tintRGB);
            int c1 = applyShadeAndTint(model.triVertRGB1[t], totalShade, tintRGB);
            int c2 = applyShadeAndTint(model.triVertRGB2[t], totalShade, tintRGB);
            fillTriangleVertexColor(
                scrX[i0], scrY[i0], c0,
                scrX[i1], scrY[i1], c1,
                scrX[i2], scrY[i2], c2,
                usePortalClips, screenBuffer
            );
        } else {
            int rgb;
            if (mode == SHADE_FLAT_VERTEX) {
                int a = model.triVertRGB0[t], b = model.triVertRGB1[t], c = model.triVertRGB2[t];
                int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF)) / 3;
                int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF)) / 3;
                int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF)) / 3;
                rgb = applyShadeAndTint((r << 16) | (g << 8) | bl, totalShade, tintRGB);
            } else {
                rgb = applyShadeAndTint(model.triFlatRGB[t], totalShade, tintRGB);
            }
            fillTriangleFlat(
                scrX[i0], scrY[i0],
                scrX[i1], scrY[i1],
                scrX[i2], scrY[i2],
                rgb, usePortalClips, screenBuffer
            );
        }
    }

    /**
     * Affine texture-mapped triangle fill (no Z-buffer, painter's order handled by the caller).
     * Texel index 0 / 255 are treated as holes and show the flat fallback colour.
     */
    // Shade+tint lookup for textured triangles: 256 ARGB entries, one per
    // palette index. Rebuilt only when palette/shade/tint/fallback change, so the
    // per-pixel loop is a single table load instead of three multiplies and clamps.
    private static final int[] SHADE_LUT = new int[256];
    private static int[] shadeLutPal;
    private static int shadeLutShade = -1;
    private static int shadeLutTint;
    private static int shadeLutFallback;

    private static int[] buildShadeLut(int[] palRGB, int shade256, int tintRGB, int fallbackRGB) {
        // Index 0 and 255 are transparent/fallback texels: cheap to rewrite every call.
        int fallback = 0xFF000000 | fallbackRGB;
        SHADE_LUT[0] = fallback;
        SHADE_LUT[255] = fallback;
        if (palRGB == shadeLutPal && shade256 == shadeLutShade && tintRGB == shadeLutTint) {
            return SHADE_LUT;
        }
        shadeLutPal = palRGB;
        shadeLutShade = shade256;
        shadeLutTint = tintRGB;
        int tintR = (tintRGB >> 16) & 0xFF, tintG = (tintRGB >> 8) & 0xFF, tintB = tintRGB & 0xFF;
        int n = palRGB.length;
        for (int i = 1; i < 255; i++) {
            if (i >= n) {
                SHADE_LUT[i] = fallback;
                continue;
            }
            int p = palRGB[i];
            int r = (((p >> 16) & 0xFF) * shade256 >> 8) + tintR;
            int g = (((p >> 8) & 0xFF) * shade256 >> 8) + tintG;
            int b = ((p & 0xFF) * shade256 >> 8) + tintB;
            if (r > 255) r = 255;
            if (g > 255) g = 255;
            if (b > 255) b = 255;
            SHADE_LUT[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return SHADE_LUT;
    }

    /** Triangles shorter than this many rows shade per pixel: rebuilding the LUT costs more. */
    private static final int TEX_LUT_MIN_ROWS = 24;

    /**
     * One horizontal textured run. Two loop bodies so the portal-clip test is
     * not evaluated for every pixel when clipping is off.
     */
    private static void drawTexSpan(int[] buf, int rowOffset, int xs, int xe, int y,
                                    int uCur, int uStep, int vCur, int vStep,
                                    byte[] tex, int maxV, int[] lut,
                                    boolean usePortalClips, short[] floorClip, short[] ceilClip) {
        if (!usePortalClips) {
            for (int x = xs; x <= xe; x++) {
                int vi = vCur >> 16;
                if (vi < 0) vi = 0; else if (vi > maxV) vi = maxV;
                buf[rowOffset + x] = lut[tex[(vi << 6) + ((uCur >> 16) & 63)] & 0xFF];
                uCur += uStep;
                vCur += vStep;
            }
        } else {
            for (int x = xs; x <= xe; x++) {
                if (y >= floorClip[x] && y <= ceilClip[x]) {
                    int vi = vCur >> 16;
                    if (vi < 0) vi = 0; else if (vi > maxV) vi = maxV;
                    buf[rowOffset + x] = lut[tex[(vi << 6) + ((uCur >> 16) & 63)] & 0xFF];
                }
                uCur += uStep;
                vCur += vStep;
            }
        }
    }

    /** Same output as drawTexSpan but shades each texel directly (no LUT build). */
    private static void drawTexSpanShaded(int[] buf, int rowOffset, int xs, int xe, int y,
                                          int uCur, int uStep, int vCur, int vStep,
                                          byte[] tex, int maxV, int[] palRGB, int shade256,
                                          int tintRGB, int fallbackRGB,
                                          boolean usePortalClips, short[] floorClip, short[] ceilClip) {
        int tintR = (tintRGB >> 16) & 0xFF, tintG = (tintRGB >> 8) & 0xFF, tintB = tintRGB & 0xFF;
        int fallback = 0xFF000000 | fallbackRGB;
        for (int x = xs; x <= xe; x++, uCur += uStep, vCur += vStep) {
            if (usePortalClips && (y < floorClip[x] || y > ceilClip[x])) continue;
            int vi = vCur >> 16;
            if (vi < 0) vi = 0; else if (vi > maxV) vi = maxV;
            int idx = tex[(vi << 6) + ((uCur >> 16) & 63)] & 0xFF;
            if (idx == 0 || idx == 255) {
                buf[rowOffset + x] = fallback;
            } else {
                int p = palRGB[idx];
                int r = (((p >> 16) & 0xFF) * shade256 >> 8) + tintR;
                int g = (((p >> 8) & 0xFF) * shade256 >> 8) + tintG;
                int b = ((p & 0xFF) * shade256 >> 8) + tintB;
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (b > 255) b = 255;
                buf[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void fillTriangleTextured(
            int x0, int y0, int u0, int v0,
            int x1, int y1, int u1, int v1,
            int x2, int y2, int u2, int v2,
            byte[] tex, int texH, int[] palRGB, int shade256, int fallbackRGB, int tintRGB,
            boolean usePortalClips, int[] screenBuffer) {

        if (texH <= 0) return;
        // Sort by Y ascending
        if (y0 > y1) {
            int t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t;
            t = u0; u0 = u1; u1 = t; t = v0; v0 = v1; v1 = t;
        }
        if (y1 > y2) {
            int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t;
            t = u1; u1 = u2; u2 = t; t = v1; v1 = v2; v2 = t;
        }
        if (y0 > y1) {
            int t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t;
            t = u0; u0 = u1; u1 = t; t = v0; v0 = v1; v1 = t;
        }

        int totalH = y2 - y0;
        if (totalH <= 0) return;
        int startY = y0 < 0 ? 0 : y0;
        int endY = y2 >= PortalRenderer.VIEWPORT_HEIGHT ? PortalRenderer.VIEWPORT_HEIGHT - 1 : y2;
        if (startY > endY) return;

        boolean useLut = totalH >= TEX_LUT_MIN_ROWS;
        int[] lut = useLut ? buildShadeLut(palRGB, shade256, tintRGB, fallbackRGB) : null;
        short[] floorClip = Sector.floorClip;
        short[] ceilClip = Sector.ceilingClip;
        int width = PortalRenderer.VIEWPORT_WIDTH;
        int maxV = texH - 1;

        // Edge steps in 16.16, computed once per triangle (no per-row divide).
        long lxS = ((long) (x2 - x0) << 16) / totalH;
        long luS = ((long) (u2 - u0) << 16) / totalH;
        long lvS = ((long) (v2 - v0) << 16) / totalH;
        int seg1H = y1 - y0;
        long s1xS = 0, s1uS = 0, s1vS = 0;
        if (seg1H > 0) {
            s1xS = ((long) (x1 - x0) << 16) / seg1H;
            s1uS = ((long) (u1 - u0) << 16) / seg1H;
            s1vS = ((long) (v1 - v0) << 16) / seg1H;
        }
        int seg2H = y2 - y1;
        long s2xS = 0, s2uS = 0, s2vS = 0;
        if (seg2H > 0) {
            s2xS = ((long) (x2 - x1) << 16) / seg2H;
            s2uS = ((long) (u2 - u1) << 16) / seg2H;
            s2vS = ((long) (v2 - v1) << 16) / seg2H;
        }

        for (int y = startY; y <= endY; y++) {
            int dy = y - y0;
            long xa16 = ((long) x0 << 16) + lxS * dy;
            long ua16 = ((long) u0 << 16) + luS * dy;
            long va16 = ((long) v0 << 16) + lvS * dy;
            long xb16, ub16, vb16;
            if (y <= y1) {
                if (seg1H > 0) {
                    xb16 = ((long) x0 << 16) + s1xS * dy;
                    ub16 = ((long) u0 << 16) + s1uS * dy;
                    vb16 = ((long) v0 << 16) + s1vS * dy;
                } else {
                    xb16 = (long) x1 << 16;
                    ub16 = (long) u1 << 16;
                    vb16 = (long) v1 << 16;
                }
            } else {
                int dys = y - y1;
                xb16 = ((long) x1 << 16) + s2xS * dys;
                ub16 = ((long) u1 << 16) + s2uS * dys;
                vb16 = ((long) v1 << 16) + s2vS * dys;
            }

            int xa = (int) (xa16 >> 16);
            int xb = (int) (xb16 >> 16);
            if (xa > xb) {
                int t = xa; xa = xb; xb = t;
                long lt = ua16; ua16 = ub16; ub16 = lt;
                lt = va16; va16 = vb16; vb16 = lt;
            }
            if (xb < 0 || xa >= width) continue;

            int spanW = xb - xa;
            int uStep = spanW > 0 ? (int) ((ub16 - ua16) / spanW) : 0;
            int vStep = spanW > 0 ? (int) ((vb16 - va16) / spanW) : 0;
            int uCur = (int) ua16;
            int vCur = (int) va16;
            int xs = xa;
            if (xs < 0) {
                uCur += uStep * (-xs);
                vCur += vStep * (-xs);
                xs = 0;
            }
            int xe = xb >= width ? width - 1 : xb;
            if (useLut) {
                drawTexSpan(screenBuffer, y * width, xs, xe, y, uCur, uStep, vCur, vStep,
                        tex, maxV, lut, usePortalClips, floorClip, ceilClip);
            } else {
                drawTexSpanShaded(screenBuffer, y * width, xs, xe, y, uCur, uStep, vCur, vStep,
                        tex, maxV, palRGB, shade256, tintRGB, fallbackRGB, usePortalClips, floorClip, ceilClip);
            }
        }
    }

    private static int applyShadeAndTint(int rgb, int shade256, int tintRGB) {
        int r = (((rgb >> 16) & 0xFF) * shade256) >> 8;
        int g = (((rgb >> 8) & 0xFF) * shade256) >> 8;
        int b = ((rgb & 0xFF) * shade256) >> 8;
        if (tintRGB != 0) {
            r += (tintRGB >> 16) & 0xFF;
            g += (tintRGB >> 8) & 0xFF;
            b += tintRGB & 0xFF;
        }
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Flat-shaded single-color triangle scanline rasterizer with per-column
     * portal clipping [Sector.floorClip[x], Sector.ceilingClip[x]].
     */
    private static void fillTriangleFlat(
            int x0, int y0,
            int x1, int y1,
            int x2, int y2,
            int color,
            boolean usePortalClips,
            int[] screenBuffer) {

        // Sort vertices by Y ascending (y0 <= y1 <= y2)
        if (y0 > y1) { int t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }
        if (y1 > y2) { int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
        if (y0 > y1) { int t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }

        int totalH = y2 - y0;
        if (totalH <= 0) return;

        int startY = y0 < 0 ? 0 : y0;
        int endY = y2 >= PortalRenderer.VIEWPORT_HEIGHT ? PortalRenderer.VIEWPORT_HEIGHT - 1 : y2;
        if (startY > endY) return;

        short[] floorClip = Sector.floorClip;
        short[] ceilClip = Sector.ceilingClip;
        int width = PortalRenderer.VIEWPORT_WIDTH;

        // Edge steps in 16.16, computed once per triangle (no per-row divide).
        long lxS = ((long) (x2 - x0) << 16) / totalH;
        int seg1H = y1 - y0;
        long s1S = seg1H > 0 ? ((long) (x1 - x0) << 16) / seg1H : 0;
        int seg2H = y2 - y1;
        long s2S = seg2H > 0 ? ((long) (x2 - x1) << 16) / seg2H : 0;

        for (int y = startY; y <= endY; y++) {
            int dy = y - y0;
            long xa16 = ((long) x0 << 16) + lxS * dy;
            long xb16;
            if (y <= y1) {
                xb16 = seg1H > 0 ? ((long) x0 << 16) + s1S * dy : (long) x1 << 16;
            } else {
                xb16 = ((long) x1 << 16) + s2S * (y - y1);
            }

            int xa = (int) (xa16 >> 16);
            int xb = (int) (xb16 >> 16);
            if (xa > xb) { int t = xa; xa = xb; xb = t; }
            if (xb < 0 || xa >= width) continue;
            if (xa < 0) xa = 0;
            if (xb >= width) xb = width - 1;

            int rowOffset = y * width;
            if (usePortalClips) {
                for (int x = xa; x <= xb; x++) {
                    if (y >= floorClip[x] && y <= ceilClip[x]) {
                        screenBuffer[rowOffset + x] = color;
                    }
                }
            } else {
                for (int x = xa; x <= xb; x++) {
                    screenBuffer[rowOffset + x] = color;
                }
            }
        }
    }

    /**
     * Vertex-Color / Vertex-Painted Gouraud triangle scanline rasterizer with
     * per-column portal clipping [Sector.floorClip[x], Sector.ceilingClip[x]].
     */
    private static void fillTriangleVertexColor(
            int x0, int y0, int c0,
            int x1, int y1, int c1,
            int x2, int y2, int c2,
            boolean usePortalClips,
            int[] screenBuffer) {

        // Sort vertices by Y ascending (y0 <= y1 <= y2)
        if (y0 > y1) {
            int t = x0; x0 = x1; x1 = t;
            t = y0; y0 = y1; y1 = t;
            t = c0; c0 = c1; c1 = t;
        }
        if (y1 > y2) {
            int t = x1; x1 = x2; x2 = t;
            t = y1; y1 = y2; y2 = t;
            t = c1; c1 = c2; c2 = t;
        }
        if (y0 > y1) {
            int t = x0; x0 = x1; x1 = t;
            t = y0; y0 = y1; y1 = t;
            t = c0; c0 = c1; c1 = t;
        }

        int totalH = y2 - y0;
        if (totalH <= 0) return;

        int startY = y0 < 0 ? 0 : y0;
        int endY = y2 >= PortalRenderer.VIEWPORT_HEIGHT ? PortalRenderer.VIEWPORT_HEIGHT - 1 : y2;
        if (startY > endY) return;

        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;

        short[] floorClip = Sector.floorClip;
        short[] ceilClip = Sector.ceilingClip;
        int width = PortalRenderer.VIEWPORT_WIDTH;

        for (int y = startY; y <= endY; y++) {
            boolean secondHalf = (y > y1) || (y1 == y0);
            int segH = secondHalf ? (y2 - y1) : (y1 - y0);
            if (segH <= 0) continue;

            int dyTotal = y - y0;
            int xa = x0 + ((x2 - x0) * dyTotal) / totalH;
            int ra = r0 + ((r2 - r0) * dyTotal) / totalH;
            int ga = g0 + ((g2 - g0) * dyTotal) / totalH;
            int ba = b0 + ((b2 - b0) * dyTotal) / totalH;

            int xb, rb, gb, bb;
            if (secondHalf) {
                int dySeg = y - y1;
                xb = x1 + ((x2 - x1) * dySeg) / segH;
                rb = r1 + ((r2 - r1) * dySeg) / segH;
                gb = g1 + ((g2 - g1) * dySeg) / segH;
                bb = b1 + ((b2 - b1) * dySeg) / segH;
            } else {
                xb = x0 + ((x1 - x0) * dyTotal) / segH;
                rb = r0 + ((r1 - r0) * dyTotal) / segH;
                gb = g0 + ((g1 - g0) * dyTotal) / segH;
                bb = b0 + ((b1 - b0) * dyTotal) / segH;
            }

            if (xa > xb) {
                int t = xa; xa = xb; xb = t;
                t = ra; ra = rb; rb = t;
                t = ga; ga = gb; gb = t;
                t = ba; ba = bb; bb = t;
            }

            if (xb < 0 || xa >= width) continue;

            int spanW = xb - xa;
            if (spanW <= 0) {
                if (xa >= 0 && xa < width) {
                    if (!usePortalClips || (y >= floorClip[xa] && y <= ceilClip[xa])) {
                        screenBuffer[y * width + xa] = 0xFF000000 | (ra << 16) | (ga << 8) | ba;
                    }
                }
                continue;
            }

            int rStep = ((rb - ra) << 8) / spanW;
            int gStep = ((gb - ga) << 8) / spanW;
            int bStep = ((bb - ba) << 8) / spanW;

            int rCur = ra << 8;
            int gCur = ga << 8;
            int bCur = ba << 8;

            if (xa < 0) {
                int skip = -xa;
                rCur += rStep * skip;
                gCur += gStep * skip;
                bCur += bStep * skip;
                xa = 0;
            }
            if (xb >= width) xb = width - 1;

            int rowOffset = y * width;
            if (usePortalClips) {
                for (int x = xa; x <= xb; x++) {
                    if (y >= floorClip[x] && y <= ceilClip[x]) {
                        int r = (rCur >> 8) & 0xFF;
                        int g = (gCur >> 8) & 0xFF;
                        int b = (bCur >> 8) & 0xFF;
                        screenBuffer[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    rCur += rStep;
                    gCur += gStep;
                    bCur += bStep;
                }
            } else {
                for (int x = xa; x <= xb; x++) {
                    int r = (rCur >> 8) & 0xFF;
                    int g = (gCur >> 8) & 0xFF;
                    int b = (bCur >> 8) & 0xFF;
                    screenBuffer[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    rCur += rStep;
                    gCur += gStep;
                    bCur += bStep;
                }
            }
        }
    }
}
