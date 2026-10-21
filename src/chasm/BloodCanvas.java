package chasm;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;

/**
 * Minimal Blood MAP exploration canvas. It reuses the J2ME portal renderer and
 * C3B loader while keeping Blood resource packaging and input separate from the
 * Chasm campaign. Blood actors, weapons and map events are layered in later
 * gameplay work; this screen is the playable geometry/material integration.
 */
public final class BloodCanvas extends GameCanvas implements Runnable {
    private static final int KEY_LEFT_SOFT = -6;
    private static final int KEY_RIGHT_SOFT = -7;
    private static final int KEY_BACK = -11;

    private final GameWorld world;
    private final String mapName;
    private volatile boolean running;
    private Thread loop;
    private boolean forward;
    private boolean backward;
    private boolean turnLeft;
    private boolean turnRight;
    private boolean strafeLeft;
    private boolean strafeRight;
    private boolean paused;
    private String loadError;
    private int frameNumber;
    private int fps;
    private long fpsStart;
    private int[] scaledPixels;

    public BloodCanvas(String requestedMap) {
        super(false);
        setFullScreenMode(true);
        CollisionSystem.useBloodScale();
        GameWorld.PLAYER_HEIGHT_OFFSET = 6;
        world = new GameWorld();
        mapName = normalizeMapName(requestedMap);
        String path = "/blood/levels/" + mapName + ".c3b";
        if (!CustomLevelLoader.load(path, false, world)) {
            String detail = CustomLevelLoader.lastError;
            loadError = "Could not load " + mapName
                    + (detail == null ? ". Check the Blood asset build." : ": " + detail);
        } else if (world.worldOrigin != null) {
            world.enablePolygonSectorLookup();
            world.playerBody.copyFrom(world.worldOrigin);
            world.health = 100;
            world.monCount = 0;
            world.itemCount = 0;
            world.propCount = 0;
        } else {
            loadError = "Map has no player start: " + mapName;
        }
    }

    private static String normalizeMapName(String requested) {
        if (requested == null || requested.length() == 0) return "e1m1";
        String name = requested;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        StringBuffer clean = new StringBuffer();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') {
                clean.append(Character.toLowerCase(c));
            }
        }
        return clean.length() == 0 ? "e1m1" : clean.toString();
    }

    public void start() {
        if (running) return;
        running = true;
        loop = new Thread(this);
        loop.start();
    }

    public void stop() {
        running = false;
        if (loop != null) loop.interrupt();
    }

    public void run() {
        fpsStart = System.currentTimeMillis();
        while (running) {
            long start = System.currentTimeMillis();
            if (!paused && loadError == null) updateWorld();
            drawFrame();
            frameNumber++;
            long now = System.currentTimeMillis();
            if (now - fpsStart >= 1000L) {
                fps = frameNumber;
                frameNumber = 0;
                fpsStart = now;
            }
            long delay = 40L - (System.currentTimeMillis() - start);
            if (delay < 5L) delay = 5L;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void updateWorld() {
        int forwardFP = 0;
        int strafeFP = 0;
        int turn = 0;
        int speed = 2 << 16;
        if (forward) forwardFP += speed;
        if (backward) forwardFP -= speed;
        if (strafeLeft) strafeFP -= speed;
        if (strafeRight) strafeFP += speed;
        if (turnLeft) turn -= 6000;
        if (turnRight) turn += 6000;
        world.movePlayer(forwardFP, strafeFP, turn);
        world.levelTick++;
    }

    private void drawFrame() {
        Graphics g = getGraphics();
        if (g == null) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0) width = 240;
        if (height <= 0) height = 320;

        g.setColor(0x000000);
        g.fillRect(0, 0, width, height);
        if (loadError == null) {
            world.render3DView(PortalRenderer.screenBuffer);
            drawViewport(g, width, height);
            drawHud(g, width, height);
        } else {
            g.setColor(0xFFFFFF);
            g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL));
            g.drawString("BLOOD J2ME", width / 2, height / 3,
                    Graphics.TOP | Graphics.HCENTER);
            Font errorFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            g.setFont(errorFont);
            drawWrappedError(g, errorFont, width, height / 2);
        }
        if (paused) {
            g.setColor(0x000000);
            g.fillRect(0, height / 2 - 12, width, 24);
            g.setColor(0xFFFFFF);
            g.drawString("PAUSED - press 5", width / 2, height / 2 - 8,
                    Graphics.TOP | Graphics.HCENTER);
        }
        flushGraphics();
    }

    private void drawWrappedError(Graphics g, Font font, int width, int topY) {
        int maxWidth = width - 12;
        int lineY = topY;
        StringBuffer line = new StringBuffer();
        for (int i = 0; i < loadError.length(); i++) {
            char ch = loadError.charAt(i);
            line.append(ch);
            if (font.stringWidth(line.toString()) > maxWidth && line.length() > 1) {
                line.setLength(line.length() - 1);
                g.drawString(line.toString(), width / 2, lineY,
                        Graphics.TOP | Graphics.HCENTER);
                lineY += font.getHeight() + 2;
                line.setLength(0);
                if (ch != ' ') line.append(ch);
            }
            if (lineY > getHeight() - font.getHeight()) return;
        }
        if (line.length() > 0) {
            g.drawString(line.toString(), width / 2, lineY,
                    Graphics.TOP | Graphics.HCENTER);
        }
    }

    private void drawViewport(Graphics g, int width, int height) {
        int srcWidth = PortalRenderer.VIEWPORT_WIDTH;
        int srcHeight = PortalRenderer.VIEWPORT_HEIGHT;
        if (width == srcWidth && height >= srcHeight + 24) {
            g.drawRGB(PortalRenderer.screenBuffer, 0, srcWidth, 0, 0,
                    srcWidth, srcHeight, false);
            return;
        }
        int viewHeight = height > 24 ? height - 24 : height;
        if (scaledPixels == null || scaledPixels.length != width * viewHeight) {
            scaledPixels = new int[width * viewHeight];
        }
        int[] source = PortalRenderer.screenBuffer;
        for (int y = 0; y < viewHeight; y++) {
            int sy = y * srcHeight / viewHeight;
            int row = y * width;
            int sourceRow = sy * srcWidth;
            for (int x = 0; x < width; x++) {
                scaledPixels[row + x] = source[sourceRow + x * srcWidth / width];
            }
        }
        g.drawRGB(scaledPixels, 0, width, 0, 0, width, viewHeight, false);
    }

    private void drawHud(Graphics g, int width, int height) {
        int hudTop = height - 24;
        g.setColor(0x090909);
        g.fillRect(0, hudTop, width, 24);
        g.setColor(0x8E1717);
        g.fillRect(0, hudTop, width, 2);
        g.setColor(0xE6D9C8);
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL));
        g.drawString("BLOOD  " + mapName.toUpperCase(), 4, hudTop + 4,
                Graphics.TOP | Graphics.LEFT);
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));
        g.drawString("2/8 MOVE  4/6 TURN  1/3 STRAFE  5 PAUSE  " + fps + " FPS",
                4, hudTop + 13, Graphics.TOP | Graphics.LEFT);
        int cx = width / 2;
        int cy = (height - 24) / 2;
        g.setColor(0xD8D0C8);
        g.drawLine(cx - 3, cy, cx + 3, cy);
        g.drawLine(cx, cy - 3, cx, cy + 3);
    }

    protected void keyPressed(int keyCode) {
        if (keyCode == KEY_LEFT_SOFT || keyCode == KEY_RIGHT_SOFT || keyCode == KEY_BACK) {
            paused = !paused;
            clearKeys();
            return;
        }
        switch (keyCode) {
            case KEY_NUM2: forward = true; break;
            case KEY_NUM8: backward = true; break;
            case KEY_NUM4: turnLeft = true; break;
            case KEY_NUM6: turnRight = true; break;
            case KEY_NUM1: strafeLeft = true; break;
            case KEY_NUM3: strafeRight = true; break;
            case KEY_NUM5: paused = !paused; break;
            default:
                try {
                    int action = getGameAction(keyCode);
                    if (action == UP) forward = true;
                    else if (action == DOWN) backward = true;
                    else if (action == LEFT) turnLeft = true;
                    else if (action == RIGHT) turnRight = true;
                    else if (action == FIRE) paused = !paused;
                } catch (Exception ignored) {
                }
        }
    }

    protected void keyReleased(int keyCode) {
        switch (keyCode) {
            case KEY_NUM2: forward = false; break;
            case KEY_NUM8: backward = false; break;
            case KEY_NUM4: turnLeft = false; break;
            case KEY_NUM6: turnRight = false; break;
            case KEY_NUM1: strafeLeft = false; break;
            case KEY_NUM3: strafeRight = false; break;
            default:
                try {
                    int action = getGameAction(keyCode);
                    if (action == UP) forward = false;
                    else if (action == DOWN) backward = false;
                    else if (action == LEFT) turnLeft = false;
                    else if (action == RIGHT) turnRight = false;
                } catch (Exception ignored) {
                }
        }
    }

    private void clearKeys() {
        forward = backward = turnLeft = turnRight = strafeLeft = strafeRight = false;
    }
}
