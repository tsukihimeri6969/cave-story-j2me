import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import java.io.ByteArrayInputStream;


public final class Sfx implements Runnable {
    public static boolean enabled = true;

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
            synchronized (this) {
                if (quit) break;
                if (qTail == qHead) {
                    try { wait(300); } catch (InterruptedException e) {}
                }
                if (quit) break;
                if (qTail != qHead) { req = queue[qTail]; qTail = (qTail + 1) % queue.length; lastWork = System.currentTimeMillis(); }
                else if (System.currentTimeMillis() - lastWork > 6000 && !anyLoop()) {
                    thread = null;              // idle: let the thread end, post() starts a new one when needed
                    closeAll();
                    return;
                }
            }
            try {
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
            if (++failures >= 4) broken = true;
            return;
        }
        try {
            if (mode == 2) p.setLoopCount(-1);
            p.start();
        } catch (Throwable e) {
            try { p.close(); } catch (Throwable x) {}
            if (++failures >= 6) broken = true;
            return;
        }
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
