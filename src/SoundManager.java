import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import java.io.InputStream;

public class SoundManager {
    private Player bgmPlayer;
    private int currentTrack = -1;
    private int prevTrack = -1;

    // ---- sound effects ----
    private final Sfx sfx = new Sfx();
    private final long[] lastPlayed = new long[160];

    public void playSfx(int id) {
        if (id <= 0 || id >= 160 || !Sfx.enabled) return;
        long now = System.currentTimeMillis();
        // a phone cannot restart the same effect every frame: cap the rate per effect
        int gap = (id == 2 || id == 24) ? 70 : 40;
        if (now - lastPlayed[id] < gap) return;
        lastPlayed[id] = now;
        sfx.play(id);
    }

    public void loopSfx(int id) { if (id > 0 && id < 160) sfx.loop(id); }

    public void stopSfx(int id) { if (id > 0 && id < 160) sfx.stop(id); }

    public boolean toggleSfx() {
        Sfx.enabled = !Sfx.enabled;
        if (!Sfx.enabled) sfx.stopAll();
        return Sfx.enabled;
    }

    public void shutdown() {
        sfx.shutdown();
        stopMusicInternal();
    }

    public void playMusic(int trackId) {
        if (trackId == currentTrack) return;
        if (currentTrack > 0) {
            prevTrack = currentTrack;
        }
        stopMusicInternal();
        currentTrack = trackId;
        if (trackId <= 0) return;

        String path = (trackId < 10) ? ("/m0" + trackId + ".mid") : ("/m" + trackId + ".mid");
        String[] types = {"audio/midi", "audio/mid"};   // some Nokia firmwares only accept one spelling
        for (int k = 0; k < types.length; k++) {
            try {
                InputStream is = getClass().getResourceAsStream(path);
                if (is == null) return;
                bgmPlayer = Manager.createPlayer(is, types[k]);
                bgmPlayer.realize();
                bgmPlayer.prefetch();
                bgmPlayer.setLoopCount(-1);
                bgmPlayer.start();
                return;
            } catch (Exception e) {
                stopMusicInternal();
            }
        }
    }

    public void resumeMusic() {
        if (prevTrack > 0) {
            int target = prevTrack;
            prevTrack = -1;
            playMusic(target);
        }
    }

    public void stopMusic() {
        prevTrack = currentTrack;
        currentTrack = -1;
        stopMusicInternal();
    }

    private void stopMusicInternal() {
        if (bgmPlayer != null) {
            try {
                bgmPlayer.stop();
                bgmPlayer.close();
            } catch (Exception e) {}
            bgmPlayer = null;
        }
    }
}