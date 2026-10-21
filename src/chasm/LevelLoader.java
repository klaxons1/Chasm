package chasm;

/**
 * Texture registry and active GameWorld holder for CovertOps3D-RE PortalRenderer
 * and C3B1 CustomLevelLoader.
 */
public final class LevelLoader {
    public static Texture[] textureTable = new Texture[256];
    public static Texture defaultErrorTexture;
    public static GameWorld gameWorld;
    public static int levelVariant = 1;

    private LevelLoader() {}

    public static void initDefaultTexture() {
        if (defaultErrorTexture == null) {
            defaultErrorTexture = new Texture(
                (byte) 0, 8, 8, 0, 0,
                new int[]{0xFFFFFF, 0xFF0000}
            );
            byte[] row0 = new byte[]{17, 17, 17, 17, 17, 17, 17, 17};
            byte[] row1 = new byte[]{17, 16, 16, 16, 16, 17, 16, 17};
            byte[] row2 = new byte[]{17, 1, 1, 1, 1, 17, 1, 17};
            defaultErrorTexture.setPixelData(0, row0);
            defaultErrorTexture.setPixelData(2, row1);
            defaultErrorTexture.setPixelData(4, row2);
            defaultErrorTexture.setPixelData(6, row0);
        }
    }

    public static void prepareCustomMapLoad() {
        initDefaultTexture();
        for (int i = 0; i < textureTable.length; i++) {
            textureTable[i] = null;
        }
    }

    public static void finishCustomMapLoad(GameWorld world) {
        gameWorld = world;
        world.initializeWorld();
    }

    public static void registerExternalTexture(byte slot, Texture texture) {
        textureTable[slot + 128] = texture;
    }

    public static Texture getTexture(byte slot) {
        Texture tex = textureTable[slot + 128];
        return tex != null ? tex : defaultErrorTexture;
    }
}
