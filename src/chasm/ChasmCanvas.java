package chasm;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;

/**
 * Main GameCanvas, Input Handler, HUD, BSP-Portal Automap, and Game Loop for
 * Chasm: The Rift (J2ME Edition).
 *
 * Uses CovertOps3D-RE's PortalRenderer for C3D2/C3B world geometry and
 * PolyRenderer3D (software 3D polygon renderer without a Z-buffer) for
 * multi-part dismemberable .CAR enemies, detached limbs, and .3O weapons/items.
 */
public final class ChasmCanvas extends GameCanvas implements Runnable {
    public static final int SCREEN_MAIN_MENU = 0;
    public static final int SCREEN_BRIEFING  = 1;
    public static final int SCREEN_PLAYING   = 2;
    public static final int SCREEN_PAUSED    = 3;
    public static final int SCREEN_CONTROLS  = 4;
    public static final int SCREEN_VICTORY   = 5;

    // Nokia S60 & Sony Ericsson softkey keycodes
    private static final int KEY_SOFT_LEFT1  = -6;
    private static final int KEY_SOFT_RIGHT1 = -7;
    private static final int KEY_SOFT_LEFT2  = -21;
    private static final int KEY_SOFT_RIGHT2 = -22;
    private static final int KEY_SE_BACK     = -11;

    private static final String[] SHADE_TAGS = { "VC", "FL", "FV", "TX" };

    private final ChasmMIDlet midlet;
    private final SoundManager sound;
    private final GameWorld world;

    private volatile boolean running = false;
    private Thread gameThread;

    public int screenState = SCREEN_MAIN_MENU;
    private int menuCursor = 0;
    private int pauseCursor = 0;
    private int selectedStartLevel = 0; // 0..3
    private boolean showAutomap = false;

    private int scrW = 240;
    private int scrH = 320;
    private int hudH = 32;
    private int viewW = 240;
    private int viewH = 288;
    private int[] scaledBuf;

    // Held keypad flags for simultaneous multi-key movement on Nokia S60 & Sony Ericsson
    private boolean kUp, kDown, kLeft, kRight, kStrafeL, kStrafeR, kFire;
    private int fpsDisplay = 25;
    private int frameCounter = 0;
    private long fpsTimer = 0L;

    public ChasmCanvas(ChasmMIDlet midlet, SoundManager sound) {
        super(false);
        setFullScreenMode(true);
        this.midlet = midlet;
        this.sound = sound;
        this.world = new GameWorld();
        this.world.loadLevel(0);
    }

    public PolyRenderer3D getRenderer3D() {
        return world.polyRenderer;
    }

    public GameWorld getWorld() {
        return world;
    }

    public void setSelectedStartLevel(int lvl) {
        if (lvl >= 0 && lvl < GameWorld.NUM_LEVELS) {
            selectedStartLevel = lvl;
        }
    }

    public int getSelectedStartLevel() {
        return selectedStartLevel;
    }

    public void startLoop() {
        if (running) return;
        running = true;
        if (sound != null) {
            sound.playMusic(0);
        }
        gameThread = new Thread(this);
        gameThread.start();
    }

    public void stopLoop() {
        running = false;
    }

    private void ensureBuffers() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0) w = 240;
        if (h <= 0) h = 320;
        if (w != scrW || h != scrH) {
            scrW = w;
            scrH = h;
            hudH = (h >= 240) ? 32 : 24;
            viewW = w;
            viewH = h - hudH;
            if (viewW != PortalRenderer.VIEWPORT_WIDTH || viewH != PortalRenderer.VIEWPORT_HEIGHT) {
                scaledBuf = new int[viewW * viewH];
            } else {
                scaledBuf = null;
            }
        }
    }

    public void run() {
        fpsTimer = System.currentTimeMillis();
        while (running) {
            long frameStart = System.currentTimeMillis();
            try {
                ensureBuffers();
                updateLogic();
                renderScreen();
                flushGraphics();
            } catch (Throwable t) {
                // Keep game loop alive on any device quirk
            }

            frameCounter++;
            long now = System.currentTimeMillis();
            if (now - fpsTimer >= 1000L) {
                fpsDisplay = frameCounter;
                frameCounter = 0;
                fpsTimer = now;
            }

            long elapsed = System.currentTimeMillis() - frameStart;
            long sleepMs = 36L - elapsed; // ~28 FPS target
            if (sleepMs < 4L) sleepMs = 4L;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {}
        }
    }

    private void updateLogic() {
        if (screenState == SCREEN_MAIN_MENU) {
            // Gently pan camera in background portal preview
            world.playerBody.rotation = MathUtils.wrapAngle(world.playerBody.rotation + 900);
            world.levelTick++;
            return;
        }

        if (screenState != SCREEN_PLAYING) {
            return;
        }

        int ks = getKeyStates();
        int forwardFP = 0;
        int strafeFP = 0;
        int turnDelta = 0;

        int moveSpeed = 7 << 16;
        int strafeSpeed = 6 << 16;
        int turnSpeed = 7200;

        if (kUp || (ks & UP_PRESSED) != 0) forwardFP += moveSpeed;
        if (kDown || (ks & DOWN_PRESSED) != 0) forwardFP -= moveSpeed;
        if (kLeft || (ks & LEFT_PRESSED) != 0) turnDelta -= turnSpeed;
        if (kRight || (ks & RIGHT_PRESSED) != 0) turnDelta += turnSpeed;
        if (kStrafeL) strafeFP -= strafeSpeed;
        if (kStrafeR) strafeFP += strafeSpeed;

        world.movePlayer(forwardFP, strafeFP, turnDelta);

        if (kFire || (ks & FIRE_PRESSED) != 0) {
            if (world.gameOver) {
                kFire = false;
                world.resetInventory();
                world.loadLevel(world.currentLevel);
                return;
            }
            world.fireWeapon();
        }

        world.updateTick();

        if (world.levelCompleted) {
            world.levelCompleted = false;
            if (world.currentLevel + 1 < GameWorld.NUM_LEVELS) {
                selectedStartLevel = world.currentLevel + 1;
                world.loadLevel(selectedStartLevel);
                if (midlet != null) midlet.saveSettings();
                screenState = SCREEN_BRIEFING;
                if (sound != null) sound.playMusic(selectedStartLevel + 1);
            } else {
                screenState = SCREEN_VICTORY;
                if (sound != null) sound.playMusic(0);
            }
        }
    }

    public void renderScreen() {
        Graphics g = getGraphics();
        renderToGraphics(g);
    }

    public void renderToGraphics(Graphics g) {
        ensureBuffers();
        switch (screenState) {
            case SCREEN_MAIN_MENU:
                drawMainMenu(g);
                break;
            case SCREEN_BRIEFING:
                drawBriefingScreen(g);
                break;
            case SCREEN_PLAYING:
                drawPlayingView(g);
                break;
            case SCREEN_PAUSED:
                drawPlayingView(g);
                drawPauseOverlay(g);
                break;
            case SCREEN_CONTROLS:
                drawControlsScreen(g);
                break;
            case SCREEN_VICTORY:
                drawVictoryScreen(g);
                break;
        }
    }

    private int[] scaleXMap;
    private int[] scaleYRow;

    private void blitViewport(Graphics g) {
        if (viewW == PortalRenderer.VIEWPORT_WIDTH && viewH == PortalRenderer.VIEWPORT_HEIGHT) {
            g.drawRGB(
                PortalRenderer.screenBuffer,
                0,
                PortalRenderer.VIEWPORT_WIDTH,
                0, 0,
                PortalRenderer.VIEWPORT_WIDTH,
                PortalRenderer.VIEWPORT_HEIGHT,
                false
            );
        } else {
            if (scaledBuf == null || scaledBuf.length != viewW * viewH) {
                scaledBuf = new int[viewW * viewH];
            }
            int srcW = PortalRenderer.VIEWPORT_WIDTH;
            int srcH = PortalRenderer.VIEWPORT_HEIGHT;
            // Source index maps are built once per view size, so the per-pixel
            // loop is a table lookup + store with no division.
            if (scaleXMap == null || scaleXMap.length != viewW
                    || scaleYRow == null || scaleYRow.length != viewH) {
                scaleXMap = new int[viewW];
                scaleYRow = new int[viewH];
                for (int x = 0; x < viewW; x++) {
                    scaleXMap[x] = (x * srcW) / viewW;
                }
                for (int y = 0; y < viewH; y++) {
                    scaleYRow[y] = ((y * srcH) / viewH) * srcW;
                }
            }
            int[] src = PortalRenderer.screenBuffer;
            int[] dst = scaledBuf;
            int[] xMap = scaleXMap;
            int[] yRow = scaleYRow;
            for (int y = 0; y < viewH; y++) {
                int srcRow = yRow[y];
                int dstRow = y * viewW;
                for (int x = 0; x < viewW; x++) {
                    dst[dstRow + x] = src[srcRow + xMap[x]];
                }
            }
            g.drawRGB(scaledBuf, 0, viewW, 0, 0, viewW, viewH, false);
        }
    }

    /**
     * Renders one monster model at a time, cycling through the roster, into a framed
     * window. Uses the painter's-algorithm model path into the viewport buffer and
     * copies only the window rectangle to the screen.
     */
    private void drawEnemyPreviewWindow(Graphics g, int x, int y, int w, int h) {
        int[] buf = PortalRenderer.screenBuffer;
        int vw = PortalRenderer.VIEWPORT_WIDTH;
        int vh = PortalRenderer.VIEWPORT_HEIGHT;
        g.setColor(0x101410);
        g.fillRect(x, y, w, h);
        if (buf == null || world.polyRenderer == null || w > vw || h > vh) {
            return;
        }

        int t = world.levelTick;
        int slot = (t / 96) % GameWorld.MONSTER_NAMES.length;
        Model3D model = world.polyRenderer.getModel(slot);

        // Clear the window rectangle in the viewport buffer (centred, same stride).
        int bx = (vw - w) >> 1;
        int by = (vh - h) >> 1;
        int bg = 0xFF101410;
        for (int row = by; row < by + h; row++) {
            int o = row * vw + bx;
            for (int c = 0; c < w; c++) {
                buf[o + c] = bg;
            }
        }

        if (model != null && model.fCount > 0) {
            int frames = model.fCount;
            int step = (t >> 2);
            int fA = step % frames;
            int fB = (fA + 1) % frames;
            int interp = (t & 3) << 6;
            // Undo the camera yaw so the monster turns on its own axis, not with the menu pan.
            int spin = MathUtils.wrapAngle(t * 700 + world.playerBody.rotation);
            world.polyRenderer.renderWorldModel(
                    model,
                    0, 0, 96 << 16,
                    spin,
                    fA, fB, interp,
                    0xFF,
                    1100,
                    0, 786432, 0,
                    0, 65536,
                    236, 0,
                    false, buf);
        }

        g.drawRGB(buf, by * vw + bx, vw, x, y, w, h, false);
        g.setColor(0x7A8B68);
        g.drawRect(x, y, w - 1, h - 1);

        Font nameFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(nameFont);
        g.setColor(0xFFE066);
        g.drawString(GameWorld.MONSTER_NAMES[slot], x + (w >> 1), y + h + 2, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawMainMenu(Graphics g) {
        UiAssets.ensureLoaded();

        // First-build menu look: tiled M_TILE1 background, LOADING.CEL logo, option list.
        if (UiAssets.tile != null && UiAssets.tileW > 0) {
            for (int y = 0; y < scrH; y += UiAssets.tileH) {
                for (int x = 0; x < scrW; x += UiAssets.tileW) {
                    g.drawRGB(UiAssets.tile, 0, UiAssets.tileW, x, y, UiAssets.tileW, UiAssets.tileH, true);
                }
            }
        } else {
            g.setColor(0x121412);
            g.fillRect(0, 0, scrW, scrH);
        }

        // Live enemy window (first-build style): the 3D monsters animate in place of the LOADING logo.
        int winH = scrH >= 280 ? 96 : 64;
        int winW = (winH * 4) / 3;
        int winX = (scrW - winW) >> 1;
        int topY = 6;
        drawEnemyPreviewWindow(g, winX, topY, winW, winH);
        topY += winH + 18;

        String shadeStr = PolyRenderer3D.SHADE_NAMES[world.polyRenderer.shadingMode];
        String soundStr = (sound != null && sound.musicEnabled) ? "ON" : "OFF";

        String[] items = {
            "NEW / START MISSION",
            "MAP: " + GameWorld.LEVEL_NAMES[selectedStartLevel],
            "3D SHADING: " + shadeStr,
            "AUDIO (MIDI+WAV): " + soundStr,
            "CONTROLS & HELP",
            "EXIT GAME"
        };

        int boxW = scrW - 20;
        int boxH = 6 * 22 + 14;
        int boxX = 10;
        int boxY = topY + 2;
        if (boxY + boxH > scrH - hudH - 4) boxY = scrH - hudH - 4 - boxH;

        g.setColor(0x101410);
        g.fillRect(boxX, boxY, boxW, boxH);
        g.setColor(0x9A7B38);
        g.drawRect(boxX, boxY, boxW - 1, boxH - 1);
        g.setColor(0x52401C);
        g.drawRect(boxX + 2, boxY + 2, boxW - 5, boxH - 5);

        Font smallFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(smallFont);
        int listY = boxY + 7;
        for (int i = 0; i < items.length; i++) {
            int iy = listY + i * 22;
            if (i == menuCursor) {
                g.setColor(0x3A2C14);
                g.fillRect(boxX + 8, iy - 2, boxW - 16, 19);
                g.setColor(0xFFE066);
                g.drawRect(boxX + 8, iy - 2, boxW - 17, 18);
                g.drawString(items[i], scrW >> 1, iy, Graphics.TOP | Graphics.HCENTER);
            } else {
                g.setColor(0xC8C4B4);
                g.drawString(items[i], scrW >> 1, iy, Graphics.TOP | Graphics.HCENTER);
            }
        }

        // Bottom HUD strip (STATUS2.CEL) with the menu hint
        int stripY = scrH - hudH;
        g.setColor(0x1C201C);
        g.fillRect(0, stripY, scrW, hudH);
        if (UiAssets.hud != null && UiAssets.hudW > 0 && UiAssets.hudW <= scrW) {
            int hx = (scrW - UiAssets.hudW) >> 1;
            g.drawRGB(UiAssets.hud, 0, UiAssets.hudW, hx, stripY, UiAssets.hudW, Math.min(UiAssets.hudH, hudH), true);
        }
        g.setColor(0xD8D0A0);
        g.drawString("2/8 MOVE   5 SELECT", scrW >> 1, stripY + 10, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawBriefingScreen(Graphics g) {
        g.setColor(0x101410);
        g.fillRect(0, 0, scrW, scrH);
        g.setColor(0x9A7B38);
        g.drawRect(4, 4, scrW - 9, scrH - 9);

        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        g.setFont(bold);
        g.setColor(0xFFCC44);
        g.drawString(GameWorld.LEVEL_NAMES[world.currentLevel], scrW >> 1, 18, Graphics.TOP | Graphics.HCENTER);

        g.setFont(small);
        g.setColor(0xD0D8C8);
        String[] lines = GameWorld.LEVEL_BRIEFINGS[world.currentLevel];
        int y = 54;
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], scrW >> 1, y, Graphics.TOP | Graphics.HCENTER);
            y += 20;
        }

        g.setColor(0xFFE066);
        g.drawString("PRESS FIRE / 5 TO ENTER RIFT", scrW >> 1, scrH - 28, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawPlayingView(Graphics g) {
        // 1. Render 3D Portal World + 3D Enemies/Limbs/Weapons into PortalRenderer.screenBuffer
        world.render3DView(PortalRenderer.screenBuffer);
        blitViewport(g);

        // 2. Draw crosshair at center of 3D viewport
        int cx = viewW >> 1;
        int cy = viewH >> 1;
        g.setColor(0x55FF55);
        g.drawLine(cx - 4, cy, cx - 2, cy);
        g.drawLine(cx + 2, cy, cx + 4, cy);
        g.drawLine(cx, cy - 4, cx, cy - 2);
        g.drawLine(cx, cy + 2, cx, cy + 4);

        // 3. Draw optional 2D BSP-Portal Automap overlay
        if (showAutomap) {
            drawAutomapOverlay(g);
        }

        // 4. Draw top HUD message banner if active
        Font smallFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(smallFont);
        if (world.hudMessageTimer > 0 && world.hudMessage != null) {
            g.setColor(0x101410);
            g.fillRect(0, 0, scrW, 16);
            g.setColor(0xFFE066);
            g.drawString(world.hudMessage, scrW >> 1, 1, Graphics.TOP | Graphics.HCENTER);
        }

        // 5. Draw Chasm bottom status bar HUD
        drawStatusBar(g, smallFont);
    }

    private void drawStatusBar(Graphics g, Font font) {
        int barY = viewH;
        g.setColor(0x1C201C);
        g.fillRect(0, barY, scrW, hudH);
        g.setColor(0x786434);
        g.drawRect(0, barY, scrW - 1, hudH - 1);
        g.drawLine(scrW / 3, barY, scrW / 3, barY + hudH);
        g.drawLine((scrW * 2) / 3, barY, (scrW * 2) / 3, barY + hudH);

        g.setFont(font);

        // Left box: HP & Armor
        g.setColor(world.health <= 25 ? 0xFF4444 : 0xFFCC44);
        g.drawString("HP:" + world.health, 6, barY + 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x88CCFF);
        g.drawString("AR:" + world.armor, 6, barY + 16, Graphics.TOP | Graphics.LEFT);

        // Center box: Active Weapon & Ammo
        g.setColor(0xFFE066);
        g.drawString(GameWorld.WEAPON_NAMES[world.curWeapon], scrW >> 1, barY + 2, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xD8D8C8);
        g.drawString("AMMO:" + world.ammo[world.curWeapon], scrW >> 1, barY + 16, Graphics.TOP | Graphics.HCENTER);

        // Right box: Dismembered Limbs & FPS / Shading
        int rx = (scrW * 2) / 3 + 5;
        g.setColor(0xFF7755);
        g.drawString("LIMBS:" + world.limbsSevered, rx, barY + 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x98B890);
        String modeTag = SHADE_TAGS[world.polyRenderer.shadingMode & 3];
        g.drawString(modeTag + " " + fpsDisplay + "FPS", rx, barY + 16, Graphics.TOP | Graphics.LEFT);
    }

    private void drawAutomapOverlay(Graphics g) {
        int mapW = Math.min(viewW - 16, 150);
        int mapH = Math.min(viewH - 24, 150);
        int mapX = (viewW - mapW) >> 1;
        int mapY = 18;

        g.setColor(0x0C120C);
        g.fillRect(mapX, mapY, mapW, mapH);
        g.setColor(0x789868);
        g.drawRect(mapX, mapY, mapW - 1, mapH - 1);

        int cx = mapX + (mapW >> 1);
        int cy = mapY + (mapH >> 1);
        int px = world.playerBody.x >> 16;
        int pz = world.playerBody.z >> 16;

        // Draw C3B wall segments around player
        if (world.wallDefinitions != null && world.vertices != null) {
            for (int i = 0; i < world.wallDefinitions.length; i++) {
                WallDefinition w = world.wallDefinitions[i];
                Point2D v0 = world.vertices[w.startVertexId & 0xFFFF];
                Point2D v1 = world.vertices[w.endVertexId & 0xFFFF];
                int x0 = cx + (((v0.x >> 16) - px) >> 3);
                int y0 = cy - (((v0.y >> 16) - pz) >> 3);
                int x1 = cx + (((v1.x >> 16) - px) >> 3);
                int y1 = cy - (((v1.y >> 16) - pz) >> 3);

                if ((x0 >= mapX && x0 < mapX + mapW && y0 >= mapY && y0 < mapY + mapH)
                        || (x1 >= mapX && x1 < mapX + mapW && y1 >= mapY && y1 < mapY + mapH)) {
                    g.setColor(CollisionSystem.isWallSolid(w) ? 0x88CC88 : 0x446644);
                    g.drawLine(x0, y0, x1, y1);
                }
            }
        }

        // Draw monsters on automap
        for (int m = 0; m < world.monCount; m++) {
            if (world.monState[m] == GameWorld.MSTATE_INACTIVE) continue;
            int mx = cx + (((world.monX[m] >> 16) - px) >> 3);
            int my = cy - (((world.monZ[m] >> 16) - pz) >> 3);
            if (mx > mapX + 2 && mx < mapX + mapW - 2 && my > mapY + 2 && my < mapY + mapH - 2) {
                g.setColor(world.monState[m] == GameWorld.MSTATE_DEAD ? 0x662222 : 0xFF3333);
                g.fillRect(mx - 1, my - 1, 3, 3);
            }
        }

        // Draw player marker
        g.setColor(0xFFFF44);
        g.fillRect(cx - 2, cy - 2, 4, 4);
    }

    private void drawPauseOverlay(Graphics g) {
        int boxW = scrW - 32;
        int boxH = 166;
        int boxX = 16;
        int boxY = (viewH - boxH) >> 1;

        g.setColor(0x101410);
        g.fillRect(boxX, boxY, boxW, boxH);
        g.setColor(0x9A7B38);
        g.drawRect(boxX, boxY, boxW - 1, boxH - 1);

        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(bold);
        g.setColor(0xFFCC44);
        g.drawString("GAME PAUSED", scrW >> 1, boxY + 8, Graphics.TOP | Graphics.HCENTER);

        String shadeStr = PolyRenderer3D.SHADE_NAMES[world.polyRenderer.shadingMode];
        String[] opts = {
            "RESUME MISSION",
            "SHADING: " + shadeStr,
            "SEVER ENEMY LIMB (DEMO)",
            "SKIP TO NEXT LEVEL",
            "MAIN MENU",
            "QUIT TO PHONE"
        };

        int y = boxY + 32;
        for (int i = 0; i < opts.length; i++) {
            if (i == pauseCursor) {
                g.setColor(0x3A2C14);
                g.fillRect(boxX + 8, y - 2, boxW - 16, 18);
                g.setColor(0xFFE066);
            } else {
                g.setColor(0xC8C4B4);
            }
            g.drawString(opts[i], scrW >> 1, y, Graphics.TOP | Graphics.HCENTER);
            y += 21;
        }
    }

    private void drawControlsScreen(Graphics g) {
        g.setColor(0x101410);
        g.fillRect(0, 0, scrW, scrH);
        g.setColor(0x9A7B38);
        g.drawRect(4, 4, scrW - 9, scrH - 9);

        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        Font plain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(bold);
        g.setColor(0xFFCC44);
        g.drawString("CONTROLS & HELP", scrW >> 1, 12, Graphics.TOP | Graphics.HCENTER);

        String[] keys = {
            "2 / 8", "4 / 6", "1 / 3", "5", "7", "9", "* / #", "0", "SOFT"
        };
        String[] acts = {
            "Forward / back",
            "Turn left / right",
            "Strafe left / right",
            "Fire & dismember",
            "Use / open / next level",
            "Level map (toggle)",
            "Previous / next weapon",
            "Jump",
            "Pause menu"
        };
        g.setFont(plain);
        int y = 36;
        for (int i = 0; i < keys.length; i++) {
            g.setColor(0xFFE066);
            g.drawString(keys[i], 14, y, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xD0D8C8);
            g.drawString(acts[i], 78, y, Graphics.TOP | Graphics.LEFT);
            y += 18;
        }

        g.setColor(0x9A9A88);
        g.drawString("TIP: aim at the enemy and fire;", 14, y + 8, Graphics.TOP | Graphics.LEFT);
        g.drawString("sever arms & head for bonus kills.", 14, y + 24, Graphics.TOP | Graphics.LEFT);
        g.drawString("Clear every enemy, then press 7.", 14, y + 40, Graphics.TOP | Graphics.LEFT);

        g.setFont(bold);
        g.setColor(0xFFE066);
        g.drawString("PRESS ANY KEY TO RETURN", scrW >> 1, scrH - 24, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawVictoryScreen(Graphics g) {
        g.setColor(0x101410);
        g.fillRect(0, 0, scrW, scrH);
        g.setColor(0x9A7B38);
        g.drawRect(4, 4, scrW - 9, scrH - 9);

        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);

        g.setFont(bold);
        g.setColor(0xFFCC44);
        g.drawString("THE RIFT IS SEALED!", scrW >> 1, 24, Graphics.TOP | Graphics.HCENTER);

        g.setFont(small);
        g.setColor(0xD0D8C8);
        g.drawString("ALL 4 TIME CHANNELS CLEARED", scrW >> 1, 68, Graphics.TOP | Graphics.HCENTER);
        g.drawString("MONSTERS DEFEATED: " + world.monstersKilled, scrW >> 1, 104, Graphics.TOP | Graphics.HCENTER);
        g.drawString("LIMBS SEVERED: " + world.limbsSevered, scrW >> 1, 128, Graphics.TOP | Graphics.HCENTER);

        g.setColor(0xFFE066);
        g.drawString("PRESS FIRE FOR MAIN MENU", scrW >> 1, scrH - 32, Graphics.TOP | Graphics.HCENTER);
    }

    // ==================== Keypad Input Handling ====================

    protected void keyPressed(int keyCode) {
        int action = 0;
        try {
            action = getGameAction(keyCode);
        } catch (Exception e) {}

        boolean isSoftKey = (keyCode == KEY_SOFT_LEFT1 || keyCode == KEY_SOFT_RIGHT1
                || keyCode == KEY_SOFT_LEFT2 || keyCode == KEY_SOFT_RIGHT2
                || keyCode == KEY_SE_BACK);

        if (screenState == SCREEN_MAIN_MENU) {
            if (keyCode == KEY_NUM2 || action == UP) {
                menuCursor = (menuCursor + 5) % 6;
            } else if (keyCode == KEY_NUM8 || action == DOWN) {
                menuCursor = (menuCursor + 1) % 6;
            } else if (keyCode == KEY_NUM5 || action == FIRE || keyCode == KEY_SOFT_LEFT1) {
                handleMainMenuSelect();
            }
            return;
        }

        if (screenState == SCREEN_BRIEFING) {
            if (keyCode == KEY_NUM5 || action == FIRE || isSoftKey) {
                screenState = SCREEN_PLAYING;
            }
            return;
        }

        if (screenState == SCREEN_CONTROLS) {
            screenState = SCREEN_MAIN_MENU;
            return;
        }

        if (screenState == SCREEN_VICTORY) {
            if (keyCode == KEY_NUM5 || action == FIRE || isSoftKey) {
                screenState = SCREEN_MAIN_MENU;
            }
            return;
        }

        if (screenState == SCREEN_PAUSED) {
            if (keyCode == KEY_NUM2 || action == UP) {
                pauseCursor = (pauseCursor + 5) % 6;
            } else if (keyCode == KEY_NUM8 || action == DOWN) {
                pauseCursor = (pauseCursor + 1) % 6;
            } else if (keyCode == KEY_NUM5 || action == FIRE || keyCode == KEY_SOFT_LEFT1) {
                handlePauseSelect();
            } else if (keyCode == KEY_NUM9 || keyCode == KEY_SOFT_RIGHT1 || keyCode == KEY_SE_BACK) {
                screenState = SCREEN_PLAYING;
            }
            return;
        }

        // SCREEN_PLAYING
        if (isSoftKey) {
            clearHeldKeys();
            pauseCursor = 0;
            screenState = SCREEN_PAUSED;
            return;
        }

        switch (keyCode) {
            case KEY_NUM2: kUp = true; break;
            case KEY_NUM8: kDown = true; break;
            case KEY_NUM4: kLeft = true; break;
            case KEY_NUM6: kRight = true; break;
            case KEY_NUM1: kStrafeL = true; break;
            case KEY_NUM3: kStrafeR = true; break;
            case KEY_NUM5: kFire = true; break;
            case KEY_NUM7: world.interactOrNextLevel(); break;
            case KEY_NUM9: showAutomap = !showAutomap; break;
            case KEY_NUM0: world.triggerJump(); break;
            case KEY_STAR: world.switchWeapon(-1); break;
            case KEY_POUND: world.switchWeapon(1); break;
            default:
                if (action == UP) kUp = true;
                else if (action == DOWN) kDown = true;
                else if (action == LEFT) kLeft = true;
                else if (action == RIGHT) kRight = true;
                else if (action == FIRE) kFire = true;
                break;
        }
    }

    protected void keyReleased(int keyCode) {
        int action = 0;
        try {
            action = getGameAction(keyCode);
        } catch (Exception e) {}

        switch (keyCode) {
            case KEY_NUM2: kUp = false; break;
            case KEY_NUM8: kDown = false; break;
            case KEY_NUM4: kLeft = false; break;
            case KEY_NUM6: kRight = false; break;
            case KEY_NUM1: kStrafeL = false; break;
            case KEY_NUM3: kStrafeR = false; break;
            case KEY_NUM5: kFire = false; break;
            default:
                if (action == UP) kUp = false;
                else if (action == DOWN) kDown = false;
                else if (action == LEFT) kLeft = false;
                else if (action == RIGHT) kRight = false;
                else if (action == FIRE) kFire = false;
                break;
        }
    }

    private void clearHeldKeys() {
        kUp = kDown = kLeft = kRight = kStrafeL = kStrafeR = kFire = false;
    }

    private void handleMainMenuSelect() {
        switch (menuCursor) {
            case 0:
                world.resetInventory();
                world.loadLevel(selectedStartLevel);
                screenState = SCREEN_BRIEFING;
                if (sound != null) sound.playMusic(selectedStartLevel + 1);
                break;
            case 1:
                selectedStartLevel = (selectedStartLevel + 1) % GameWorld.NUM_LEVELS;
                world.loadLevel(selectedStartLevel);
                if (midlet != null) midlet.saveSettings();
                break;
            case 2:
                world.polyRenderer.cycleShadingMode();
                if (midlet != null) midlet.saveSettings();
                break;
            case 3:
                if (sound != null) {
                    sound.musicEnabled = !sound.musicEnabled;
                    sound.sfxEnabled = sound.musicEnabled;
                    if (sound.musicEnabled) sound.playMusic(0);
                    else sound.stopMusic();
                }
                if (midlet != null) midlet.saveSettings();
                break;
            case 4:
                screenState = SCREEN_CONTROLS;
                break;
            case 5:
                if (midlet != null) midlet.exitGame();
                break;
        }
    }

    private void handlePauseSelect() {
        switch (pauseCursor) {
            case 0:
                screenState = SCREEN_PLAYING;
                break;
            case 1:
                world.polyRenderer.cycleShadingMode();
                break;
            case 2:
                // Dismember next intact part on the lead monster so user can test dismemberment anytime
                if (world.monCount > 0) {
                    int mask = world.monPartMask[0];
                    if ((mask & Model3D.MASK_RIGHT_HAND) != 0) {
                        world.dismemberMonsterPart(0, Model3D.SUBMODEL_RIGHT_HAND);
                    } else if ((mask & Model3D.MASK_LEFT_HAND) != 0) {
                        world.dismemberMonsterPart(0, Model3D.SUBMODEL_LEFT_HAND);
                    } else if ((mask & Model3D.MASK_HEAD) != 0) {
                        world.dismemberMonsterPart(0, Model3D.SUBMODEL_HEAD);
                    }
                }
                screenState = SCREEN_PLAYING;
                break;
            case 3:
                world.levelCompleted = true;
                screenState = SCREEN_PLAYING;
                break;
            case 4:
                screenState = SCREEN_MAIN_MENU;
                if (sound != null) sound.playMusic(0);
                break;
            case 5:
                if (midlet != null) midlet.exitGame();
                break;
        }
    }
}
