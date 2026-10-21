package chasm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.microedition.lcdui.Display;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.VolumeControl;

/**
 * J2ME Audio & Vibration Manager (JSR-135 MMAPI + MIDP 2.0 Display.vibrate).
 * Handles General MIDI soundtrack playback and pre-cached 8kHz WAV sound effects,
 * with hardware-safe arbitration for Nokia S60 and Sony Ericsson feature phones.
 */
public final class SoundManager {
    public static SoundManager instance;

    public static final int SFX_RIFLE       = 0;
    public static final int SFX_SHOTGUN     = 1;
    public static final int SFX_SAW         = 2;
    public static final int SFX_ROCKET      = 3;
    public static final int SFX_MON_PAIN    = 4;
    public static final int SFX_MON_DIE     = 5;
    public static final int SFX_GIB         = 6;
    public static final int SFX_PLAYER_PAIN = 7;
    public static final int SFX_DOOR        = 8;
    public static final int SFX_PICKUP      = 9;
    public static final int SFX_CHAINGUN   = 10;
    public static final int SFX_CROSSBOW   = 11;
    public static final int SFX_SWITCH     = 12;
    public static final int SFX_MON_ALERT  = 13;
    public static final int NUM_SFX        = 14;

    public static void playSfx(int sfxId) {
        if (instance != null) {
            instance.playSfxInstance(sfxId);
        }
    }

    private static final String[] SFX_FILES = {
        "/sfx_supershot.wav", "/sfx_shotgun.wav", "/sfx_disk.wav", "/sfx_explode.wav",
        "/sfx_monpain.wav", "/sfx_mondie.wav", "/sfx_gib.wav", "/sfx_pain.wav",
        "/sfx_door.wav", "/sfx_pickup.wav", "/sfx_chaingun.wav", "/sfx_crossbow.wav",
        "/sfx_switch.wav", "/sfx_monalert.wav"
    };

    private static final String[] MUSIC_FILES = {
        "/mus_menu.mid", "/mus_lvl1.mid", "/mus_lvl2.mid", "/mus_lvl3.mid", "/mus_lvl4.mid"
    };

    private final Display display;
    public boolean musicEnabled = true;
    public boolean sfxEnabled = true;
    public boolean vibraEnabled = true;
    // On strict single-stream Nokia S60 devices, pauses MIDI briefly during SFX if needed
    public boolean s60SafeAudio = false;

    private byte[][] sfxData;
    private Player musicPlayer;
    private Player activeSfxPlayer;
    private final Player[] sfxPlayers = new Player[NUM_SFX];
    private final boolean[] sfxFailed = new boolean[NUM_SFX];
    private final Object sfxLock = new Object();
    private int pendingSfx = -1;
    private volatile boolean sfxRunning = false;
    private Thread sfxThread;
    private int currentMusicTrack = -1;
    private long lastSfxTime = 0L;

    public SoundManager(Display disp) {
        this.display = disp;
        instance = this;
        String platform = System.getProperty("microedition.platform");
        if (platform != null) {
            String p = platform.toUpperCase();
            if (p.indexOf("NOKIA") >= 0 && p.indexOf("SONYERICSSON") < 0) {
                s60SafeAudio = false; // Default simultaneous; user can toggle in menu
            }
        }
        preloadSfxBytes();
    }

    private void preloadSfxBytes() {
        sfxData = new byte[NUM_SFX][];
        byte[] tmp = new byte[1024];
        for (int i = 0; i < NUM_SFX; i++) {
            InputStream is = null;
            try {
                is = getClass().getResourceAsStream(SFX_FILES[i]);
                if (is != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
                    int n;
                    while ((n = is.read(tmp)) > 0) {
                        bos.write(tmp, 0, n);
                    }
                    sfxData[i] = bos.toByteArray();
                }
            } catch (Exception e) {
                // Ignore missing SFX
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ex) {}
                }
            }
        }
    }

    public synchronized void playMusic(int trackIdx) {
        currentMusicTrack = trackIdx;
        if (!musicEnabled || trackIdx < 0 || trackIdx >= MUSIC_FILES.length) {
            stopMusic();
            return;
        }
        stopMusic();
        InputStream is = null;
        try {
            is = getClass().getResourceAsStream(MUSIC_FILES[trackIdx]);
            if (is == null) return;
            musicPlayer = Manager.createPlayer(is, "audio/midi");
            musicPlayer.realize();
            musicPlayer.prefetch();
            musicPlayer.setLoopCount(-1);
            setPlayerVolume(musicPlayer, 75);
            musicPlayer.start();
        } catch (Exception e) {
            musicPlayer = null;
        }
    }

    public synchronized void stopMusic() {
        if (musicPlayer != null) {
            try {
                musicPlayer.stop();
            } catch (Exception e) {}
            try {
                musicPlayer.deallocate();
            } catch (Exception e) {}
            try {
                musicPlayer.close();
            } catch (Exception e) {}
            musicPlayer = null;
        }
    }

    public synchronized void pauseAll() {
        if (musicPlayer != null) {
            try { musicPlayer.stop(); } catch (Exception e) {}
        }
        closeActiveSfx();
    }

    public synchronized void resumeMusic() {
        if (musicEnabled && currentMusicTrack >= 0) {
            if (musicPlayer != null) {
                try {
                    musicPlayer.start();
                    return;
                } catch (Exception e) {}
            }
            playMusic(currentMusicTrack);
        }
    }

    /**
     * Non-blocking SFX request. The game thread only records the newest request and wakes the
     * audio worker; createPlayer()/realize() never run on the frame path, which removes the
     * shooting micro-stalls on S60 / SE devices.
     */
    public void playSfxInstance(int sfxId) {
        if (!sfxEnabled || sfxId < 0 || sfxId >= NUM_SFX) return;
        if (sfxData[sfxId] == null) return;

        long now = System.currentTimeMillis();
        if (now - lastSfxTime < 90L && sfxId != SFX_RIFLE && sfxId != SFX_SHOTGUN && sfxId != SFX_ROCKET) {
            return;
        }
        lastSfxTime = now;

        synchronized (sfxLock) {
            pendingSfx = sfxId;
            sfxLock.notify();
        }
        if (sfxThread == null) {
            startSfxWorker();
        }
    }

    private void startSfxWorker() {
        sfxRunning = true;
        sfxThread = new Thread(new Runnable() {
            public void run() {
                sfxWorkerLoop();
            }
        });
        sfxThread.start();
    }

    private void sfxWorkerLoop() {
        // Warm up every cached player once so the first shot of each type is not a decode stall.
        for (int i = 0; i < NUM_SFX && sfxRunning; i++) {
            ensureSfxPlayer(i);
        }
        while (sfxRunning) {
            int id;
            synchronized (sfxLock) {
                while (pendingSfx < 0 && sfxRunning) {
                    try {
                        sfxLock.wait();
                    } catch (InterruptedException ie) {
                        // keep waiting
                    }
                }
                id = pendingSfx;
                pendingSfx = -1;
            }
            if (id >= 0 && sfxRunning) {
                playSfxNow(id);
            }
        }
    }

    private Player ensureSfxPlayer(int id) {
        if (sfxPlayers[id] != null || sfxFailed[id]) return sfxPlayers[id];
        try {
            Player p = Manager.createPlayer(new ByteArrayInputStream(sfxData[id]), "audio/x-wav");
            p.realize();
            p.prefetch();
            setPlayerVolume(p, 95);
            sfxPlayers[id] = p;
        } catch (Exception e) {
            sfxFailed[id] = true;
            sfxPlayers[id] = null;
        }
        return sfxPlayers[id];
    }

    private void playSfxNow(int id) {
        Player p = ensureSfxPlayer(id);
        if (p == null) return;
        try {
            // One effect channel: silence the previous effect before starting this one.
            if (activeSfxPlayer != null && activeSfxPlayer != p) {
                activeSfxPlayer.stop();
            }
            p.stop();
            p.setMediaTime(0L);
            activeSfxPlayer = p;
            p.start();
        } catch (Exception e) {
            sfxFailed[id] = true;
            sfxPlayers[id] = null;
            activeSfxPlayer = null;
        }
    }

    private void closeActiveSfx() {
        if (activeSfxPlayer != null) {
            try { activeSfxPlayer.stop(); } catch (Exception e) {}
            activeSfxPlayer = null;
        }
    }

    public void vibrate(int durationMs) {
        if (!vibraEnabled || display == null) return;
        try {
            display.vibrate(durationMs);
        } catch (Exception e) {}
    }

    private static void setPlayerVolume(Player p, int level) {
        if (p == null) return;
        try {
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(level);
            }
        } catch (Exception e) {}
    }
}
