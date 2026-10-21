package chasm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loader for the clean C3B custom-level runtime format.
 *
 * C3B deliberately keeps a simple fixed-record layout plus external BMP
 * materials and optional entity INI sidecars. It is separate from the
 * reverse-engineered legacy loader, but produces the existing GameWorld/BSPNode
 * objects so PortalRenderer remains fast and unchanged.
 */
public final class CustomLevelLoader {

    private static final int VERSION = 1;
    /** Last load failure, exposed for diagnostics on small J2ME screens. */
    public static String lastError;

    // Header flags. C3B v1 reserved this byte; bit 0 moves entity records to
    // a UTF-8 INI sidecar and keeps the compiled binary geometry-only.
    private static final int HEADER_FLAG_EXTERNAL_ENTITIES = 1;
    private static final int HEADER_FLAG_BLOOD_MAP = 2;
    private static final int HEADER_FLAG_BLOOD_STACK_LINKS = 4;

    // Per-sector flags, not C3B header flags.
    private static final int SECTOR_FLAG_CEILING_SKY = 1;
    private static final int SECTOR_FLAG_FLOOR_SKY = 2;

    private CustomLevelLoader() {
    }

    public static boolean load(String levelPath, boolean shouldLoadObjects) {
        return load(levelPath, shouldLoadObjects, null);
    }

    public static boolean load(String levelPath, boolean shouldLoadObjects, GameWorld targetWorld) {
        DataInputStream input = null;
        lastError = null;
        try {
            LevelLoader.prepareCustomMapLoad();
            InputStream stream = CustomLevelLoader.class.getResourceAsStream(levelPath);
            if (stream == null) throw new IOException("Custom level not found: " + levelPath);
            input = new DataInputStream(stream);

            readMagic(input);
            int version = input.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported C3B version: " + version);
            int headerFlags = input.readUnsignedByte();
            if ((headerFlags & ~(HEADER_FLAG_EXTERNAL_ENTITIES | HEADER_FLAG_BLOOD_MAP
                    | HEADER_FLAG_BLOOD_STACK_LINKS)) != 0) {
                throw new IOException("Unsupported C3B header flags: " + headerFlags);
            }
            boolean externalEntities = (headerFlags & HEADER_FLAG_EXTERNAL_ENTITIES) != 0;
            boolean bloodMap = (headerFlags & HEADER_FLAG_BLOOD_MAP) != 0;
            boolean bloodStackLinks = (headerFlags & HEADER_FLAG_BLOOD_STACK_LINKS) != 0;
            if (bloodStackLinks && !bloodMap) {
                throw new IOException("Blood stack links require a Blood map");
            }
            int rootNode = BinaryUtils.readShortLE(input);

            int vertexCount = readCount(input, "vertices");
            int wallCount = readCount(input, "walls");
            int objectCount = readOptionalCount(input);
            if (objectCount > 32767) throw new IOException("C3B exceeds signed-index limits");
            if (externalEntities && objectCount != 0) {
                throw new IOException("External-entity C3B contains inline objects");
            }
            int surfaceCount = readCount(input, "surfaces");
            int sectorCount = readCount(input, "sectors");
            int nodeCount = readCount(input, "nodes");
            int leafCount = readCount(input, "leaves");
            int segmentCount = readCount(input, "segments");
            validateCounts(vertexCount, wallCount, surfaceCount, sectorCount,
                    nodeCount, leafCount, segmentCount, rootNode);

            int materialPathLength = BinaryUtils.readShortLE(input) & 0xFFFF;
            byte[] materialPathBytes = new byte[materialPathLength];
            input.readFully(materialPathBytes, 0, materialPathLength);
            String materialPath = new String(materialPathBytes, "UTF-8");
            if (materialPath.length() == 0) throw new IOException("C3B has no material manifest");

            String entityPath = null;
            if (externalEntities) {
                int entityPathLength = BinaryUtils.readShortLE(input) & 0xFFFF;
                byte[] entityPathBytes = new byte[entityPathLength];
                input.readFully(entityPathBytes, 0, entityPathLength);
                entityPath = new String(entityPathBytes, "UTF-8");
                if (entityPath.length() == 0) throw new IOException("C3B has no entity sidecar");
            }

            CustomMaterialSet materials = CustomMaterialSet.load(resolvePath(levelPath, materialPath));
            materials.installWallTextures();
            materials.installSpriteTextures();
            materials.installSkyTexture();

            GameWorld world = targetWorld != null ? targetWorld : new GameWorld();
            world.distanceOrderSectors = bloodMap;
            world.bloodStartSector = -1;
            world.bloodActiveSector = -1;
            world.upperStackSector = null;
            world.lowerStackSector = null;
            Point2D[] vertices = new Point2D[vertexCount];
            for (int i = 0; i < vertexCount; ++i) {
                vertices[i] = new Point2D(BinaryUtils.readShortLE(input) << 16,
                        BinaryUtils.readShortLE(input) << 16);
            }
            world.setVertices(vertices);

            WallDefinition[] walls = new WallDefinition[wallCount];
            for (int i = 0; i < wallCount; ++i) {
                short startVertex = BinaryUtils.readShortLE(input);
                short endVertex = BinaryUtils.readShortLE(input);
                short frontSurface = BinaryUtils.readShortLE(input);
                short backSurface = BinaryUtils.readShortLE(input);
                byte flags = input.readByte();
                byte type = input.readByte();
                byte special = input.readByte();
                input.readByte(); // reserved
                walls[i] = new WallDefinition(startVertex, endVertex,
                        frontSurface, backSurface, flags, type, special);
            }
            world.wallDefinitions = walls;

            if (externalEntities) {
                throw new IOException("External entities not used");
            } else {
                installInlineObjects(input, objectCount, world, shouldLoadObjects, bloodMap);
            }

            WallSurface[] surfaces = new WallSurface[surfaceCount];
            for (int i = 0; i < surfaceCount; ++i) {
                short packedX = BinaryUtils.readShortLE(input);
                short packedY = BinaryUtils.readShortLE(input);
                byte upper = input.readByte();
                byte lower = input.readByte();
                byte main = input.readByte();
                int surfaceLight = input.readUnsignedByte();
                int sectorId = BinaryUtils.readShortLE(input) & 0xFFFF;
                if (sectorId >= sectorCount) throw new IOException("Surface sector out of range");
                short offsetX = packedX;
                short offsetY = packedY;
                int repeatX = 8;
                int repeatY = 8;
                if (bloodMap) {
                    offsetX = (short)(packedX & 0xff);
                    offsetY = (short)(packedY & 0xff);
                    repeatX = (packedX >>> 8) & 0xff;
                    repeatY = (packedY >>> 8) & 0xff;
                    if (repeatX == 0) repeatX = 8;
                    if (repeatY == 0) repeatY = 8;
                }
                surfaces[i] = new WallSurface(upper, main, lower, sectorId,
                        offsetX, offsetY, repeatX, repeatY, bloodMap ? surfaceLight : -1);
            }
            world.wallSurfaces = surfaces;

            SectorData[] sectors = new SectorData[sectorCount];
            for (int i = 0; i < sectorCount; ++i) {
                short floorHeight = BinaryUtils.readShortLE(input);
                short ceilingHeight = BinaryUtils.readShortLE(input);
                byte floorSlot = input.readByte();
                byte ceilingSlot = input.readByte();
                int lightLevel = input.readUnsignedByte();
                int flags = input.readUnsignedByte();
                short tag = BinaryUtils.readShortLE(input);
                short type = BinaryUtils.readShortLE(input);

                boolean ceilingSky = (flags & SECTOR_FLAG_CEILING_SKY) != 0;
                boolean floorSky = (flags & SECTOR_FLAG_FLOOR_SKY) != 0;

                // SectorData names mirror the legacy on-disk fields, while
                // PortalRenderer consumes floorTexture on the upper spans and
                // ceilingTexture on the lower spans.  Keep C3D/C3B semantic
                // fields natural (floor means floor, ceiling means ceiling)
                // and adapt once here so custom maps do not appear inverted.
                SectorData sector = new SectorData((short)i, floorHeight, ceilingHeight,
                        floorSky ? (byte)51 : floorSlot,
                        ceilingSky ? (byte)51 : ceilingSlot,
                        (short)lightLevel, tag, type);

                if (!floorSky && floorSlot != 0) {
                    sector.ceilingTexture = materials.getFlatTexture(floorSlot & 0xFF);
                    if (sector.ceilingTexture == null) {
                        throw new IOException("Missing floor material: " + (floorSlot & 0xFF));
                    }
                }
                if (!ceilingSky && ceilingSlot != 0) {
                    sector.floorTexture = materials.getFlatTexture(ceilingSlot & 0xFF);
                    if (sector.floorTexture == null) {
                        throw new IOException("Missing ceiling material: " + (ceilingSlot & 0xFF));
                    }
                }
                sectors[i] = sector;
            }
            world.sectors = sectors;
            if (bloodMap && world.bloodStartSector >= 0
                    && world.bloodStartSector < sectorCount && world.worldOrigin != null) {
                SectorData startSector = sectors[world.bloodStartSector];
                world.worldOrigin.y = ((startSector.floorHeight + GameWorld.PLAYER_HEIGHT_OFFSET) << 16);
                world.bloodActiveSector = world.bloodStartSector;
            }
            world.upperStackSector = new short[sectorCount];
            world.lowerStackSector = new short[sectorCount];
            world.initializeBloodStackVisibility(sectorCount);
            for (int i = 0; i < sectorCount; ++i) {
                world.upperStackSector[i] = -1;
                world.lowerStackSector[i] = -1;
            }
            if (bloodStackLinks) {
                for (int i = 0; i < sectorCount; ++i) {
                    short upper = BinaryUtils.readShortLE(input);
                    short lower = BinaryUtils.readShortLE(input);
                    if ((upper < -1 || upper >= sectorCount)
                            || (lower < -1 || lower >= sectorCount)) {
                        throw new IOException("Blood stack sector link out of range");
                    }
                    world.upperStackSector[i] = upper;
                    world.lowerStackSector[i] = lower;
                }
            }

            short[] nodeStartX = new short[nodeCount];
            short[] nodeStartZ = new short[nodeCount];
            short[] nodeDeltaX = new short[nodeCount];
            short[] nodeDeltaZ = new short[nodeCount];
            short[] nodeFront = new short[nodeCount];
            short[] nodeBack = new short[nodeCount];
            for (int i = 0; i < nodeCount; ++i) {
                nodeStartX[i] = BinaryUtils.readShortLE(input);
                nodeStartZ[i] = BinaryUtils.readShortLE(input);
                nodeDeltaX[i] = BinaryUtils.readShortLE(input);
                nodeDeltaZ[i] = BinaryUtils.readShortLE(input);
                nodeFront[i] = BinaryUtils.readShortLE(input);
                nodeBack[i] = BinaryUtils.readShortLE(input);
            }

            short[] leafSector = new short[leafCount];
            short[] leafOffset = new short[leafCount];
            short[] leafCountArray = new short[leafCount];
            for (int i = 0; i < leafCount; ++i) {
                leafSector[i] = BinaryUtils.readShortLE(input);
                leafOffset[i] = BinaryUtils.readShortLE(input);
                leafCountArray[i] = BinaryUtils.readShortLE(input);
                if ((leafSector[i] & 0xFFFF) >= sectorCount
                        || (leafOffset[i] & 0xFFFF) + (leafCountArray[i] & 0xFFFF) > segmentCount) {
                    throw new IOException("C3B leaf out of range");
                }
            }

            WallSegment[] segments = new WallSegment[segmentCount];
            for (int i = 0; i < segmentCount; ++i) {
                short startVertex = BinaryUtils.readShortLE(input);
                short endVertex = BinaryUtils.readShortLE(input);
                short definition = BinaryUtils.readShortLE(input);
                boolean frontFacing = input.readUnsignedByte() == 0;
                short textureOffset = BinaryUtils.readShortLE(input);
                segments[i] = new WallSegment(startVertex, endVertex, definition,
                        frontFacing, textureOffset);
            }
            world.wallSegments = segments;

            Sector[] leaves = new Sector[leafCount];
            for (int i = 0; i < leafCount; ++i) {
                leaves[i] = new Sector(leafSector[i], leafCountArray[i], leafOffset[i]);
            }
            world.bspSectors = leaves;
            BSPNode.visibleSectorsList = new Sector[leafCount];

            BSPNode[] nodes = new BSPNode[nodeCount];
            for (int i = 0; i < nodeCount; ++i) {
                int front = convertChild(nodeFront[i], nodeCount, leafCount);
                int back = convertChild(nodeBack[i], nodeCount, leafCount);
                nodes[i] = new BSPNode(nodeStartX[i] << 16, nodeStartZ[i] << 16,
                        nodeDeltaX[i] << 16, nodeDeltaZ[i] << 16, front, back);
            }
            world.bspNodes = nodes;
            world.setRootBSPNodeIndex(rootNode);

            int pvsByteCount = BinaryUtils.readIntLE(input);
            int expectedPvsBytes = (sectorCount * sectorCount + 7) >> 3;
            if (pvsByteCount != expectedPvsBytes) throw new IOException("Bad C3B PVS size");
            byte[] pvs = new byte[pvsByteCount];
            input.readFully(pvs, 0, pvsByteCount);
            applyVisibility(sectors, pvs);

            materials.installTextureAnimations(world);
            LevelLoader.finishCustomMapLoad(world);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return false;
        } catch (OutOfMemoryError e) {
            lastError = "Not enough memory to load level";
            return false;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Reads legacy inline object records for early C3B1 packages. */
    private static void installInlineObjects(DataInputStream input, int objectCount,
                                             GameWorld world, boolean shouldLoadObjects,
                                             boolean bloodMap)
            throws IOException {
        if (LevelLoader.levelVariant == 0) LevelLoader.levelVariant = 1;
        for (int i = 0; i < objectCount; ++i) {
            short x = BinaryUtils.readShortLE(input);
            short z = BinaryUtils.readShortLE(input);
            short angle = BinaryUtils.readShortLE(input);
            short type = BinaryUtils.readShortLE(input);
            short parameter = BinaryUtils.readShortLE(input);

            if (type >= 1 && type <= 4 && LevelLoader.levelVariant == type) {
                world.worldOrigin = new Transform3D(x << 16, 0, z << 16, MathUtils.fromAngle4096(angle));
                if (bloodMap && parameter >= 0) world.bloodStartSector = parameter;
            }
        }
        if (world.worldOrigin == null) {
            world.worldOrigin = new Transform3D(0, 0, 0, 0);
        }
    }

    private static void readMagic(DataInputStream input) throws IOException {
        if (input.readUnsignedByte() != 'C' || input.readUnsignedByte() != '3'
                || input.readUnsignedByte() != 'B' || input.readUnsignedByte() != '1') {
            throw new IOException("Not a C3B level");
        }
    }

    private static int readCount(DataInputStream input, String name) throws IOException {
        int count = BinaryUtils.readShortLE(input) & 0xFFFF;
        if (count == 0) throw new IOException("C3B has no " + name);
        return count;
    }

    /** Object records are optional because new packages use entities.ini. */
    private static int readOptionalCount(DataInputStream input) throws IOException {
        return BinaryUtils.readShortLE(input) & 0xFFFF;
    }

    private static void validateCounts(int vertices, int walls, int surfaces, int sectors,
                                       int nodes, int leaves, int segments, int rootNode)
            throws IOException {
        if (vertices > 32767 || walls > 32767 || surfaces > 32767 || sectors > 32767
                || nodes > 32767 || leaves > 32767 || segments > 32767) {
            throw new IOException("C3B exceeds signed-index limits");
        }
        if (rootNode < 0 || rootNode >= nodes) throw new IOException("Bad C3B root node");
    }

    private static int convertChild(short child, int nodeCount, int leafCount) throws IOException {
        if (child >= 0) {
            if (child >= nodeCount) throw new IOException("C3B node child out of range");
            return child;
        }
        int leaf = -child - 1;
        if (leaf < 0 || leaf >= leafCount) throw new IOException("C3B leaf child out of range");
        return 0x8000 | leaf;
    }

    /**
     * C3B stores natural from->to visibility; current runtime stores inverse
     * to->from flags. Returns the count for one load-time diagnostic, which is
     * useful when distinguishing actual portal artifacts from PVS culling.
     */
    private static int applyVisibility(SectorData[] sectors, byte[] pvs) {
        int sectorCount = sectors.length;
        int visiblePairs = 0;
        for (int to = 0; to < sectorCount; ++to) {
            sectors[to].visitedFlags = new boolean[sectorCount];
        }
        for (int from = 0; from < sectorCount; ++from) {
            for (int to = 0; to < sectorCount; ++to) {
                int bit = from * sectorCount + to;
                boolean visible = (pvs[bit >> 3] & (1 << (bit & 7))) != 0;
                if (visible) ++visiblePairs;
                sectors[to].visitedFlags[from] = !visible;
            }
        }
        return visiblePairs;
    }

    private static String resolvePath(String levelPath, String relativePath) {
        if (relativePath.length() > 0 && relativePath.charAt(0) == '/') return relativePath;
        int slash = levelPath.lastIndexOf('/');
        return slash < 0 ? "/" + relativePath : levelPath.substring(0, slash + 1) + relativePath;
    }
}
