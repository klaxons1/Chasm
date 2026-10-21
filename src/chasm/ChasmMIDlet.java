package chasm;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * Main MIDlet entry point for Chasm: The Rift (J2ME Edition).
 * Targets Nokia S60 and Sony Ericsson phones (CLDC 1.1 / MIDP 2.0).
 */
public class ChasmMIDlet extends MIDlet {
    private static final String RMS_NAME = "ChasmRiftSave";

    private Display display;
    private SoundManager sound;
    private ChasmCanvas canvas;
    private boolean initialized = false;

    public ChasmMIDlet() {
    }

    protected void startApp() {
        if (!initialized) {
            initialized = true;
            display = Display.getDisplay(this);
            sound = new SoundManager(display);
            canvas = new ChasmCanvas(this, sound);
            loadSettings();
            enableNokiaBacklight();
            display.setCurrent(canvas);
            canvas.startLoop();
        } else {
            display.setCurrent(canvas);
            sound.resumeMusic();
        }
    }

    protected void pauseApp() {
        if (sound != null) {
            sound.pauseAll();
        }
        if (canvas != null && canvas.screenState == ChasmCanvas.SCREEN_PLAYING) {
            canvas.screenState = ChasmCanvas.SCREEN_PAUSED;
        }
    }

    protected void destroyApp(boolean unconditional) {
        saveSettings();
        if (canvas != null) {
            canvas.stopLoop();
        }
        if (sound != null) {
            sound.stopMusic();
        }
    }

    public void exitGame() {
        destroyApp(true);
        notifyDestroyed();
    }

    private void enableNokiaBacklight() {
        try {
            // Optional Nokia UI API backlight keep-alive via reflection so Sony Ericsson devices are unaffected
            Class dc = Class.forName("com.nokia.mid.ui.DeviceControl");
            if (dc != null) {
                com.nokia.mid.ui.DeviceControl.setLights(0, 100);
            }
        } catch (Throwable t) {
            // Non-Nokia device; ignore
        }
    }

    public void loadSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_NAME, true);
            if (rs.getNumRecords() >= 1) {
                byte[] data = rs.getRecord(1);
                if (data != null && data.length >= 4) {
                    canvas.setSelectedStartLevel(data[0]);
                    canvas.getRenderer3D().shadingMode = data[1] & 3;
                    sound.musicEnabled = (data[2] != 0);
                    sound.sfxEnabled = (data[3] != 0);
                }
            }
        } catch (Exception e) {
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception ex) {}
            }
        }
    }

    public void saveSettings() {
        if (canvas == null || sound == null) return;
        RecordStore rs = null;
        try {
            byte[] data = new byte[4];
            data[0] = (byte) canvas.getSelectedStartLevel();
            data[1] = (byte) canvas.getRenderer3D().shadingMode;
            data[2] = (byte) (sound.musicEnabled ? 1 : 0);
            data[3] = (byte) (sound.sfxEnabled ? 1 : 0);

            rs = RecordStore.openRecordStore(RMS_NAME, true);
            if (rs.getNumRecords() >= 1) {
                rs.setRecord(1, data, 0, data.length);
            } else {
                rs.addRecord(data, 0, data.length);
            }
        } catch (Exception e) {
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception ex) {}
            }
        }
    }
}
