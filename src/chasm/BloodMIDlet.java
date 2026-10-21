package chasm;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

/** J2ME entry point for the Blood MAP exploration build. */
public final class BloodMIDlet extends MIDlet {
    private Display display;
    private BloodCanvas canvas;

    protected void startApp() {
        if (canvas == null) {
            display = Display.getDisplay(this);
            String map = getAppProperty("Blood-Map");
            canvas = new BloodCanvas(map);
            display.setCurrent(canvas);
            canvas.start();
        } else {
            display.setCurrent(canvas);
        }
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.stop();
    }
}
