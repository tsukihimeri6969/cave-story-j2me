import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;

public class CSMidlet extends MIDlet {
    private GameCanvas canvas;

    protected void startApp() {
        if (canvas == null) {
            try {
                canvas = new GameCanvas();
                Display.getDisplay(this).setCurrent(canvas);
                canvas.start();
            } catch (Throwable t) {
                // show the reason instead of a black screen / silent exit
                canvas = null;
                Runtime rt = Runtime.getRuntime();
                Form f = new Form("Start error");
                f.append(t.getClass().getName() + " " + t.getMessage()
                         + " free " + (rt.freeMemory() / 1024) + "K of " + (rt.totalMemory() / 1024) + "K");
                Display.getDisplay(this).setCurrent(f);
            }
        }
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.stop();
        }
    }
}
