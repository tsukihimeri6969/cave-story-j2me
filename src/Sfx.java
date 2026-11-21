import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import java.io.ByteArrayInputStream;

/**
 * Sound effects.  The original game synthesises its effects from PixTone parameters; prep_assets.py does that
 * once on the PC and stores x<N>.wav (8-bit mono) in the jar, because a phone without an FPU needs seconds to
 * synthesise the long ones.  Playback happens on a low-priority worker thread so the game loop never waits on
 * the phone's media stack.
 */
public final class Sfx implements Runnable {
    public static boolean enabled = true;
    /** set when the previous run never came back from the phone's media stack; sound effects then start disabled */
    public static boolean disabledByCrash;

    private static final String FLAG_STORE = "cssfx";
    private boolean armed;

    static {
        if (readFlag()) { enabled = false; disabledByCrash = true; }
    }

    private static boolean readFlag() {
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore(FLAG_STORE, false);
            return rs.getNumRecords() > 0 && rs.getRecord(1)[0] == 1;
        } catch (Throwable t) {
            return false;
        } finally {
            if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable t) {} }
        }
    }

    /** the game froze (or the media stack did): keep sound effects off on later runs until the player switches them on with * */
    public static void markCrash() { writeFlag(true); enabled = false; disabledByCrash = true; }
    public static void clearCrash() { writeFlag(false); disabledByCrash = false; }

    private static void writeFlag(boolean on) {
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore(FLAG_STORE, true);
            byte[] b = new byte[]{(byte) (on ? 1 : 0)};
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, 1);
            else rs.setRecord(1, b, 0, 1);
        } catch (Throwable t) {
        } finally {
            if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable t) {} }
        }
    }

    private static final int SLOTS = 3;
    private static final int CACHE_LIMIT = 40000;

    private byte[][] cache = new byte[160][];
    private int cacheBytes;

    private Player[] pl = new Player[SLOTS];
    private int[] plId = new int[SLOTS];
    private long[] plEnd = new long[SLOTS];
    private boolean[] plLoop = new boolean[SLOTS];
    private int nextSlot;
    private String mime;
    private int failures;
    private boolean broken;

    private final int[] queue = new int[32];
    private int qHead, qTail;
    private Thread thread;
    private boolean quit;

    // ---- public API (called from the game thread) -------------------------------------------------------

    public void play(int id) { post(id, 1); }
    public void loop(int id) { post(id, 2); }
    public void stop(int id) { post(id, 3); }
    public void stopAll() { post(0, 4); }

    public void shutdown() {
        synchronized (this) { quit = true; notify(); }
    }

    private synchronized void post(int id, int mode) {
        if (!enabled && mode != 3 && mode != 4) return;
        if (broken || id <= 0 || id >= 160 && mode != 4) return;
        int next = (qHead + 1) % queue.length;
        if (next == qTail) return;              // queue full: drop
        queue[qHead] = id | (mode << 8);
        qHead = next;
        if (thread == null) {
            thread = new Thread(this);
            try { thread.setPriority(Thread.MIN_PRIORITY); } catch (Throwable t) {}
            thread.start();
        }
        notify();
    }

    // ---- worker thread ------------------------------------------------------------------------------------

    public void run() {
        long lastWork = System.currentTimeMillis();
        while (!broken) {
            int req = -1;
            boolean idle = false;
            synchronized (this) {
                if (quit) break;
                if (qTail == qHead) {
                    try { wait(300); } catch (InterruptedException e) {}
                }
                if (quit) break;
                if (qTail != qHead) { req = queue[qTail]; qTail = (qTail + 1) % queue.length; lastWork = System.currentTimeMillis(); }
                else if (System.currentTimeMillis() - lastWork > 6000 && !anyLoop()) idle = true;
            }
            // everything below talks to the phone's media stack, which may block: never while holding the monitor
            try {
                if (idle) {
                    closeAll();
                    synchronized (this) {
                        if (qTail == qHead) { thread = null; return; }   // nothing arrived meanwhile: end the thread
                    }
                    lastWork = System.currentTimeMillis();
                    continue;
                }
                if (req >= 0) handle(req & 0xFF, req >> 8);
                housekeeping();
            } catch (Throwable t) {
                if (++failures >= 6) broken = true;
            }
        }
        closeAll();
        synchronized (this) { thread = null; }
    }

    private void handle(int id, int mode) {
        if (mode == 4) { closeAll(); return; }
        if (mode == 3) {
            for (int s = 0; s < SLOTS; s++) if (pl[s] != null && plId[s] == id) closeSlot(s);
            return;
        }
        if (!enabled) return;
        byte[] w = render(id);
        if (w == null) return;
        // same effect restarts, like the original
        int slot = -1;
        for (int s = 0; s < SLOTS; s++) if (pl[s] != null && plId[s] == id) { slot = s; break; }
        if (slot < 0) {
            for (int s = 0; s < SLOTS; s++) if (pl[s] == null) { slot = s; break; }
        }
        if (slot < 0) { slot = nextSlot; nextSlot = (nextSlot + 1) % SLOTS; }
        closeSlot(slot);
        if (!armed) { armed = true; writeFlag(true); }
        Player p = null;
        String[] types = (mime != null) ? new String[]{mime} : new String[]{"audio/x-wav", "audio/wav"};
        for (int k = 0; k < types.length && p == null; k++) {
            try {
                p = Manager.createPlayer(new ByteArrayInputStream(w), types[k]);
                p.realize();
                p.prefetch();
                mime = types[k];
            } catch (Throwable e) {
                if (p != null) { try { p.close(); } catch (Throwable x) {} }
                p = null;
            }
        }
        if (p == null) {
            if (armed) { armed = false; writeFlag(false); }      // it failed, but it did come back
            if (++failures >= 4) broken = true;
            return;
        }
        try {
            if (mode == 2) p.setLoopCount(-1);
            p.start();
        } catch (Throwable e) {
            try { p.close(); } catch (Throwable x) {}
            if (armed) { armed = false; writeFlag(false); }
            if (++failures >= 6) broken = true;
            return;
        }
        if (armed) { armed = false; writeFlag(false); }     // the media stack answered: not frozen
        pl[slot] = p;
        plId[slot] = id;
        plLoop[slot] = (mode == 2);
        plEnd[slot] = System.currentTimeMillis() + (w.length - 44) / 11 + 150;
    }

    private void housekeeping() {
        long now = System.currentTimeMillis();
        for (int s = 0; s < SLOTS; s++) {
            if (pl[s] != null && !plLoop[s] && now > plEnd[s]) closeSlot(s);
        }
    }

    private void closeSlot(int s) {
        if (pl[s] != null) {
            try { pl[s].stop(); } catch (Throwable e) {}
            try { pl[s].close(); } catch (Throwable e) {}
            pl[s] = null;
        }
    }

    private boolean anyLoop() { for (int s = 0; s < SLOTS; s++) if (pl[s] != null && plLoop[s]) return true; return false; }

    private void closeAll() { for (int s = 0; s < SLOTS; s++) closeSlot(s); }

    // ---- sample loading -------------------------------------------------------------------------------

    private byte[] render(int id) {
        byte[] c = cache[id];
        if (c != null) return c;
        byte[] w = null;
        java.io.InputStream is = null;
        try {
            is = getClass().getResourceAsStream("/x" + id + ".wav");
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int len = 0;
            while (true) {
                int n = is.read(buf, len, buf.length - len);
                if (n < 0) break;
                len += n;
                if (len == buf.length) {
                    byte[] nb = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, nb, 0, len);
                    buf = nb;
                }
            }
            w = new byte[len];
            System.arraycopy(buf, 0, w, 0, len);
        } catch (Throwable e) {
            return null;
        } finally {
            if (is != null) { try { is.close(); } catch (Throwable e) {} }
        }
        if (cacheBytes + w.length > CACHE_LIMIT) {
            for (int k = 0; k < cache.length; k++) cache[k] = null;
            cacheBytes = 0;
        }
        cache[id] = w;
        cacheBytes += w.length;
        return w;
    }
}
