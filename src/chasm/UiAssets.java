package chasm;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * UI images restored from the first build's /ui.bin (LOADING.CEL logo, M_TILE1.CEL menu tile,
 * STATUS2.CEL HUD strip). Index 255 is transparent (ARGB alpha 0, drawn with processAlpha).
 */
public final class UiAssets {
    public static int logoW, logoH;
    public static int[] logo;
    public static int tileW, tileH;
    public static int[] tile;
    public static int hudW, hudH;
    public static int[] hud;

    private static boolean loaded = false;

    private UiAssets() {}

    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        int[] pal = loadPalette();
        InputStream is = null;
        try {
            is = UiAssets.class.getResourceAsStream("/ui.bin");
            if (is == null) return;
            DataInputStream dis = new DataInputStream(is);
            logoW = dis.readUnsignedShort();
            logoH = dis.readUnsignedShort();
            logo = readImage(dis, logoW * logoH, pal);
            tileW = dis.readUnsignedShort();
            tileH = dis.readUnsignedShort();
            tile = readImage(dis, tileW * tileH, pal);
            hudW = dis.readUnsignedShort();
            hudH = dis.readUnsignedShort();
            hud = readImage(dis, hudW * hudH, pal);
        } catch (Exception e) {
            logo = null;
            tile = null;
            hud = null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ex) {}
            }
        }
    }

    private static int[] readImage(DataInputStream dis, int count, int[] pal) throws Exception {
        byte[] raw = new byte[count];
        dis.readFully(raw);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int idx = raw[i] & 0xFF;
            out[i] = (idx == 255) ? 0x00000000 : (0xFF000000 | pal[idx]);
        }
        return out;
    }

    private static int[] loadPalette() {
        int[] pal = new int[256];
        InputStream is = null;
        try {
            is = UiAssets.class.getResourceAsStream("/pal.bin");
            byte[] raw = new byte[768];
            int got = 0;
            if (is != null) {
                while (got < 768) {
                    int n = is.read(raw, got, 768 - got);
                    if (n <= 0) break;
                    got += n;
                }
            }
            for (int i = 0; i < 256; i++) {
                pal[i] = (got >= 768)
                        ? (((raw[i * 3] & 0xFF) << 16) | ((raw[i * 3 + 1] & 0xFF) << 8) | (raw[i * 3 + 2] & 0xFF))
                        : ((i << 16) | (i << 8) | i);
            }
        } catch (Exception e) {
            for (int i = 0; i < 256; i++) pal[i] = (i << 16) | (i << 8) | i;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ex) {}
            }
        }
        return pal;
    }
}
