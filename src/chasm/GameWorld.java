package chasm;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Chasm: The Rift GameWorld built on CovertOps3D-RE's C3D2/C3B BSP-Portal Engine
 * and PolyRenderer3D (Z-buffer-free software 3D polygon renderer with .CAR
 * multi-part dismemberment).
 */
public final class GameWorld {

    // ==================== Portal World Fields (CovertOps3D-RE) ====================
    public static int MIN_WALL_HEIGHT = 16;
    public static int MIN_CEILING_CLEARANCE = 50;
    public static int PLAYER_HEIGHT_OFFSET = 40;

    public Point2D[] vertices;
    private Point2D[] transformedVertices;
    public WallDefinition[] wallDefinitions;
    public SectorData[] sectors;
    public WallSurface[] wallSurfaces;
    public Transform3D worldOrigin;
    public BSPNode[] bspNodes;
    public Sector[] bspSectors;
    public WallSegment[] wallSegments;
    private int rootBSPNodeIndex = -1;

    private Texture[] animatedWallTargets;
    private Texture[][] animatedWallFrames;
    private Sprite[] animatedFlatTargets;
    private Sprite[][] animatedFlatFrames;
    private int textureAnimationTimer;
    private int textureAnimationFrame;

    private final Point2D collisionTestPoint = new Point2D(0, 0);

    // ==================== Chasm Gameplay Constants ====================
    public static final int NUM_LEVELS = 4;
    public static final String[] LEVEL_NAMES = {
        "M1: MILITARY BASE",
        "M2: SEWER CHASM",
        "M3: ANCIENT TEMPLE",
        "M4: RIFT SANCTUM"
    };

    public static final String[][] LEVEL_BRIEFINGS = {
        {
            "EPISODE 1: MILITARY BASE",
            "Time-channels have opened beneath",
            "the outpost. Mutated soldiers and",
            "biomechanical horrors patrol the",
            "corridors. Sever their weapon arms",
            "to disarm them before they fire!"
        },
        {
            "EPISODE 2: SEWER CHASM",
            "Beyond the base drainage lies the",
            "flooded chasm. Phantoms and acid",
            "fiends lurk behind every portal.",
            "Use the Sawblade Launcher to",
            "dismember enemies at range."
        },
        {
            "EPISODE 3: ANCIENT TEMPLE",
            "The rift leads into an ancient",
            "Egyptian temple complex guarded",
            "by Undead Knights, Mummies, and",
            "Scorpions. Aim for their weapon",
            "limbs to neutralize their attacks."
        },
        {
            "EPISODE 4: RIFT SANCTUM",
            "At the heart of the distortion",
            "stands the Rift Sanctum. Defeat",
            "the elite guardians and seal the",
            "temporal anomaly forever!"
        }
    };

    public static final String[] WEAPON_NAMES = {
        "1:RIFT RIFLE",
        "2:DOUBLE SHOTGUN",
        "3:CHAINGUN",
        "4:CROSSBOW",
        "5:SAW LAUNCHER",
        "6:MEGAZAPPER"
    };

    public static final int[] WEAPON_MODEL_IDS = { 11, 12, 13, 14, 15, 16 };
    public static final int[] WEAPON_COOLDOWN  = { 5, 11, 3, 9, 8, 14 };
    public static final int[] WEAPON_DAMAGE    = { 24, 52, 18, 42, 48, 85 };
    public static final int[] WEAPON_AMMO_COST = { 1, 2, 1, 1, 1, 2 };

    // Monster AI states
    public static final int MSTATE_INACTIVE = 0;
    public static final int MSTATE_IDLE     = 1;
    public static final int MSTATE_CHASE    = 2;
    public static final int MSTATE_ATTACK   = 3;
    public static final int MSTATE_PAIN     = 4;
    public static final int MSTATE_DEAD     = 5;

    // Compatibility aliases for HUD / minimap
    public static final int STATE_IDLE   = MSTATE_IDLE;
    public static final int STATE_CHASE  = MSTATE_CHASE;
    public static final int STATE_ATTACK = MSTATE_ATTACK;
    public static final int STATE_PAIN   = MSTATE_PAIN;
    public static final int STATE_DEAD   = MSTATE_DEAD;

    public static final String[] MONSTER_NAMES = {
        "GRUNT", "SKELETON", "GOBLIN", "SCORPION", "ZOMBIE", "RAT",
        "PHANTOM", "ORC", "MUMMY", "OGRE", "DEMON"
    };

    public static final int[] MONSTER_MAX_HP = {
        70, 85, 65, 95, 90, 45, 80, 100, 120, 145, 180
    };

    // ==================== Player & Renderer State ====================
    public final PhysicsBody playerBody = new PhysicsBody(0, 0, 0, 0);
    public final PolyRenderer3D polyRenderer;

    public int currentLevel = 0;
    public int levelTick = 0;

    // Jump state (16.16 world units): '0' key launches, gravity brings the eye back down.
    public int jumpOffset = 0;
    public int jumpVel = 0;
    public int health = 100;
    public int armor = 25;
    public int curWeapon = 0;
    public final boolean[] weaponOwned = new boolean[6];
    public final int[] ammo = new int[6];

    public int weaponCooldown = 0;
    public int weaponAnimTick = 0;
    public int muzzleFlashTimer = 0;
    public int damageFlashTimer = 0;
    public int walkBobPhase = 0;

    public String hudMessage = "CHASM: THE RIFT";
    public int hudMessageTimer = 45;
    public boolean levelCompleted = false;
    public boolean gameOver = false;

    // ==================== 3D Monsters (with .CAR Dismemberment) ====================
    public static final int MAX_MONSTERS = 32;
    public int monCount = 0;
    public final int[] monType      = new int[MAX_MONSTERS];
    public final int[] monX         = new int[MAX_MONSTERS];
    public final int[] monZ         = new int[MAX_MONSTERS];
    public final int[] monAngle     = new int[MAX_MONSTERS];
    public final int[] monHP        = new int[MAX_MONSTERS];
    public final int[] monMaxHP     = new int[MAX_MONSTERS];
    public final int[] monState     = new int[MAX_MONSTERS];
    public final int[] monStateTick = new int[MAX_MONSTERS];
    public final int[] monFrameA    = new int[MAX_MONSTERS];
    public final int[] monFrameB    = new int[MAX_MONSTERS];
    public final int[] monInterp    = new int[MAX_MONSTERS];
    public final int[] monPartMask  = new int[MAX_MONSTERS];
    public final int[] monPainTimer = new int[MAX_MONSTERS];
    public final Sector[] monSector = new Sector[MAX_MONSTERS];

    // ==================== Detached Severed Body Parts (Submodels 0..2) ====================
    public static final int MAX_LIMBS = 32;
    public final boolean[] limbActive      = new boolean[MAX_LIMBS];
    public final int[] limbMonsterType     = new int[MAX_LIMBS];
    public final int[] limbSubmodelIdx     = new int[MAX_LIMBS];
    public final int[] limbX               = new int[MAX_LIMBS];
    public final int[] limbY               = new int[MAX_LIMBS];
    public final int[] limbZ               = new int[MAX_LIMBS];
    public final int[] limbVX              = new int[MAX_LIMBS];
    public final int[] limbVY              = new int[MAX_LIMBS];
    public final int[] limbVZ              = new int[MAX_LIMBS];
    public final int[] limbAngle           = new int[MAX_LIMBS];
    public final int[] limbFloorY          = new int[MAX_LIMBS];
    public final int[] limbTimer           = new int[MAX_LIMBS];
    public final Sector[] limbSector       = new Sector[MAX_LIMBS];

    // ==================== 3D Items (Pickups) ====================
    public static final int MAX_ITEMS = 40;
    public int itemCount = 0;
    public final boolean[] itemActive  = new boolean[MAX_ITEMS];
    public final int[] itemType        = new int[MAX_ITEMS];
    public final int[] itemModelId     = new int[MAX_ITEMS];
    public final int[] itemX           = new int[MAX_ITEMS];
    public final int[] itemZ           = new int[MAX_ITEMS];
    public final Sector[] itemSector   = new Sector[MAX_ITEMS];

    // ==================== 3D Props (Barrels, Pillars, Crates) ====================
    public static final int MAX_PROPS = 24;
    public int propCount = 0;
    public final boolean[] propActive  = new boolean[MAX_PROPS];
    public final int[] propModelId     = new int[MAX_PROPS];
    public final int[] propX           = new int[MAX_PROPS];
    public final int[] propZ           = new int[MAX_PROPS];
    public final int[] propAngle       = new int[MAX_PROPS];
    public final Sector[] propSector   = new Sector[MAX_PROPS];

    // ==================== 3D Projectiles & Gibs ====================
    public static final int MAX_PROJ = 24;
    /** Projectile model ids (models.bin): 33 = gib particle, 34 = saw-disk, 35 = energy bolt. */
    public static final int PROJ_GIB  = 33;
    public static final int PROJ_DISK = 34;
    public static final int PROJ_BOLT = 35;
    public final boolean[] projActive    = new boolean[MAX_PROJ];
    public final boolean[] projFromPlayer= new boolean[MAX_PROJ];
    public final int[] projModelId       = new int[MAX_PROJ];
    public final int[] projX             = new int[MAX_PROJ];
    public final int[] projY             = new int[MAX_PROJ];
    public final int[] projZ             = new int[MAX_PROJ];
    public final int[] projVX            = new int[MAX_PROJ];
    public final int[] projVY            = new int[MAX_PROJ];
    public final int[] projVZ            = new int[MAX_PROJ];
    public final int[] projAngle         = new int[MAX_PROJ];
    public final int[] projSpin          = new int[MAX_PROJ];
    public final int[] projDamage        = new int[MAX_PROJ];
    public final int[] projLife          = new int[MAX_PROJ];
    public final Sector[] projSector     = new Sector[MAX_PROJ];

    public int monstersKilled = 0;
    public int limbsSevered = 0;
    private int rngState = 0x5A3C9E17;

    public GameWorld() {
        PortalRenderer.initialize();
        Model3D[] models = Model3D.loadAllModels("/models.bin");
        this.polyRenderer = new PolyRenderer3D(models);
        resetInventory();
    }

    public int nextRand() {
        rngState ^= (rngState << 13);
        rngState ^= (rngState >>> 17);
        rngState ^= (rngState << 5);
        return rngState & 0x7FFFFFFF;
    }

    public void resetInventory() {
        health = 100;
        armor = 25;
        curWeapon = 0;
        for (int i = 0; i < 6; i++) {
            weaponOwned[i] = (i <= 1); // Start with Rift Rifle + Double Shotgun
            ammo[i] = 0;
        }
        ammo[0] = 60;
        ammo[1] = 24;
        ammo[2] = 50;
        ammo[3] = 12;
        ammo[4] = 12;
        ammo[5] = 10;
        limbsSevered = 0;
        monstersKilled = 0;
    }

    // ==================== CovertOps3D-RE Portal Engine Methods ====================

    public final BSPNode getRootBSPNode() {
        int root = rootBSPNodeIndex >= 0 ? rootBSPNodeIndex : this.bspNodes.length - 1;
        return this.bspNodes[root];
    }

    public final void setRootBSPNodeIndex(int rootNodeIndex) {
        if (rootNodeIndex < 0 || rootNodeIndex >= this.bspNodes.length) {
            throw new IllegalArgumentException("Invalid BSP root");
        }
        rootBSPNodeIndex = rootNodeIndex;
    }

    public final void setVertices(Point2D[] newVertices) {
        this.vertices = newVertices;
        this.transformedVertices = new Point2D[newVertices.length];
        for (int i = 0; i < this.transformedVertices.length; i++) {
            this.transformedVertices[i] = new Point2D(0, 0);
        }
    }

    public final Point2D[] transformVertices(int originX, int originY, int angle) {
        long sinAngle = (long) MathUtils.fastSin(angle);
        long cosAngle = (long) MathUtils.fastCos(angle);

        for (int i = 0; i < this.vertices.length; i++) {
            int relativeX = this.vertices[i].x - originX;
            int relativeY = this.vertices[i].y - originY;

            this.transformedVertices[i].x = (int) ((cosAngle * (long) relativeX - sinAngle * (long) relativeY) >> 16);
            this.transformedVertices[i].y = (int) ((sinAngle * (long) relativeX + cosAngle * (long) relativeY) >> 16);
        }

        return this.transformedVertices;
    }

    public final Sector getSectorAtPoint(int x, int z) {
        return this.getRootBSPNode().findSectorNodeAtPoint(x, z);
    }

    public final SectorData getSectorDataAtPoint(int x, int z) {
        return this.getRootBSPNode().findSectorAtPoint(x, z);
    }

    public final void initializeWorld() {
        for (int i = 0; i < this.bspNodes.length; i++) {
            this.bspNodes[i].initializeBSPNode(this);
        }
        for (int i = 0; i < this.wallDefinitions.length; i++) {
            this.wallDefinitions[i].initializeWall(this);
        }
        for (int i = 0; i < this.wallSurfaces.length; i++) {
            this.wallSurfaces[i].resolveSectorLink(this);
        }
        for (int i = 0; i < this.wallSegments.length; i++) {
            this.wallSegments[i].initializeWallSegment(this);
        }
        for (int i = 0; i < this.bspSectors.length; i++) {
            this.bspSectors[i].initializeWalls(this);
        }
        this.getRootBSPNode().calculateVisibleSectors();
    }

    public final void installTextureAnimations(Texture[] wallTargets, Texture[][] wallFrames,
                                               Sprite[] flatTargets, Sprite[][] flatFrames) {
        this.animatedWallTargets = wallTargets;
        this.animatedWallFrames = wallFrames;
        this.animatedFlatTargets = flatTargets;
        this.animatedFlatFrames = flatFrames;
        this.textureAnimationTimer = 0;
        this.textureAnimationFrame = 0;
    }

    public final void advanceTextureAnimations() {
        if ((animatedWallTargets == null || animatedWallTargets.length == 0)
                && (animatedFlatTargets == null || animatedFlatTargets.length == 0)) {
            return;
        }
        if (++textureAnimationTimer < 6) {
            return;
        }
        textureAnimationTimer = 0;
        ++textureAnimationFrame;

        if (animatedWallTargets != null) {
            for (int index = 0; index < animatedWallTargets.length; ++index) {
                Texture[] frames = animatedWallFrames[index];
                Texture frame = frames[textureAnimationFrame % frames.length];
                Texture target = animatedWallTargets[index];
                target.pixelData = frame.pixelData;
                target.colorPalettes = frame.colorPalettes;
            }
        }
        if (animatedFlatTargets != null) {
            for (int index = 0; index < animatedFlatTargets.length; ++index) {
                Sprite[] frames = animatedFlatFrames[index];
                Sprite frame = frames[textureAnimationFrame % frames.length];
                Sprite target = animatedFlatTargets[index];
                target.pixelData = frame.pixelData;
                target.colorPalettes = frame.colorPalettes;
                target.flatColors = frame.flatColors;
            }
        }
    }

    // ==================== Level & Entity Sidecar Loading ====================

    public void loadLevel(int levelIndex) {
        if (levelIndex < 0) levelIndex = 0;
        if (levelIndex >= NUM_LEVELS) levelIndex = 0;
        this.currentLevel = levelIndex;
        this.levelTick = 0;
        this.levelCompleted = false;
        this.gameOver = false;
        this.muzzleFlashTimer = 0;
        this.damageFlashTimer = 0;
        this.weaponCooldown = 0;
        this.weaponAnimTick = 0;

        String c3bPath = "/levels/level" + (levelIndex + 1) + ".c3b";
        CustomLevelLoader.load(c3bPath, false, this);

        if (this.worldOrigin != null) {
            this.playerBody.copyFrom(this.worldOrigin);
        }

        // Clear dynamic arrays
        this.monCount = 0;
        this.itemCount = 0;
        this.propCount = 0;
        for (int i = 0; i < MAX_LIMBS; i++) limbActive[i] = false;
        for (int i = 0; i < MAX_PROJ; i++) projActive[i] = false;

        // Load Chasm 3D entities sidecar
        String entPath = "/levels/entities" + (levelIndex + 1) + ".bin";
        loadEntitiesSidecar(entPath);

        setMessage(LEVEL_NAMES[levelIndex], 45);
    }

    private void loadEntitiesSidecar(String entPath) {
        try {
            InputStream is = GameWorld.class.getResourceAsStream(entPath);
            if (is == null) return;
            DataInputStream dis = new DataInputStream(is);

            // 10-byte spawn + exit header: >hhhhh
            int spawnX = dis.readShort();
            int spawnZ = dis.readShort();
            int spawnAng4096 = dis.readShort() & 4095;
            int exitX = dis.readShort();
            int exitZ = dis.readShort();

            this.playerBody.x = spawnX << 16;
            this.playerBody.z = spawnZ << 16;
            this.playerBody.rotation = MathUtils.fromAngle4096(spawnAng4096);

            // Monsters: >H count, then >BhhB (mtype, mx, mz, mang)
            int mCount = dis.readUnsignedShort();
            this.monCount = mCount < MAX_MONSTERS ? mCount : MAX_MONSTERS;
            for (int i = 0; i < mCount; i++) {
                int mType = dis.readUnsignedByte();
                int xUnits = dis.readShort();
                int zUnits = dis.readShort();
                int mang = dis.readUnsignedByte();
                if (i >= MAX_MONSTERS) continue;

                if (mType < 0 || mType > 10) mType = i % 11;
                monType[i] = mType;
                monX[i] = xUnits << 16;
                monZ[i] = zUnits << 16;
                monAngle[i] = MathUtils.fastAtan2(monX[i] - playerBody.x, monZ[i] - playerBody.z);
                monMaxHP[i] = MONSTER_MAX_HP[mType];
                monHP[i] = monMaxHP[i];
                monState[i] = (i == 0) ? MSTATE_CHASE : MSTATE_IDLE;
                monStateTick[i] = i * 3;
                monFrameA[i] = 0;
                monFrameB[i] = 1;
                monInterp[i] = 0;
                monPartMask[i] = Model3D.MASK_INTACT_DEFAULT;
                monPainTimer[i] = 0;
                monSector[i] = getSectorAtPoint(monX[i], monZ[i]);
            }

            // Items: >H count, then >Bhh (modelId 17..28, ix, iz)
            int iCount = dis.readUnsignedShort();
            this.itemCount = iCount < MAX_ITEMS ? iCount : MAX_ITEMS;
            for (int i = 0; i < iCount; i++) {
                int modelId = dis.readUnsignedByte();
                int xUnits = dis.readShort();
                int zUnits = dis.readShort();
                if (i >= MAX_ITEMS) continue;

                if (modelId < 17 || modelId > 29) modelId = 17 + (i % 12);
                itemActive[i] = true;
                itemType[i] = modelId - 17;
                itemModelId[i] = modelId;
                itemX[i] = xUnits << 16;
                itemZ[i] = zUnits << 16;
                itemSector[i] = getSectorAtPoint(itemX[i], itemZ[i]);
            }

            // Props: >H count, then >BhhB (modelId 30..32, px, pz, pang)
            int pCount = dis.readUnsignedShort();
            this.propCount = pCount < MAX_PROPS ? pCount : MAX_PROPS;
            for (int i = 0; i < pCount; i++) {
                int modelId = dis.readUnsignedByte();
                int xUnits = dis.readShort();
                int zUnits = dis.readShort();
                int pang = dis.readUnsignedByte();
                if (i >= MAX_PROPS) continue;

                if (modelId < 30 || modelId > 32) modelId = 30 + (i % 3);
                propActive[i] = true;
                propModelId[i] = modelId;
                propX[i] = xUnits << 16;
                propZ[i] = zUnits << 16;
                propAngle[i] = MathUtils.fromAngle4096((pang & 7) << 9);
                propSector[i] = getSectorAtPoint(propX[i], propZ[i]);
            }

            dis.close();
        } catch (Exception e) {
            // Ignore malformed sidecar
        }
    }

    public void setMessage(String msg, int ticks) {
        this.hudMessage = msg;
        this.hudMessageTimer = ticks;
    }

    // ==================== Rendering Entry Point ====================

    public void render3DView(int[] targetScreenBuffer) {
        SectorData curSecData = getSectorDataAtPoint(playerBody.x, playerBody.z);
        int floorH = curSecData != null ? curSecData.floorHeight : 0;
        int bobY = MathUtils.fastSin(walkBobPhase) >> 1;
        int cameraHeightFP = ((floorH + PLAYER_HEIGHT_OFFSET) << 16) + bobY + jumpOffset;
        playerBody.y = cameraHeightFP;

        // 1. Render BSP-Portal world + back-to-front sector 3D entities via PolyRenderer3D
        PortalRenderer.renderWorld(playerBody.x, -cameraHeightFP, playerBody.z, playerBody.rotation);

        // 2. Render first-person 3D weapon model (.3O + .ANI) without Z-buffer
        Model3D weaponModel = polyRenderer.getModel(WEAPON_MODEL_IDS[curWeapon]);
        if (weaponModel != null) {
            int fA = 0, fB = 0, interp = 0;
            if (weaponAnimTick > 0 && weaponModel.fCount > 1) {
                int totalFrames = weaponModel.fCount - 1;
                int maxTick = WEAPON_COOLDOWN[curWeapon];
                int progress = ((maxTick - weaponAnimTick) << 8) / (maxTick > 0 ? maxTick : 1);
                int scaled = progress * totalFrames;
                fA = 1 + (scaled >> 8);
                fB = fA + 1;
                if (fA >= weaponModel.fCount) fA = weaponModel.fCount - 1;
                if (fB >= weaponModel.fCount) fB = 0;
                interp = scaled & 0xFF;
            }
            int wBobX = (MathUtils.fastCos(walkBobPhase) * 6) >> 16;
            int wBobY = Math.abs((MathUtils.fastSin(walkBobPhase) * 5) >> 16);
            polyRenderer.renderViewWeapon(
                weaponModel, fA, fB, interp,
                wBobX, wBobY,
                muzzleFlashTimer > 0,
                PortalRenderer.screenBuffer
            );
        }

        // 3. Apply damage flash tint if player was hit
        if (damageFlashTimer > 0) {
            int[] buf = PortalRenderer.screenBuffer;
            int len = PortalRenderer.SCREEN_BUFFER_SIZE;
            for (int i = 0; i < len; i++) {
                int c = buf[i];
                int r = ((c >> 16) & 0xFF) + 90;
                if (r > 255) r = 255;
                buf[i] = (c & 0xFF00FFFF) | (r << 16);
            }
        }

        if (targetScreenBuffer != null && targetScreenBuffer != PortalRenderer.screenBuffer) {
            System.arraycopy(PortalRenderer.screenBuffer, 0, targetScreenBuffer, 0, PortalRenderer.SCREEN_BUFFER_SIZE);
        }
    }

    // ==================== Player Movement, Combat & Dismemberment ====================

    public void movePlayer(int forwardFP, int strafeFP, int turnDelta) {
        if (gameOver) return;

        playerBody.rotation = MathUtils.wrapAngle(playerBody.rotation + turnDelta);

        if (forwardFP == 0 && strafeFP == 0) return;

        int sin = MathUtils.fastSin(playerBody.rotation);
        int cos = MathUtils.fastCos(playerBody.rotation);

        // In PortalRenderer: forward is (+sin, +cos), right is (+cos, -sin)
        int dx = (int) (((long) sin * forwardFP + (long) cos * strafeFP) >> 16);
        int dz = (int) (((long) cos * forwardFP - (long) sin * strafeFP) >> 16);

        int proposedX = playerBody.x + dx;
        int proposedZ = playerBody.z + dz;

        SectorData curSector = getSectorDataAtPoint(playerBody.x, playerBody.z);
        collisionTestPoint.x = proposedX;
        collisionTestPoint.y = proposedZ;

        if (wallDefinitions != null) {
            for (int i = 0; i < wallDefinitions.length; i++) {
                WallDefinition wall = wallDefinitions[i];
                if (wall.isCollidable() || CollisionSystem.isWallPassableForSector(curSector, wall)) {
                    CollisionSystem.resolveWallCollision(collisionTestPoint, wall, vertices);
                }
            }
        }

        // Monster cylinder collision so player cannot walk inside live monsters
        int minDist = 20 << 16;
        for (int m = 0; m < monCount; m++) {
            if (monState[m] == MSTATE_INACTIVE || monState[m] == MSTATE_DEAD) continue;
            int mx = collisionTestPoint.x - monX[m];
            int mz = collisionTestPoint.y - monZ[m];
            if (Math.abs(mx) < minDist && Math.abs(mz) < minDist) {
                if (Math.abs(mx) > Math.abs(mz)) {
                    collisionTestPoint.x = monX[m] + (mx >= 0 ? minDist : -minDist);
                } else {
                    collisionTestPoint.y = monZ[m] + (mz >= 0 ? minDist : -minDist);
                }
            }
        }

        playerBody.x = collisionTestPoint.x;
        playerBody.z = collisionTestPoint.y;
        walkBobPhase = MathUtils.wrapAngle(walkBobPhase + 26000);

        checkItemPickups();
    }

    private void checkItemPickups() {
        int pickupRadius = 26 << 16;
        for (int i = 0; i < itemCount; i++) {
            if (!itemActive[i]) continue;
            if (Math.abs(playerBody.x - itemX[i]) < pickupRadius
                    && Math.abs(playerBody.z - itemZ[i]) < pickupRadius) {
                itemActive[i] = false;
                int t = itemType[i] % 8;
                if (t == 0) {
                    health = Math.min(100, health + 25);
                    setMessage("MEDKIT +25 HP", 25);
                } else if (t == 1) {
                    armor = Math.min(100, armor + 25);
                    setMessage("ARMOR +25", 25);
                } else {
                    int w = t - 2;
                    if (w >= 0 && w < 6) {
                        weaponOwned[w] = true;
                        ammo[w] = Math.min(200, ammo[w] + 15);
                        if (curWeapon < w) curWeapon = w;
                        setMessage("GOT " + WEAPON_NAMES[w], 30);
                    }
                }
                SoundManager.playSfx(SoundManager.SFX_PICKUP);
            }
        }
    }

    public void switchWeapon(int delta) {
        for (int k = 1; k <= 6; k++) {
            int next = (curWeapon + delta * k + 60) % 6;
            if (weaponOwned[next]) {
                curWeapon = next;
                setMessage(WEAPON_NAMES[curWeapon], 20);
                break;
            }
        }
    }

    public boolean fireWeapon() {
        if (gameOver || weaponCooldown > 0) return false;
        int cost = WEAPON_AMMO_COST[curWeapon];
        if (ammo[curWeapon] < cost) {
            // Auto-fallback to Rift Rifle with emergency cell recharge
            if (curWeapon != 0) {
                curWeapon = 0;
            }
            if (ammo[0] < 1) ammo[0] = 15;
            cost = 1;
        }
        ammo[curWeapon] -= cost;
        weaponCooldown = WEAPON_COOLDOWN[curWeapon];
        weaponAnimTick = weaponCooldown;
        muzzleFlashTimer = 2;

        int sfx = (curWeapon == 1) ? SoundManager.SFX_SHOTGUN
                : (curWeapon == 2) ? SoundManager.SFX_CHAINGUN
                : (curWeapon == 3) ? SoundManager.SFX_CROSSBOW
                : (curWeapon == 4) ? SoundManager.SFX_SAW
                : (curWeapon == 5) ? SoundManager.SFX_ROCKET
                : SoundManager.SFX_RIFLE;
        SoundManager.playSfx(sfx);

        if (curWeapon >= 3) {
            // Projectile weapons (Crossbow / Megazapper bolt, Sawblade disc). Damage is applied
            // only when the projectile reaches a monster, so there is no double hit.
            spawnProjectile(
                true,
                (curWeapon == 4) ? PROJ_DISK : PROJ_BOLT,
                playerBody.x,
                playerBody.y - (8 << 16),
                playerBody.z,
                playerBody.rotation,
                WEAPON_DAMAGE[curWeapon]
            );
            return true;
        }

        // Hitscan weapons (Rift Rifle, Double Shotgun, Volcano Chaingun)
        performHitscanAttack(WEAPON_DAMAGE[curWeapon]);
        return true;
    }

    private void performHitscanAttack(int damage) {
        int sin = MathUtils.fastSin(playerBody.rotation);
        int cos = MathUtils.fastCos(playerBody.rotation);

        int bestMon = -1;
        int bestDepth = Integer.MAX_VALUE;

        for (int m = 0; m < monCount; m++) {
            if (monState[m] == MSTATE_INACTIVE || monState[m] == MSTATE_DEAD) continue;
            int rx = monX[m] - playerBody.x;
            int rz = monZ[m] - playerBody.z;

            int viewX = (int) (((long) cos * rx - (long) sin * rz) >> 16);
            int viewDepth = (int) (((long) sin * rx + (long) cos * rz) >> 16);
            if (viewDepth <= (8 << 16) || viewDepth > (900 << 16)) continue;

            // Hit cylinder width scales forgivingly for keypad aiming
            int hitHalfWidth = (22 << 16) + (viewDepth >> 3);
            if (Math.abs(viewX) <= hitHalfWidth && viewDepth < bestDepth) {
                if (hasLineOfSight(playerBody.x, playerBody.z, monX[m], monZ[m])) {
                    bestDepth = viewDepth;
                    bestMon = m;
                }
            }
        }

        if (bestMon >= 0) {
            damageMonster(bestMon, damage);
        }
    }

    /**
     * Applies damage to monster m and triggers Chasm's signature .CAR multi-stage
     * dismemberment:
     *   - Stage 1: Right Arm + Weapon severed (MASK_RIGHT_HAND -> MASK_RIGHT_HAND_SEPARATED + submodels[0])
     *   - Stage 2: Left Arm severed (MASK_LEFT_HAND -> MASK_LEFT_HAND_SEPARATED + submodels[1])
     *   - Stage 3: Head severed / Decapitation (MASK_HEAD -> MASK_HEAD_SEPARATED + submodels[2])
     */
    public void damageMonster(int m, int damage) {
        if (m < 0 || m >= monCount) return;
        if (monState[m] == MSTATE_INACTIVE || monState[m] == MSTATE_DEAD) return;

        monHP[m] -= damage;
        monPainTimer[m] = 4;

        int maxHP = monMaxHP[m];
        int hp = monHP[m];
        int mask = monPartMask[m];

        // Check Stage 1 Dismemberment: Sever Right Arm + Weapon at <= 72% HP
        if (hp <= (maxHP * 72) / 100 && (mask & Model3D.MASK_RIGHT_HAND) != 0) {
            dismemberMonsterPart(m, Model3D.SUBMODEL_RIGHT_HAND);
        }

        // Check Stage 2 Dismemberment: Sever Left Arm at <= 38% HP
        if (hp <= (maxHP * 38) / 100 && (mask & Model3D.MASK_LEFT_HAND) != 0) {
            dismemberMonsterPart(m, Model3D.SUBMODEL_LEFT_HAND);
        }

        if (hp <= 0) {
            monHP[m] = 0;
            // Sever Head on overkill or Sawblade/Shotgun/Megazapper finisher
            if ((monPartMask[m] & Model3D.MASK_HEAD) != 0
                    && (damage >= 35 || curWeapon == 1 || curWeapon == 4 || curWeapon == 5)) {
                dismemberMonsterPart(m, Model3D.SUBMODEL_HEAD);
            }
            monState[m] = MSTATE_DEAD;
            monStateTick[m] = 0;
            monFrameA[m] = 8;
            monFrameB[m] = 9;
            monInterp[m] = 0;
            monstersKilled++;
            SoundManager.playSfx(SoundManager.SFX_MON_DIE);

            // Check if all monsters in level are defeated
            checkAllMonstersCleared();
        } else {
            monState[m] = MSTATE_PAIN;
            monStateTick[m] = 0;
            monFrameA[m] = 7;
            monFrameB[m] = 7;
            monInterp[m] = 0;
            SoundManager.playSfx(SoundManager.SFX_MON_PAIN);
        }
    }

    /**
     * Severs a specific body part from monster m:
     *   submodelIdx == 0: Right Arm + Weapon
     *   submodelIdx == 1: Left Arm
     *   submodelIdx == 2: Head
     * Updates the monster's polygon bitmask to hide the limb and expose the
     * bloody stump polygon group, and spawns the detached 3D submodel mesh!
     */
    public void dismemberMonsterPart(int m, int submodelIdx) {
        int mask = monPartMask[m];
        String partLabel;
        if (submodelIdx == Model3D.SUBMODEL_RIGHT_HAND) {
            if ((mask & Model3D.MASK_RIGHT_HAND) == 0) return;
            mask = (mask & ~Model3D.MASK_RIGHT_HAND) | Model3D.MASK_RIGHT_HAND_SEPARATED;
            monFrameA[m] = 7;
            monFrameB[m] = 7;
            partLabel = "RIGHT ARM SEVERED!";
        } else if (submodelIdx == Model3D.SUBMODEL_LEFT_HAND) {
            if ((mask & Model3D.MASK_LEFT_HAND) == 0) return;
            mask = (mask & ~Model3D.MASK_LEFT_HAND) | Model3D.MASK_LEFT_HAND_SEPARATED;
            monFrameA[m] = 7;
            monFrameB[m] = 7;
            partLabel = "LEFT ARM SEVERED!";
        } else {
            if ((mask & Model3D.MASK_HEAD) == 0) return;
            mask = (mask & ~Model3D.MASK_HEAD) | Model3D.MASK_HEAD_SEPARATED;
            partLabel = "HEAD SEVERED!";
        }

        monPartMask[m] = mask;
        monInterp[m] = 0;
        limbsSevered++;
        setMessage(MONSTER_NAMES[monType[m]] + ": " + partLabel, 32);
        SoundManager.playSfx(SoundManager.SFX_GIB);

        // Spawn physical detached 3D limb submodel slightly toward player and to the limb's side
        SectorData secData = getSectorDataAtPoint(monX[m], monZ[m]);
        int floorY = (secData != null ? secData.floorHeight : 0) << 16;
        int spawnH = floorY + ((submodelIdx == Model3D.SUBMODEL_HEAD ? 34 : 22) << 16);

        int toCamAngle = MathUtils.fastAtan2(playerBody.x - monX[m], playerBody.z - monZ[m]);
        int sideOffset = (submodelIdx == 0) ? -55000 : (submodelIdx == 1) ? 55000 : 0;
        int ejectAngle = MathUtils.wrapAngle(toCamAngle + sideOffset);

        int startX = monX[m] + MathUtils.fastSin(ejectAngle) * 10;
        int startZ = monZ[m] + MathUtils.fastCos(ejectAngle) * 10;
        int vx = MathUtils.fastSin(ejectAngle) * 2;
        int vz = MathUtils.fastCos(ejectAngle) * 2;
        int vy = 3 << 16;

        // Only real detached geometry becomes a flying limb (some .CAR monsters have no separable head/arm).
        Model3D parentModel = polyRenderer.getModel(monType[m]);
        boolean hasLimbGeometry = parentModel != null && parentModel.submodels != null
                && submodelIdx < parentModel.submodels.length
                && parentModel.submodels[submodelIdx] != null
                && parentModel.submodels[submodelIdx].vCount > 0
                && parentModel.submodels[submodelIdx].fCount > 0;
        if (hasLimbGeometry) {
            spawnDetachedLimb(monType[m], submodelIdx, startX, spawnH, startZ, vx, vy, vz, monAngle[m], floorY);
        }
    }

    private void spawnDetachedLimb(
            int monsterType, int subIdx,
            int x, int y, int z,
            int vx, int vy, int vz,
            int angle, int floorY) {
        int slot = -1;
        for (int i = 0; i < MAX_LIMBS; i++) {
            if (!limbActive[i]) { slot = i; break; }
        }
        if (slot < 0) slot = limbsSevered % MAX_LIMBS;

        limbActive[slot] = true;
        limbMonsterType[slot] = monsterType;
        limbSubmodelIdx[slot] = subIdx;
        limbX[slot] = x;
        limbY[slot] = y;
        limbZ[slot] = z;
        limbVX[slot] = vx;
        limbVY[slot] = vy;
        limbVZ[slot] = vz;
        limbAngle[slot] = angle;
        limbFloorY[slot] = floorY;
        limbTimer[slot] = 400; // Stays on floor for a long time
        limbSector[slot] = getSectorAtPoint(x, z);
    }

    private void spawnProjectile(
            boolean fromPlayer, int modelId,
            int x, int y, int z,
            int angle, int damage) {
        int slot = -1;
        for (int i = 0; i < MAX_PROJ; i++) {
            if (!projActive[i]) { slot = i; break; }
        }
        if (slot < 0) slot = levelTick % MAX_PROJ;

        boolean gib = (modelId == PROJ_GIB);
        int speed = (modelId == PROJ_DISK) ? 14 : (gib ? 5 : 18);
        projActive[slot] = true;
        projFromPlayer[slot] = fromPlayer;
        projModelId[slot] = modelId;
        projX[slot] = x;
        projY[slot] = y;
        projZ[slot] = z;
        projVX[slot] = MathUtils.fastSin(angle) * speed;
        projVY[slot] = gib ? (3 << 16) : 0;
        projVZ[slot] = MathUtils.fastCos(angle) * speed;
        projAngle[slot] = angle;
        projSpin[slot] = 0;
        projDamage[slot] = damage;
        projLife[slot] = gib ? 18 : (modelId == PROJ_DISK ? 40 : 35);
        projSector[slot] = getSectorAtPoint(x, z);
    }

    public boolean hasLineOfSight(int x1, int z1, int x2, int z2) {
        if (wallDefinitions == null) return true;
        for (int i = 0; i < wallDefinitions.length; i++) {
            WallDefinition wall = wallDefinitions[i];
            if (!CollisionSystem.isWallSolid(wall)) continue;
            Point2D vStart = vertices[wall.startVertexId & 0xFFFF];
            Point2D vEnd = vertices[wall.endVertexId & 0xFFFF];
            if (CollisionSystem.doLineSegmentsIntersect(x1, z1, x2, z2, vStart.x, vStart.y, vEnd.x, vEnd.y)) {
                return false;
            }
        }
        return true;
    }

    private void checkAllMonstersCleared() {
        for (int i = 0; i < monCount; i++) {
            if (monState[i] != MSTATE_INACTIVE && monState[i] != MSTATE_DEAD) {
                return;
            }
        }
        setMessage("SECTOR CLEAR! PRESS * FOR NEXT LEVEL", 90);
    }

    /** '*' key: use. Clears the level once every enemy is down; doors are not in the portal data yet. */
    public void interactOrNextLevel() {
        int alive = 0;
        for (int i = 0; i < monCount; i++) {
            if (monState[i] != MSTATE_INACTIVE && monState[i] != MSTATE_DEAD) alive++;
        }
        if (alive == 0) {
            levelCompleted = true;
            return;
        }
        setMessage("NOTHING TO USE HERE - CLEAR THE SECTOR", 40);
    }

    /** '0' key: jump. */
    public void triggerJump() {
        if (jumpOffset == 0 && jumpVel == 0) {
            jumpVel = 3 << 16;
        }
    }

    private void updateJump() {
        if (jumpOffset == 0 && jumpVel == 0) return;
        jumpOffset += jumpVel;
        jumpVel -= 1 << 14; // gravity 0.25 units/tick^2
        if (jumpOffset <= 0) {
            jumpOffset = 0;
            jumpVel = 0;
        }
    }

    // ==================== Per-Tick Game Logic Update ====================

    public void updateTick() {
        levelTick++;
        updateJump();
        advanceTextureAnimations();

        if (weaponCooldown > 0) weaponCooldown--;
        if (weaponAnimTick > 0) weaponAnimTick--;
        if (muzzleFlashTimer > 0) muzzleFlashTimer--;
        if (damageFlashTimer > 0) damageFlashTimer--;
        if (hudMessageTimer > 0) hudMessageTimer--;

        updateDetachedLimbs();
        updateProjectiles();
        updateMonsters();
    }

    private void updateDetachedLimbs() {
        for (int i = 0; i < MAX_LIMBS; i++) {
            if (!limbActive[i]) continue;
            if (limbY[i] > limbFloorY[i]) {
                limbX[i] += limbVX[i];
                limbZ[i] += limbVZ[i];
                limbY[i] += limbVY[i];
                limbVY[i] -= (1 << 16) + 16384; // Gravity
                limbAngle[i] = MathUtils.wrapAngle(limbAngle[i] + 18000);
                if (limbY[i] <= limbFloorY[i]) {
                    limbY[i] = limbFloorY[i];
                    limbVX[i] = 0;
                    limbVZ[i] = 0;
                    limbVY[i] = 0;
                }
                limbSector[i] = getSectorAtPoint(limbX[i], limbZ[i]);
            }
            if (--limbTimer[i] <= 0) {
                limbActive[i] = false;
            }
        }
    }

    private void updateProjectiles() {
        int hitRadius = 22 << 16;
        for (int i = 0; i < MAX_PROJ; i++) {
            if (!projActive[i]) continue;
            if (--projLife[i] <= 0) {
                projActive[i] = false;
                continue;
            }

            int nx = projX[i] + projVX[i];
            int nz = projZ[i] + projVZ[i];

            if (projModelId[i] == PROJ_GIB) {
                // Gib particle with gravity
                projY[i] += projVY[i];
                projVY[i] -= (1 << 16);
                projAngle[i] = MathUtils.wrapAngle(projAngle[i] + 24000);
                SectorData sd = getSectorDataAtPoint(nx, nz);
                int fl = (sd != null ? sd.floorHeight : 0) << 16;
                if (projY[i] < fl) projY[i] = fl;
                projX[i] = nx;
                projZ[i] = nz;
                projSector[i] = getSectorAtPoint(nx, nz);
                continue;
            }

            if (!hasLineOfSight(projX[i], projZ[i], nx, nz)) {
                projActive[i] = false;
                continue;
            }

            projX[i] = nx;
            projZ[i] = nz;
            // Bolts keep their heading; saw-disks spin around their travel direction.
            if (projModelId[i] == PROJ_DISK) {
                projSpin[i] = MathUtils.wrapAngle(projSpin[i] + 32000);
            }
            projSector[i] = getSectorAtPoint(nx, nz);

            if (projFromPlayer[i]) {
                for (int m = 0; m < monCount; m++) {
                    if (monState[m] == MSTATE_INACTIVE || monState[m] == MSTATE_DEAD) continue;
                    if (Math.abs(nx - monX[m]) < hitRadius && Math.abs(nz - monZ[m]) < hitRadius) {
                        damageMonster(m, projDamage[i]);
                        projActive[i] = false;
                        break;
                    }
                }
            }
        }
    }

    private void updateMonsters() {
        for (int m = 0; m < monCount; m++) {
            int st = monState[m];
            if (st == MSTATE_INACTIVE) continue;

            if (monPainTimer[m] > 0) monPainTimer[m]--;
            monStateTick[m]++;

            if (st == MSTATE_DEAD) {
                // Advance death frames 8 -> 9 and hold at 9 (corpse on floor)
                if (monStateTick[m] < 6) {
                    monFrameA[m] = 8;
                    monFrameB[m] = 9;
                    monInterp[m] = (monStateTick[m] * 256) / 6;
                } else {
                    monFrameA[m] = 9;
                    monFrameB[m] = 9;
                    monInterp[m] = 0;
                }
                continue;
            }

            int dx = playerBody.x - monX[m];
            int dz = playerBody.z - monZ[m];
            int distApprox = Math.abs(dx) + Math.abs(dz);

            int angleToPlayer = MathUtils.fastAtan2(-dx, -dz);

            if (st == MSTATE_PAIN) {
                monFrameA[m] = 7;
                monFrameB[m] = 7;
                monInterp[m] = 0;
                if (monStateTick[m] >= 5) {
                    monState[m] = MSTATE_CHASE;
                    monStateTick[m] = 0;
                }
                continue;
            }

            if (st == MSTATE_IDLE) {
                monFrameA[m] = (monStateTick[m] >> 3) & 1;
                monFrameB[m] = (monFrameA[m] + 1) & 1;
                monInterp[m] = (monStateTick[m] & 7) << 5;

                if (distApprox < (380 << 16) && hasLineOfSight(monX[m], monZ[m], playerBody.x, playerBody.z)) {
                    monState[m] = MSTATE_CHASE;
                    monStateTick[m] = 0;
                    monAngle[m] = angleToPlayer;
                }
            } else if (st == MSTATE_CHASE) {
                monAngle[m] = angleToPlayer;
                // Walk frames 2..4 (3 keyframes)
                int walkStep = (monStateTick[m] >> 1) % 3;
                monFrameA[m] = 2 + walkStep;
                monFrameB[m] = 2 + ((walkStep + 1) % 3);
                monInterp[m] = (monStateTick[m] & 1) << 7;

                // Can only attack if monster still has at least one arm!
                boolean hasArm = (monPartMask[m] & (Model3D.MASK_RIGHT_HAND | Model3D.MASK_LEFT_HAND)) != 0;

                if (hasArm && distApprox < (150 << 16) && (monStateTick[m] > 10)
                        && hasLineOfSight(monX[m], monZ[m], playerBody.x, playerBody.z)) {
                    monState[m] = MSTATE_ATTACK;
                    monStateTick[m] = 0;
                } else if (distApprox > (44 << 16)) {
                    // Step toward player
                    int stepX = (dx >= 0 ? 1 : -1) * Math.min(Math.abs(dx), 3 << 16);
                    int stepZ = (dz >= 0 ? 1 : -1) * Math.min(Math.abs(dz), 3 << 16);
                    int nx = monX[m] + stepX;
                    int nz = monZ[m] + stepZ;
                    if (hasLineOfSight(monX[m], monZ[m], nx, nz)) {
                        monX[m] = nx;
                        monZ[m] = nz;
                        monSector[m] = getSectorAtPoint(nx, nz);
                    }
                }
            } else if (st == MSTATE_ATTACK) {
                monAngle[m] = angleToPlayer;
                // Attack frames 5..6
                int atkFrame = 5 + Math.min(1, monStateTick[m] >> 2);
                monFrameA[m] = atkFrame;
                monFrameB[m] = atkFrame;
                monInterp[m] = 0;

                // Show muzzle flash polygon group (128) on frame 7 if right arm is still attached
                if (monStateTick[m] == 3) {
                    if ((monPartMask[m] & Model3D.MASK_RIGHT_HAND) != 0) {
                        monPartMask[m] |= Model3D.MASK_WEAPON_FIRE;
                    }
                    if (distApprox < (240 << 16) && hasLineOfSight(monX[m], monZ[m], playerBody.x, playerBody.z)) {
                        damagePlayer(6 + (monType[m] >> 1));
                    }
                } else {
                    monPartMask[m] &= ~Model3D.MASK_WEAPON_FIRE;
                }

                if (monStateTick[m] >= 7) {
                    monPartMask[m] &= ~Model3D.MASK_WEAPON_FIRE;
                    monState[m] = MSTATE_CHASE;
                    monStateTick[m] = 0;
                }
            }
        }
    }

    public void damagePlayer(int dmg) {
        if (gameOver) return;
        if (armor > 0) {
            int absorb = dmg >> 1;
            if (absorb > armor) absorb = armor;
            armor -= absorb;
            dmg -= absorb;
        }
        health -= dmg;
        damageFlashTimer = 3;
        SoundManager.playSfx(SoundManager.SFX_PLAYER_PAIN);
        if (health <= 0) {
            health = 0;
            gameOver = true;
            setMessage("YOU DIED! PRESS FIRE TO RESTART", 999);
        }
    }
}
