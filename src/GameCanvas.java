import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;

public class GameCanvas extends Canvas implements Runnable {
    private static final int MODE_INTRO    = 0;
    private static final int MODE_TITLE    = 1;
    private static final int MODE_PLAY     = 2;
    private static final int MODE_CAMP     = 3;
    private static final int MODE_TELEPORT = 4;
    private static final int MODE_CREDITS  = 5;

    // Cave Story runs its game logic at 50 Hz
    private static final int TICK_MS = 20;
    // <CMU0024 = CURLY = the "Cave Story" title theme (index into the exe's music table)
    private static final int TITLE_BGM = 24;

    private int gameMode = MODE_INTRO;
    private int introTimer = 0;
    private int titleCursor = 0;
    private boolean hasSaveFile = false;

    private volatile boolean running;
    private Image imgMyChar;
    private Image imgTileset;
    private Image imgNpcSym;
    private Image imgNpcRegu;
    private Image imgNpc1;
    private Image imgNpc2;
    private Image imgBullet;
    private Image imgCaret;
    private Image imgHud;          // 96x72 strip of TextBox: HUD digits, Lv, exp bar, HP case
    private Image imgArms;
    private Image imgTitle;
    private Image imgPixel;

    private TextBoxRenderer textBox;
    private SoundManager sound;

    // Compact NPC table loaded from npctbl.bin
    // (format written by prep_assets.py v2: 15 bytes per NPC type)
    private int[] npcBitsTbl;      // type flag word (0x20 shootable, 0x04 invulnerable, ...)
    private byte[] npcSurfTbl;
    private byte[] npcDmgTbl;
    private short[] npcLifeTbl;
    private byte[] npcExpTbl;
    private byte[] npcHitTbl;      // 4 bytes per type: front, top, back, bottom (pixels from centre)
    private byte[] npcViewTbl;     // 4 bytes per type: front, top, back, bottom
    // sprite rectangles per NPC type (npcrects.bin, extracted from the exe): l,t,r,b as shorts
    private short[] npcRcData;
    private int[] npcRcOff;            // index into npcRcData (in rects) per type
    private byte[] npcRcLeft, npcRcRight;

    private int curStageId = -1;
    private int spawnX;
    private int spawnY;
    private int mapW;
    private int mapH;
    private byte[] mapTiles;
    private byte[] tileAttrs = new byte[256];
    private Image imgBack;
    private int bkType;

    // Pre-allocated Entity Pool
    private static final int MAX_NPCS = 96;
    private int npcCount = 0;
    private boolean[] npcAlive = new boolean[MAX_NPCS];
    private int[] npcX = new int[MAX_NPCS];
    private int[] npcY = new int[MAX_NPCS];
    private int[] npcVx = new int[MAX_NPCS];
    private int[] npcVy = new int[MAX_NPCS];
    private int[] npcFlag = new int[MAX_NPCS];
    private int[] npcEvent = new int[MAX_NPCS];
    private int[] npcType = new int[MAX_NPCS];
    private int[] npcBits = new int[MAX_NPCS];
    private int[] npcHp = new int[MAX_NPCS];
    private int[] npcState = new int[MAX_NPCS];
    private int[] npcTimer = new int[MAX_NPCS];
    private int[] npcAnim = new int[MAX_NPCS];   // scripted frame + 1 (0 = automatic)
    private int[] npcDir = new int[MAX_NPCS];
    private int[] npcExp = new int[MAX_NPCS];
    private int[] npcDmg = new int[MAX_NPCS];

    // Pre-allocated Bullet Pool
    private static final int MAX_BULLETS = 16;
    private boolean[] bulAlive = new boolean[MAX_BULLETS];
    private int[] bulCode = new int[MAX_BULLETS];
    private int[] bulX = new int[MAX_BULLETS];
    private int[] bulY = new int[MAX_BULLETS];
    private int[] bulVx = new int[MAX_BULLETS];
    private int[] bulVy = new int[MAX_BULLETS];
    private int[] bulDir = new int[MAX_BULLETS];
    private int[] bulDmg = new int[MAX_BULLETS];
    private int[] bulLife = new int[MAX_BULLETS];
    private int[] bulLevel = new int[MAX_BULLETS];

    private boolean[] bulPierce = new boolean[MAX_BULLETS];
    private int[] bulAge = new int[MAX_BULLETS];
    private int[] bulAoe = new int[MAX_BULLETS];
    private int[] npcHitCool = new int[MAX_NPCS];
    private int spurCharge = 0;
    private int rndSeed = 0x2545;

    // tiny particle pool (smoke, sparks, bubbles): positions are world pixels * 8
    private static final int MAX_PART = 28;
    private int[] partX = new int[MAX_PART];
    private int[] partY = new int[MAX_PART];
    private int[] partVx = new int[MAX_PART];
    private int[] partVy = new int[MAX_PART];
    private int[] partLife = new int[MAX_PART];
    private int[] partCol = new int[MAX_PART];
    private int[] partSize = new int[MAX_PART];

    // weapon level thresholds (exp needed to go from level 1->2, 2->3, and the cap at level 3)
    private static final int[][] ARMS_REQ = {
        {0,0,0},{30,40,16},{10,20,10},{10,20,20},{30,40,10},{10,20,10},{10,20,10},
        {10,20,20},{10,20,10},{10,20,10},{30,60,10},{10,20,10},{10,20,10},{10,20,10}};

    // Pre-allocated Caret Pool
    private static final int MAX_CARETS = 16;
    private boolean[] carAlive = new boolean[MAX_CARETS];
    private int[] carX = new int[MAX_CARETS];
    private int[] carY = new int[MAX_CARETS];
    private int[] carTick = new int[MAX_CARETS];

    // Weapons & Inventory
    private static final int MAX_ARMS = 8;
    private int[] armsCode = new int[MAX_ARMS];
    private int[] armsLevel = new int[MAX_ARMS];
    private int[] armsExp = new int[MAX_ARMS];
    private int[] armsAmmo = new int[MAX_ARMS];
    private int[] armsMaxAmmo = new int[MAX_ARMS];
    private int armCount = 0;
    private int selectedArm = 0;
    private int[] items = new int[32];
    private int itemCount = 0;

    // Teleporter (<PS+ / <SLP) state
    private int[] permitIndex = new int[8];
    private int[] permitEvent = new int[8];
    private int permitCount = 0;
    private int teleportCursor = 0;

    // Ending Credits (<CRE) state
    private String creditTsc = "";
    private int creditPc = 0;
    private int creditWaitTicks = 0;
    private int creditIndentX = 8;
    private boolean creditPaused = false;
    private boolean creditEnded = false;
    private Image imgCreditIllust;
    private Image credBuffer;
    private Graphics credG;
    private static final int MAX_CRED_LINES = 16;
    private String[] credText = new String[MAX_CRED_LINES];
    private int[] credX = new int[MAX_CRED_LINES];
    private int[] credY = new int[MAX_CRED_LINES];
    private boolean[] credActive = new boolean[MAX_CRED_LINES];

    // Camp Menu state
    private int campSection = 0;
    private int campCursor = 0;

    // TSC Virtual Machine state
    private String headTsc = "";
    private String stageTsc = "";
    private String activeTsc = "";
    private boolean tscActive = false;
    private boolean tscKeyLock = false;
    private boolean tscWaitNod = false;
    private boolean tscWaitYn = false;
    private int tscYnEvent = 0;
    private int tscYnChoice = 0;
    private int tscWaitTicks = 0;
    private int tscPc = 0;
    private boolean[] flags = new boolean[8000];

    // Fade & Camera state
    private int fadeMode = 0;
    private int fadeStep = 0;
    private int fadeDir = 0;
    private int quakeTicks = 0;
    private int focusNpcEvent = 0;

    // Quote state (0x200 = 1 pixel)
    private int px;
    private int py;
    private int vx = 0;
    private int vy = 0;
    private int dir = 2;
    private int lookDir = 0;
    private int animTick = 0;
    private boolean grounded = false;
    private boolean inWater = false;
    private int airTicks = 1000;
    private int boostFuel = 0;
    private boolean boosting = false;
    private int boostDir = 1;       // 0 left, 1 up, 2 right, 3 down
    private int hp = 3;
    private int maxHp = 3;
    private int shockTicks = 0;
    private boolean playerHidden = false;   // <HMC / <SMC
    private int shootCooldown = 0;
    private int rngState = 12345;

    private boolean keyLeft;
    private boolean keyRight;
    private boolean keyUp;
    private boolean keyDown;
    private boolean keyJump;
    private boolean keyShoot;
    private boolean keyInspect;
    private boolean keyNextArm;
    private boolean keyCamp;

    private boolean prevLeft;
    private boolean prevRight;
    private boolean prevUp;
    private boolean prevDown;
    private boolean prevJump;
    private boolean prevShoot;
    private int spurBeep;
    private boolean prevInspect;
    private boolean prevNextArm;
    private boolean prevCamp;

    public GameCanvas() {
        setFullScreenMode(true);
        textBox = new TextBoxRenderer();
        sound = new SoundManager();

        try {
            imgMyChar = Image.createImage("/MyChar.png");
            imgNpcSym = Image.createImage("/NpcSym.png");
            imgBullet = Image.createImage("/Bullet.png");
            imgCaret  = Image.createImage("/Caret.png");
            try { imgHud = Image.createImage("/Hud.png"); } catch (Exception e) { imgHud = null; }
            imgArms   = Image.createImage("/Arms.png");
            loadNpcTable();
            loadNpcVoices();
            loadNpcRects();
            loadBulletIndex();
            headTsc = readUtf8Resource("/Head.tsc");
        } catch (Exception e) {
            e.printStackTrace();
        }
        enterIntro();
    }

    private void loadNpcTable() {
        try {
            InputStream nis = getClass().getResourceAsStream("/npctbl.bin");
            if (nis != null) {
                DataInputStream dis = new DataInputStream(nis);
                int n = dis.readUnsignedByte() | (dis.readUnsignedByte() << 8);
                npcBitsTbl = new int[n];
                npcSurfTbl = new byte[n];
                npcDmgTbl = new byte[n];
                npcLifeTbl = new short[n];
                npcExpTbl = new byte[n];
                npcHitTbl = new byte[n * 4];
                npcViewTbl = new byte[n * 4];
                for (int i = 0; i < n; i++) {
                    int b0 = dis.readUnsignedByte();
                    int b1 = dis.readUnsignedByte();
                    npcBitsTbl[i] = b0 | (b1 << 8);
                    npcSurfTbl[i] = dis.readByte();
                    npcDmgTbl[i] = dis.readByte();
                    int l0 = dis.readUnsignedByte();
                    int l1 = dis.readUnsignedByte();
                    npcLifeTbl[i] = (short) (l0 | (l1 << 8));
                    npcExpTbl[i] = dis.readByte();
                    dis.readFully(npcHitTbl, i * 4, 4);
                    dis.readFully(npcViewTbl, i * 4, 4);
                }
                dis.close();
            }
        } catch (Exception e) {
            npcBitsTbl = null;
            npcLifeTbl = null;
        }
    }

    private byte[] npcVoiceTbl;     // 2 bytes per type: hit sound, destroy sound

    private void loadNpcVoices() {
        try {
            InputStream vis = getClass().getResourceAsStream("/npcvoice.bin");
            if (vis != null) {
                DataInputStream dis = new DataInputStream(vis);
                int n = dis.readUnsignedByte() | (dis.readUnsignedByte() << 8);
                npcVoiceTbl = new byte[n * 2];
                dis.readFully(npcVoiceTbl);
                dis.close();
            }
        } catch (Exception e) { npcVoiceTbl = null; }
    }

    private int hitVoice(int t) {
        return (npcVoiceTbl != null && t >= 0 && t * 2 + 1 < npcVoiceTbl.length) ? (npcVoiceTbl[t * 2] & 0xFF) : 0;
    }

    private int destroyVoice(int t) {
        return (npcVoiceTbl != null && t >= 0 && t * 2 + 1 < npcVoiceTbl.length) ? (npcVoiceTbl[t * 2 + 1] & 0xFF) : 0;
    }

    private void loadNpcRects() {
        try {
            InputStream is = getClass().getResourceAsStream("/npcrects.bin");
            if (is == null) return;
            DataInputStream dis = new DataInputStream(is);
            byte[] all = new byte[is.available() > 0 ? is.available() : 16384];
            int len = 0;
            int r;
            while ((r = dis.read(all, len, all.length - len)) > 0) {
                len += r;
                if (len == all.length) {
                    byte[] bigger = new byte[all.length * 2];
                    System.arraycopy(all, 0, bigger, 0, len);
                    all = bigger;
                }
            }
            dis.close();
            int n = 361;
            npcRcLeft = new byte[n];
            npcRcRight = new byte[n];
            npcRcOff = new int[n];
            short[] tmp = new short[len / 2 + 8];
            int o = 0, cnt = 0;
            for (int t = 0; t < n && o + 1 < len; t++) {
                int nl = all[o] & 0xFF, nr = all[o + 1] & 0xFF;
                o += 2;
                npcRcLeft[t] = (byte) nl;
                npcRcRight[t] = (byte) nr;
                npcRcOff[t] = cnt;
                for (int k = 0; k < (nl + nr) * 4; k++) {
                    tmp[cnt++] = (short) ((all[o] & 0xFF) | ((all[o + 1] & 0xFF) << 8));
                    o += 2;
                }
            }
            npcRcData = new short[cnt];
            System.arraycopy(tmp, 0, npcRcData, 0, cnt);
        } catch (Exception e) {
            npcRcData = null;
        }
    }

    private boolean hasRects(int t) {
        return npcRcData != null && t >= 0 && t < npcRcLeft.length && npcRcLeft[t] > 0;
    }

    private boolean hasNpcTable(int t) {
        return npcLifeTbl != null && t >= 0 && t < npcLifeTbl.length;
    }

    private int tblBits(int t) {
        return hasNpcTable(t) ? npcBitsTbl[t] : 0;
    }

    /** k: 0 front, 1 top, 2 back, 3 bottom */
    private int tblHit(int t, int k) {
        return hasNpcTable(t) ? (npcHitTbl[t * 4 + k] & 0xFF) : 8;
    }

    // per-instance copies, so ported AI can switch sprite sheet or resize its hit box like the original
    private int[] npcSurf = new int[MAX_NPCS];
    private int[] npcHitI = new int[MAX_NPCS * 4];
    private int[] npcViewI = new int[MAX_NPCS * 4];

    private int nHit(int i, int k) { return npcHitI[i * 4 + k]; }
    private int nView(int i, int k) { return npcViewI[i * 4 + k]; }

    private void loadInstanceTables(int i) {
        int t = npcType[i];
        npcSurf[i] = (hasNpcTable(t)) ? (npcSurfTbl[t] & 0xFF) : 21;
        for (int k = 0; k < 4; k++) {
            npcHitI[i * 4 + k] = tblHit(t, k);
            npcViewI[i * 4 + k] = tblView(t, k);
        }
    }

    private int tblView(int t, int k) {
        return hasNpcTable(t) ? (npcViewTbl[t * 4 + k] & 0xFF) : 8;
    }

    /** shootable, damaging, killable enemy (data-driven from npc.tbl) */
    private boolean isHostile(int t) {
        if (!hasNpcTable(t)) return false;
        int b = npcBitsTbl[t];
        return (npcDmgTbl[t] & 0xFF) > 0 && (b & 0x20) != 0 && (b & 0x04) == 0 && npcLifeTbl[t] > 0;
    }

    /** damages on contact but cannot be killed (spikes, crushers, ...) */
    private boolean isHazard(int t) {
        if (!hasNpcTable(t) || isHostile(t)) return false;
        return (npcDmgTbl[t] & 0xFF) > 0 && t != 1;
    }

    /** "ignore solidity" bit: flies / floats */
    private boolean isFlyingEnemy(int t) {
        return (tblBits(t) & 0x08) != 0;
    }

    private String readUtf8Resource(String path) {
        try {
            InputStream is = getClass().getResourceAsStream(path);
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void enterIntro() {
        gameMode = MODE_INTRO;
        introTimer = 0;
        sound.stopMusic();
        textBox.setVisible(false);
        try { imgPixel = Image.createImage("/Pixel.png"); } catch (Exception e) {}
    }

    private void enterTitle() {
        imgPixel = null;
        imgCreditIllust = null;
        credBuffer = null;
        credG = null;
        mapTiles = null;
        imgTileset = null;
        imgNpcRegu = null;
        imgNpc1 = null;
        imgNpc2 = null;
        stageTsc = "";
        activeTsc = "";
        System.gc();

        if (imgTitle == null) {
            try { imgTitle = Image.createImage("/Title.png"); } catch (Exception e) {}
        }

        gameMode = MODE_TITLE;
        hasSaveFile = SaveManager.hasSave();
        titleCursor = hasSaveFile ? 1 : 0;
        textBox.setVisible(false);
        textBox.setFace(0);
        sound.playMusic(TITLE_BGM);
    }

    private void startNewGame() {
        imgTitle = null;
        imgPixel = null;
        System.gc();

        for (int i = 0; i < flags.length; i++) flags[i] = false;
        armCount = 0;
        selectedArm = 0;
        itemCount = 0;
        permitCount = 0;
        hp = 3;
        maxHp = 3;
        dir = 2;
        gameMode = MODE_PLAY;
        loadStage(13, 200, 10, 8);
    }

    private void continueSavedGame() {
        imgTitle = null;
        imgPixel = null;
        System.gc();

        int[] st = SaveManager.loadGame(armsCode, armsLevel, armsExp, armsAmmo, armsMaxAmmo,
                                        items, permitIndex, permitEvent, flags);
        if (st == null) {
            startNewGame();
            return;
        }
        int stg = st[0];
        int savedPx = st[1];
        int savedPy = st[2];
        dir = st[3];
        hp = st[4];
        maxHp = st[5];
        armCount = st[6];
        selectedArm = st[7];
        itemCount = st[8];
        permitCount = st[9];
        gameMode = MODE_PLAY;
        loadStage(stg, 90, savedPx >> 13, savedPy >> 13);
        px = savedPx;
        py = savedPy;
    }

    /** returns {map, tileset, pxa, npc1, npc2, bkType, bkName} */
    private String[] lookupStageInfo(int stageId) {
        try {
            InputStream sis = getClass().getResourceAsStream("/stages.bin");
            if (sis != null) {
                DataInputStream dis = new DataInputStream(sis);
                int count = dis.readUnsignedByte() | (dis.readUnsignedByte() << 8);
                if (stageId >= 0 && stageId < count) {
                    for (int i = 0; i < stageId; i++) {
                        int skipLen = dis.readUnsignedByte();
                        dis.skipBytes(skipLen);
                    }
                    int len = dis.readUnsignedByte();
                    byte[] b = new byte[len];
                    dis.readFully(b);
                    dis.close();
                    String rec = new String(b, "UTF-8").trim();
                    String[] out = new String[7];
                    int from = 0;
                    for (int k = 0; k < 7; k++) {
                        int p = (k < 6) ? rec.indexOf('|', from) : -1;
                        out[k] = (p < 0) ? rec.substring(from) : rec.substring(from, p);
                        from = p + 1;
                        if (p < 0) {
                            for (int j = k + 1; j < 7; j++) out[j] = "";
                            break;
                        }
                    }
                    if (out[0].length() > 0 && out[1].length() > 0) {
                        if (out[5].length() == 0) out[5] = "0";
                        return out;
                    }
                }
                dis.close();
            }
        } catch (Exception e) {}
        return new String[]{"Start", "PrtCave", "PrtCave", "NpcCemet", "Npc0", "4", "bk0"};
    }

    private void ensureNpcReguLoaded() {
        if (imgNpcRegu == null) {
            try { imgNpcRegu = Image.createImage("/NpcRegu.png"); } catch (Exception e) {}
        }
    }

    public void loadStage(int stageId, int eventNum, int tileX, int tileY) {
        try {
            String[] info = lookupStageInfo(stageId);
            String stg = info[0];
            String ts  = info[1];
            String pxa = info[2];
            String n1  = info[3];
            String n2  = info[4];
            String bkName = info[6];
            try { bkType = Integer.parseInt(info[5]); } catch (Exception e) { bkType = 4; }

            mapTiles = null;
            imgTileset = null;
            imgBack = null;
            imgNpcRegu = null;
            imgNpc1 = null;
            imgNpc2 = null;
            System.gc();
            stageTsc = "";
            activeTsc = "";
            for (int i = 0; i < MAX_BULLETS; i++) bulAlive[i] = false;
            for (int i = 0; i < MAX_CARETS; i++) carAlive[i] = false;
            System.gc();

            try { imgTileset = Image.createImage("/" + ts + ".png"); } catch (Exception e) {}
            if (imgTileset == null) imgTileset = Image.createImage("/PrtCave.png");
            if (bkName.length() > 0 && !bkName.equals("bk0") && !bkName.equals("Bk0")) {
                try { imgBack = Image.createImage("/" + bkName + ".png"); } catch (Exception e) {}
            }

            if (n1.equals("NpcRegu")) {
                ensureNpcReguLoaded();
                imgNpc1 = imgNpcRegu;
            } else if (!n1.equals("Npc0") && !n1.equals("NpcSym")) {
                try { imgNpc1 = Image.createImage("/" + n1 + ".png"); } catch (Exception e) {}
            }

            if (n2.equals("NpcRegu")) {
                ensureNpcReguLoaded();
                imgNpc2 = imgNpcRegu;
            } else if (n2.equals(n1)) {
                imgNpc2 = imgNpc1;
            } else if (!n2.equals("Npc0") && !n2.equals("NpcSym")) {
                try { imgNpc2 = Image.createImage("/" + n2 + ".png"); } catch (Exception e) {}
            }

            loadMap("/" + stg + ".pxm", "/" + pxa + ".pxa");
            loadPxe("/" + stg + ".pxe");
            stageTsc = readUtf8Resource("/" + stg + ".tsc");

            curStageId = stageId;
            focusNpcEvent = 0;
            spawnX = tileX * 16 * 0x200;
            spawnY = tileY * 16 * 0x200;
            px = spawnX;
            py = spawnY;
            vx = 0;
            vy = 0;
            grounded = false;

            if (eventNum > 0) {
                startTscEvent(eventNum);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetQuote() {
        playerHidden = false;
        px = spawnX;
        py = spawnY;
        vx = 0;
        vy = 0;
        grounded = false;
        fadeMode = 0;
        fadeStep = 0;
        if (hp <= 0) hp = maxHp;
    }

    private void loadMap(String pxmPath, String pxaPath) throws Exception {
        InputStream is = getClass().getResourceAsStream(pxmPath);
        if (is == null) is = getClass().getResourceAsStream("/Start.pxm");
        DataInputStream dis = new DataInputStream(is);
        dis.readInt();
        int wLo = dis.readUnsignedByte();
        int wHi = dis.readUnsignedByte();
        int hLo = dis.readUnsignedByte();
        int hHi = dis.readUnsignedByte();
        mapW = wLo | (wHi << 8);
        mapH = hLo | (hHi << 8);
        mapTiles = new byte[mapW * mapH];
        dis.readFully(mapTiles);
        dis.close();

        // never keep the previous stage's attributes if this stage's file is missing
        for (int i = 0; i < tileAttrs.length; i++) tileAttrs[i] = 0;
        InputStream ais = getClass().getResourceAsStream(pxaPath);
        if (ais != null) {
            DataInputStream ad = new DataInputStream(ais);
            try {
                ad.readFully(tileAttrs);   // InputStream.read(byte[]) may return fewer than 256 bytes
            } catch (Exception e) {}
            ad.close();
        }
    }

    /**
     * The first tick of many original NPC routines (act_no == 0) nudges the entity off its map position, e.g.
     * Kazuma at the computer moves 4 px left and 16 px down so that he sits in the chair.  Types that run the
     * ported original code get this for free; for every other type the same nudge is applied once at spawn.
     * Values are in pixels, taken from the case-0 block of each routine (NpcAct*.cpp).
     */
    private void applySpawnOffset(int i) {
        int t = npcType[i];
        if (isPorted(t)) return;
        int dx = 0, dy = 0;
        boolean right = npcDir[i] == 2;
        switch (t) {
            case 5: case 24: case 28: case 64: case 147: case 153: case 203: case 241: dy = 3; break;
            case 0: case 21: if (right) dy = 16; break;
            case 25: dx = 8; break;
            case 30: if (npcDir[i] != 0) dy = 16; break;                  // sleeping hermit
            case 43: dy = -16; break;
            case 62: case 92: dx = -4; dy = 16; break;                    // Kazuma / Sue at the computer
            case 91: case 100: case 234: dy = 16; break;
            case 102: dy = 8; break;
            case 194: dy = 4; break;
            case 222: dy = -8; break;
            case 300: dy = 6; break;
            case 349: if (!right) dx = 8; else dy = 16; break;
            case 114: case 212: case 246: dy = -4; break;
            case 138: dx = right ? -8 : 8; break;
            case 149: case 157: case 184: dx = 8; dy = 8; break;
            case 152: if (right) dy = 16; break;
            case 185: dy = 8; break;
            case 193: dx = 24; break;
            case 205: case 313: dy = 4; break;
            case 228: case 256: case 274: case 276: case 298: case 317: case 337: dy = -8; break;
            case 229: case 305: dy = -16; break;
            case 230: dx = -16; dy = -16; break;
            case 239: if (!right) { dx = 8; dy = 16; } break;
            case 247: dy = 6; break;
            case 261: case 262: dx = -1; dy = -2; break;
            case 268: dy = 8; break;
            case 307: dx = 1; dy = -2; break;
            case 322: if (!right) dy = 8; else dx = 8; break;
            case 326: dx = 16; dy = -8; break;
            default: return;
        }
        npcX[i] += dx * 0x200;
        npcY[i] += dy * 0x200;
    }

    private void initNpcStats(int i) {
        int t = npcType[i];
        npcState[i] = 0;
        npcTimer[i] = 0;
        npcAnim[i] = 0;
        resetNpcAi(i);
        loadInstanceTables(i);
        npcDir[i] = ((npcBits[i] & 0x1000) != 0) ? 2 : 0;
        if (npcLifeTbl != null && t >= 0 && t < npcLifeTbl.length) {
            int surf = npcSurfTbl[t] & 0xFF;
            if (surf == 23) ensureNpcReguLoaded();
            npcHp[i] = npcLifeTbl[t];
            npcDmg[i] = npcDmgTbl[t] & 0xFF;
            npcExp[i] = npcExpTbl[t] & 0xFF;
        } else {
            npcHp[i] = (t == 64 || t == 58 || t == 59) ? 3 : 0;
            npcDmg[i] = 2;
            npcExp[i] = 1;
        }
    }

    private void loadPxe(String pxePath) {
        npcCount = 0;
        for (int i = 0; i < MAX_NPCS; i++) npcAlive[i] = false;
        try {
            InputStream is = getClass().getResourceAsStream(pxePath);
            if (is == null) return;
            DataInputStream dis = new DataInputStream(is);
            dis.readInt();
            int c0 = dis.readUnsignedByte();
            int c1 = dis.readUnsignedByte();
            dis.readShort();
            int total = c0 | (c1 << 8);
            npcCount = (total > MAX_NPCS) ? MAX_NPCS : total;

            for (int i = 0; i < total; i++) {
                int tx = readLeShort(dis);
                int ty = readLeShort(dis);
                int flg = readLeShort(dis);
                int ev = readLeShort(dis);
                int tp = readLeShort(dis);
                int bt = readLeShort(dis);
                if (i < MAX_NPCS) {
                    npcAlive[i] = true;
                    npcX[i] = tx * 16 * 0x200;
                    npcY[i] = ty * 16 * 0x200;
                    npcVx[i] = 0;
                    npcVy[i] = 0;
                    npcFlag[i] = flg;
                    npcEvent[i] = ev;
                    npcType[i] = tp;
                    npcBits[i] = bt | tblBits(tp);   // like the original: table bits | entity bits
                    initNpcStats(i);
                    applySpawnOffset(i);
                    // like the original: the flag test happens once, when the stage is loaded
                    if ((npcBits[i] & 0x0800) != 0) {
                        npcAlive[i] = flg >= 0 && flg < flags.length && flags[flg];
                    } else if ((npcBits[i] & 0x4000) != 0) {
                        npcAlive[i] = !(flg >= 0 && flg < flags.length && flags[flg]);
                    }
                }
            }
            dis.close();
        } catch (Exception e) {
            npcCount = 0;
        }
    }

    private int readLeShort(DataInputStream dis) throws Exception {
        int lo = dis.readUnsignedByte();
        int hi = dis.readUnsignedByte();
        return (lo | (hi << 8));
    }

    private int nextRand() {
        rngState = (rngState * 1103515245 + 12345) & 0x7FFFFFFF;
        return rngState;
    }

    /** what a destroyed enemy leaves behind: a heart, missile ammo (only if Quote owns a launcher) or XP crystals */
    private void dropLoot(int x, int y, int exp) {
        if (exp == 0) return;
        switch (rnd(1, 5)) {
            case 1:
                spawnPickup(87, x, y, exp > 6 ? 6 : 2);
                break;
            case 2:
                if (hasMissileLauncher() && spawnPickup(86, x, y, exp > 6 ? 3 : 1)) break;
                spawnExpPiles(x, y, exp);
                break;
            default:
                spawnExpPiles(x, y, exp);
                break;
        }
    }

    private boolean hasMissileLauncher() {
        for (int i = 0; i < armCount; i++) if (armsCode[i] == 5 || armsCode[i] == 10) return true;
        return false;
    }

    private boolean spawnPickup(int type, int x, int y, int val) {
        int n = setNpc(type, x, y, 0, 0, 0, -1);
        if (n < 0) return false;
        npcExp[n] = val;
        npcC1[n] = 0;
        return true;
    }

    /** XP is handed out in crystals worth 20, 5 or 1, like SetExpObjects */
    private void spawnExpPiles(int x, int y, int exp) {
        while (exp > 0) {
            int sub = (exp >= 20) ? 20 : (exp >= 5 ? 5 : 1);
            exp -= sub;
            spawnDrop(x, y, 1, sub);
        }
    }

    private void addMissileAmmo(int val) {
        int a = -1;
        for (int i = 0; i < armCount && a < 0; i++) if (armsCode[i] == 5) a = i;
        for (int i = 0; i < armCount && a < 0; i++) if (armsCode[i] == 10) a = i;
        if (a < 0) return;
        armsAmmo[a] += val;
        if (armsAmmo[a] > armsMaxAmmo[a]) armsAmmo[a] = armsMaxAmmo[a];
    }

    /** heart (87) and missile (86) pickups: animation, lifetime when dropped, and the pickup itself */
    private void updatePickup(int i, int t) {
        int ex = npcExp[i];
        if (npcDir[i] == 0) {
            if (++npcAniWait[i] > 2) { npcAniWait[i] = 0; npcAniNo[i]++; }
        }
        if (npcAniNo[i] > 1) npcAniNo[i] = 0;
        int base = (t == 87 && ex == 6) ? 2 : 0;        // (big missiles use the 'right' rect list, see drawNpcByRect)
        npcAnim[i] = npcAniNo[i] + base + 1;
        if (npcDir[i] == 0) npcC1[i]++;
        if (npcC1[i] > 550) { npcAlive[i] = false; return; }
        if (npcC1[i] > 500 && ((npcC1[i] >> 1) & 1) == 1) npcAnim[i] = -1;
        if (npcC1[i] > 547 && t == 87) npcAnim[i] = 5;
        int nx = (npcX[i] >> 9) + 8, ny = (npcY[i] >> 9) + 8;
        int qx = (px >> 9) + 8, qy = (py >> 9) + 8;
        if (!playerHidden && Math.abs(qx - nx) <= 12 && Math.abs(qy - ny) <= 12) {
            if (t == 87) {
                sfx(20);
                hp += ex;
                if (hp > maxHp) hp = maxHp;
            } else {
                sfx(42);
                addMissileAmmo(ex);
            }
            npcAlive[i] = false;
        }
    }

    private void spawnDrop(int wx, int wy, int type, int expVal) {
        for (int i = 0; i < MAX_NPCS; i++) {
            if (!npcAlive[i]) {
                npcAlive[i] = true;
                npcX[i] = wx;
                npcY[i] = wy;
                npcVx[i] = ((nextRand() & 0x1FF) - 0x100);
                npcVy[i] = -0x300;
                npcFlag[i] = 0;
                npcEvent[i] = 0;
                npcType[i] = type;
                npcBits[i] = 0;
                npcHp[i] = 0;
                npcState[i] = 0;
                npcTimer[i] = 0;
                npcExp[i] = expVal;
                if (i >= npcCount) npcCount = i + 1;
                return;
            }
        }
    }

    /** <SSS: ambient loops (wind / river) built from the same effects the original loops */
    private void startNoise(int n) {
        stopNoise();
        if (n == 1) { sfxLoop(40); sfxLoop(41); }
        else if (n == 2) sfxLoop(58);
    }

    private void stopNoise() { sfxStop(40); sfxStop(41); sfxStop(58); }

    private void spawnCaret(int wx, int wy) {
        for (int i = 0; i < MAX_CARETS; i++) {
            if (!carAlive[i]) {
                carAlive[i] = true;
                carX[i] = wx;
                carY[i] = wy;
                carTick[i] = 0;
                return;
            }
        }
    }

    private int parseTscInt(String src, int offset) {
        int val = 0;
        for (int i = 0; i < 4 && offset + i < src.length(); i++) {
            char c = src.charAt(offset + i);
            if (c >= '0' && c <= '9') val = val * 10 + (c - '0');
        }
        return val;
    }

    public boolean startTscEvent(int ev) {
        if (startTscEventFrom(stageTsc, ev)) return true;
        return startTscEventFrom(headTsc, ev);
    }

    private boolean startTscEventFrom(String src, int ev) {
        if (src == null || src.length() == 0) return false;
        StringBuffer sb = new StringBuffer("#");
        if (ev < 1000) sb.append('0');
        if (ev < 100) sb.append('0');
        if (ev < 10) sb.append('0');
        sb.append(ev);

        int idx = src.indexOf(sb.toString());
        if (idx == -1) return false;
        activeTsc = src;
        tscPc = idx + 5;
        while (tscPc < activeTsc.length() && (activeTsc.charAt(tscPc) == '\r' || activeTsc.charAt(tscPc) == '\n')) {
            tscPc++;
        }
        tscActive = true;
        tscWaitNod = false;
        tscWaitYn = false;
        textBox.setWaitingNod(false);
        textBox.setWaitingYn(false, 0);
        tscWaitTicks = 0;
        return true;
    }

    private void addPermitStage(int idx, int ev) {
        for (int i = 0; i < permitCount; i++) {
            if (permitIndex[i] == idx) {
                permitEvent[i] = ev;
                return;
            }
        }
        if (permitCount < 8) {
            permitIndex[permitCount] = idx;
            permitEvent[permitCount] = ev;
            permitCount++;
        }
    }

    private void openTeleporterMenu() {
        String selTsc = readUtf8Resource("/StageSelect.tsc");
        if (permitCount == 0) {
            if (!startTscEventFrom(selTsc, 0)) {
                tscActive = false;
                tscKeyLock = false;
            }
            return;
        }
        gameMode = MODE_TELEPORT;
        teleportCursor = 0;
        textBox.markCampDirty();
        previewTeleportSlot(selTsc);
    }

    private void previewTeleportSlot(String selTsc) {
        if (teleportCursor >= 0 && teleportCursor < permitCount && selTsc.length() > 0) {
            int ev = permitEvent[teleportCursor];
            if (startTscEventFrom(selTsc, ev)) {
                for (int i = 0; i < 120 && tscActive && !tscWaitNod; i++) {
                    updateTsc();
                }
                tscActive = false;
            }
        }
    }

    private void updateTeleportMenu() {
        if (keyInspect && !prevInspect || (keyCamp && !prevCamp)) {
            gameMode = MODE_PLAY;
            textBox.setVisible(false);
            String selTsc = readUtf8Resource("/StageSelect.tsc");
            if (!startTscEventFrom(selTsc, 0)) {
                tscActive = false;
                tscKeyLock = false;
            }
            return;
        }

        boolean moved = false;
        if (keyLeft && !prevLeft && permitCount > 1) {
            teleportCursor = (teleportCursor + permitCount - 1) % permitCount;
            moved = true;
        } else if (keyRight && !prevRight && permitCount > 1) {
            teleportCursor = (teleportCursor + 1) % permitCount;
            moved = true;
        }

        if (moved) {
            textBox.markCampDirty();
            previewTeleportSlot(readUtf8Resource("/StageSelect.tsc"));
        }

        if (keyJump && !prevJump) {
            int warpEv = permitEvent[teleportCursor] + 1000;
            gameMode = MODE_PLAY;
            textBox.setVisible(false);
            String selTsc = readUtf8Resource("/StageSelect.tsc");
            if (!startTscEventFrom(selTsc, warpEv)) {
                tscActive = false;
                tscKeyLock = false;
            }
        }
    }

    private void startCredits() {
        mapTiles = null;
        imgTileset = null;
        imgNpcRegu = null;
        imgNpc1 = null;
        imgNpc2 = null;
        stageTsc = "";
        activeTsc = "";
        imgCreditIllust = null;
        System.gc();

        if (credBuffer == null) {
            credBuffer = Image.createImage(168, 240);
            credG = credBuffer.getGraphics();
        }
        for (int i = 0; i < MAX_CRED_LINES; i++) {
            credActive[i] = false;
            credText[i] = "";
        }

        creditTsc = readUtf8Resource("/Credit.tsc");
        creditPc = 0;
        creditWaitTicks = 0;
        creditIndentX = 8;
        creditPaused = false;
        creditEnded = false;

        tscActive = false;
        tscKeyLock = false;
        textBox.setVisible(false);
        fadeMode = 0;
        fadeStep = 0;
        gameMode = MODE_CREDITS;
    }

    private void loadCreditIllustration(int id) {
        imgCreditIllust = null;
        System.gc();
        if (id <= 0) return;
        String path = (id < 10) ? ("/Credit0" + id + ".png") : ("/Credit" + id + ".png");
        try {
            imgCreditIllust = Image.createImage(path);
        } catch (Exception e) {
            imgCreditIllust = null;
        }
    }

    private void pushCreditLine(String txt, int xPos) {
        for (int i = 0; i < MAX_CRED_LINES; i++) {
            if (!credActive[i]) {
                credActive[i] = true;
                credText[i] = txt;
                credX[i] = xPos;
                credY[i] = 240;
                return;
            }
        }
    }

    private void jumpCreditLabel(int labelNum) {
        StringBuffer sb = new StringBuffer("l");
        if (labelNum < 1000) sb.append('0');
        if (labelNum < 100) sb.append('0');
        if (labelNum < 10) sb.append('0');
        sb.append(labelNum);
        int idx = creditTsc.indexOf(sb.toString());
        if (idx != -1) {
            creditPc = idx + 5;
            while (creditPc < creditTsc.length() &&
                   (creditTsc.charAt(creditPc) == '\r' || creditTsc.charAt(creditPc) == '\n')) {
                creditPc++;
            }
        }
    }

    private void updateCredits() {
        if (!creditPaused) {
            for (int i = 0; i < MAX_CRED_LINES; i++) {
                if (credActive[i]) {
                    credY[i] -= 1;
                    if (credY[i] < -18) {
                        credActive[i] = false;
                    }
                }
            }
        }

        if (creditEnded || creditPaused) {
            if ((keyJump && !prevJump) || (keyShoot && !prevShoot) || (keyInspect && !prevInspect)) {
                creditTsc = "";
                enterTitle();
                return;
            }
        }

        if (creditWaitTicks > 0) {
            creditWaitTicks--;
            return;
        }

        if (creditEnded || creditPc >= creditTsc.length()) {
            creditEnded = true;
            return;
        }

        int eol = creditTsc.indexOf('\n', creditPc);
        String line;
        if (eol == -1) {
            line = creditTsc.substring(creditPc);
            creditPc = creditTsc.length();
        } else {
            line = creditTsc.substring(creditPc, eol);
            creditPc = eol + 1;
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }

        while (line.length() > 0) {
            if (line.startsWith("<CMU") && line.length() >= 8) {
                sound.playMusic(parseTscInt(line, 4));
                line = line.substring(8);
            } else if (line.startsWith("<SIL") && line.length() >= 8) {
                loadCreditIllustration(parseTscInt(line, 4));
                line = line.substring(8);
            } else if (line.startsWith("<CIL") && line.length() >= 4) {
                loadCreditIllustration(0);
                line = line.substring(4);
            } else if (line.startsWith("<END") || line.startsWith("~")) {
                creditEnded = true;
                return;
            } else if (line.startsWith("/")) {
                creditPaused = true;
                line = line.substring(1);
            } else if (line.startsWith("-") && line.length() >= 5) {
                creditWaitTicks = parseTscInt(line, 1) / 2;
                return;
            } else if (line.startsWith("+") && line.length() >= 5) {
                creditIndentX = parseTscInt(line, 1) / 2;
                if (creditIndentX > 120) creditIndentX = 8;
                line = line.substring(5);
            } else if (line.startsWith("!") && line.length() >= 5) {
                sound.playMusic(parseTscInt(line, 1));
                line = line.substring(5);
            } else if (line.startsWith("l") && line.length() >= 5 &&
                       line.charAt(1) >= '0' && line.charAt(1) <= '9') {
                line = line.substring(5);
            } else if (line.startsWith("j") && line.length() >= 5 &&
                       line.charAt(1) >= '0' && line.charAt(1) <= '9') {
                jumpCreditLabel(parseTscInt(line, 1));
                return;
            } else if (line.startsWith("f") && line.length() >= 10 && line.charAt(5) == ':') {
                int flg = parseTscInt(line, 1);
                int lbl = parseTscInt(line, 6);
                if (flg >= 0 && flg < flags.length && flags[flg]) {
                    jumpCreditLabel(lbl);
                    return;
                }
                line = line.substring(10);
            } else {
                break;
            }
        }

        if (line.length() > 0) {
            pushCreditLine(line, creditIndentX);
            creditWaitTicks = 18;
        }
    }

    private void addWeapon(int code, int maxAmmo) {
        for (int i = 0; i < armCount; i++) {
            if (armsCode[i] == code) {
                armsMaxAmmo[i] += maxAmmo;
                armsAmmo[i] = armsMaxAmmo[i];
                return;
            }
        }
        if (armCount < MAX_ARMS) {
            armsCode[armCount] = code;
            armsLevel[armCount] = 1;
            armsExp[armCount] = 0;
            armsMaxAmmo[armCount] = maxAmmo;
            armsAmmo[armCount] = maxAmmo;
            selectedArm = armCount;
            armCount++;
        }
    }

    private void removeWeapon(int code) {
        for (int i = 0; i < armCount; i++) {
            if (armsCode[i] == code) {
                for (int j = i; j < armCount - 1; j++) {
                    armsCode[j] = armsCode[j + 1];
                    armsLevel[j] = armsLevel[j + 1];
                    armsExp[j] = armsExp[j + 1];
                    armsAmmo[j] = armsAmmo[j + 1];
                    armsMaxAmmo[j] = armsMaxAmmo[j + 1];
                }
                armCount--;
                if (selectedArm >= armCount && armCount > 0) selectedArm = armCount - 1;
                return;
            }
        }
    }

    private int[] armsReq(int code) {
        return ARMS_REQ[(code >= 0 && code < ARMS_REQ.length) ? code : 2];
    }

    private void addWeaponExp(int amount) {
        if (armCount == 0) return;
        int code = armsCode[selectedArm];
        int[] r = armsReq(code);
        int lv = armsLevel[selectedArm];
        if (lv < 1) lv = armsLevel[selectedArm] = 1;
        armsExp[selectedArm] += amount;
        while (lv < 3 && armsExp[selectedArm] >= r[lv - 1]) {
            armsExp[selectedArm] -= r[lv - 1];
            lv++;
            armsLevel[selectedArm] = lv;
            if (code != 13) sfx(27);
            spawnCaret(px, py - (8 << 9));
        }
        if (lv >= 3 && armsExp[selectedArm] > r[2]) armsExp[selectedArm] = r[2];
    }

    private void addItem(int code) {
        for (int i = 0; i < itemCount; i++) {
            if (items[i] == code) return;
        }
        if (itemCount < items.length) {
            items[itemCount++] = code;
        }
    }

    private void removeItem(int code) {
        for (int i = 0; i < itemCount; i++) {
            if (items[i] == code) {
                for (int j = i; j < itemCount - 1; j++) items[j] = items[j + 1];
                itemCount--;
                return;
            }
        }
    }

    private boolean hasItem(int code) {
        for (int i = 0; i < itemCount; i++) {
            if (items[i] == code) return true;
        }
        return false;
    }

    private void updateTsc() {
        if (fadeMode == 1) {
            fadeStep++;
            if (fadeStep >= 16) {
                fadeStep = 16;
                fadeMode = 2;
            }
            return;
        } else if (fadeMode == 3) {
            fadeStep--;
            if (fadeStep <= 0) {
                fadeStep = 0;
                fadeMode = 0;
            }
            return;
        }

        if (!tscActive) return;

        if (tscWaitTicks > 0) {
            tscWaitTicks--;
            return;
        }

        if (tscWaitYn) {
            if (keyLeft && !prevLeft && tscYnChoice != 0) {
                sfx(1);
                tscYnChoice = 0;
                textBox.setWaitingYn(true, 0);
            } else if (keyRight && !prevRight && tscYnChoice != 1) {
                sfx(1);
                tscYnChoice = 1;
                textBox.setWaitingYn(true, 1);
            } else if ((keyJump && !prevJump) || (keyInspect && !prevInspect)) {
                sfx(18);
                tscWaitYn = false;
                textBox.setWaitingYn(false, 0);
                if (tscYnChoice == 1) {
                    startTscEvent(tscYnEvent);
                }
            }
            return;
        }

        if (tscWaitNod) {
            if ((keyJump && !prevJump) || (keyInspect && !prevInspect)) {
                tscWaitNod = false;
                textBox.setWaitingNod(false);
            }
            return;
        }

        int safety = 0;
        while (tscActive && tscPc < activeTsc.length() && safety++ < 48) {
            char c = activeTsc.charAt(tscPc);
            if (c == '#') {
                tscActive = false;
                tscKeyLock = false;
                textBox.setVisible(false);
                break;
            }
            if (c != '<') {
                tscPc++;
                if (textBox.isVisible()) {
                    textBox.appendChar(c);
                    if (c != ' ' && c != '\n' && c != '\r') sfx(2);
                    break;
                }
                continue;
            }

            if (tscPc + 3 >= activeTsc.length()) {
                tscActive = false;
                break;
            }
            String cmd = activeTsc.substring(tscPc + 1, tscPc + 4);
            tscPc += 4;

            if (cmd.equals("MSG") || cmd.equals("MS2") || cmd.equals("MS3")) {
                textBox.clear();
                textBox.setVisible(true);
            } else if (cmd.equals("CLO")) {
                textBox.setVisible(false);
            } else if (cmd.equals("CLR")) {
                textBox.clear();
            } else if (cmd.equals("FAC")) {
                textBox.setFace(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("NOD")) {
                tscWaitNod = true;
                textBox.setWaitingNod(true);
                break;
            } else if (cmd.equals("SOU")) {
                sfx(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("SSS")) {
                startNoise(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("CSS")) {
                stopNoise();
            } else if (cmd.equals("YNJ")) {
                sfx(5);
                tscYnEvent = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                tscWaitYn = true;
                tscYnChoice = 0;
                textBox.setWaitingYn(true, 0);
                break;
            } else if (cmd.equals("FAO")) {
                fadeDir = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                fadeMode = 1;
                fadeStep = 0;
                break;
            } else if (cmd.equals("FAI")) {
                fadeDir = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                fadeMode = 3;
                fadeStep = 16;
                break;
            } else if (cmd.equals("FOB")) {
                fadeMode = 2;
                fadeStep = 16;
            } else if (cmd.equals("KEY") || cmd.equals("PRI")) {
                tscKeyLock = true;
                vx = 0;
            } else if (cmd.equals("FRE") || cmd.equals("END")) {
                tscKeyLock = false;
                if (cmd.equals("END")) {
                    tscActive = false;
                    textBox.setVisible(false);
                    break;
                }
            } else if (cmd.equals("WAI")) {
                tscWaitTicks = parseTscInt(activeTsc, tscPc) / 2;
                tscPc += 4;
                break;
            } else if (cmd.equals("CMU")) {
                sound.playMusic(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("FMU")) {
                sound.stopMusic();
            } else if (cmd.equals("RMU")) {
                sound.resumeMusic();
            } else if (cmd.equals("PS+")) {
                int idx = parseTscInt(activeTsc, tscPc);
                int ev  = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                addPermitStage(idx, ev);
            } else if (cmd.equals("SLP")) {
                openTeleporterMenu();
                break;
            } else if (cmd.equals("CRE")) {
                startCredits();
                break;
            } else if (cmd.equals("SVP")) {
                SaveManager.saveGame(curStageId, px, py, dir, hp, maxHp,
                                     armCount, selectedArm, armsCode, armsLevel,
                                     armsExp, armsAmmo, armsMaxAmmo, itemCount, items,
                                     permitCount, permitIndex, permitEvent, flags);
            } else if (cmd.equals("LDP")) {
                continueSavedGame();
                break;
            } else if (cmd.equals("FL+")) {
                int f = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                if (f >= 0 && f < flags.length) flags[f] = true;
            } else if (cmd.equals("FL-")) {
                int f = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                if (f >= 0 && f < flags.length) flags[f] = false;
            } else if (cmd.equals("FLJ")) {
                int f = parseTscInt(activeTsc, tscPc);
                int ev = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                if (f >= 0 && f < flags.length && flags[f]) {
                    startTscEvent(ev);
                    break;
                }
            } else if (cmd.equals("IT+")) {
                addItem(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("IT-")) {
                removeItem(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("ITJ")) {
                int it = parseTscInt(activeTsc, tscPc);
                int ev = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                if (hasItem(it)) {
                    startTscEvent(ev);
                    break;
                }
            } else if (cmd.equals("AM+")) {
                int code = parseTscInt(activeTsc, tscPc);
                int ammo = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                addWeapon(code, ammo);
            } else if (cmd.equals("AM-")) {
                removeWeapon(parseTscInt(activeTsc, tscPc));
                tscPc += 4;
            } else if (cmd.equals("TAM")) {
                int oldC = parseTscInt(activeTsc, tscPc);
                int newC = parseTscInt(activeTsc, tscPc + 5);
                int ammo = parseTscInt(activeTsc, tscPc + 10);
                tscPc += 14;
                removeWeapon(oldC);
                addWeapon(newC, ammo);
            } else if (cmd.equals("ML+")) {
                int add = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                maxHp += add;
                hp += add;
            } else if (cmd.equals("LI+")) {
                int add = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                hp += add;
                if (hp > maxHp) hp = maxHp;
            } else if (cmd.equals("AE+")) {
                for (int i = 0; i < armCount; i++) armsAmmo[i] = armsMaxAmmo[i];
            } else if (cmd.equals("MYD")) {
                int d = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                if (d == 0 || d == 2) dir = d;
            } else if (cmd.equals("MYB")) {
                int d = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                vy = -0x200;
                vx = (d == 0) ? 0x200 : -0x200;
            } else if (cmd.equals("QUA")) {
                quakeTicks = parseTscInt(activeTsc, tscPc) / 2;
                tscPc += 4;
            } else if (cmd.equals("FON")) {
                focusNpcEvent = parseTscInt(activeTsc, tscPc);
                tscPc += 9;
            } else if (cmd.equals("FOM")) {
                focusNpcEvent = 0;
                tscPc += 4;
            } else if (cmd.equals("DNP")) {
                int ev = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcEvent[i] == ev) vanishNpc(i);
                }
            } else if (cmd.equals("ANP")) {
                int ev = parseTscInt(activeTsc, tscPc);
                int st = parseTscInt(activeTsc, tscPc + 5);
                int dr = parseTscInt(activeTsc, tscPc + 10);
                tscPc += 14;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcEvent[i] == ev) {      // the original changes only the first live match
                        npcState[i] = st;
                        npcTimer[i] = 0;
                        npcDir[i] = resolveTscDir(i, dr);
                        break;
                    }
                }
            } else if (cmd.equals("CNP")) {
                int ev = parseTscInt(activeTsc, tscPc);
                int tp = parseTscInt(activeTsc, tscPc + 5);
                int dr = parseTscInt(activeTsc, tscPc + 10);
                tscPc += 14;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcEvent[i] == ev) {
                        npcBits[i] &= ~0x80FF;                   // like the original: clear the table flags, then add the new type's
                        npcType[i] = tp;
                        npcBits[i] |= tblBits(tp);
                        initNpcStats(i);
                        npcDir[i] = resolveTscDir(i, dr);
                        npcVx[i] = 0;
                        npcVy[i] = 0;
                        npcAnim[i] = 0;
                        applySpawnOffset(i);
                        if (isPorted(tp)) actNpcPort(i, tp);     // the original runs the new AI once straight away
                    }
                }
            } else if (cmd.equals("EVE")) {
                int ev = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                startTscEvent(ev);
                break;
            } else if (cmd.equals("MOV")) {
                int mx = parseTscInt(activeTsc, tscPc);
                int my = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                px = mx * 16 * 0x200;
                py = my * 16 * 0x200;
                vx = 0;
                vy = 0;
            } else if (cmd.equals("TRA")) {
                int stg = parseTscInt(activeTsc, tscPc);
                int ev  = parseTscInt(activeTsc, tscPc + 5);
                int mx  = parseTscInt(activeTsc, tscPc + 10);
                int my  = parseTscInt(activeTsc, tscPc + 15);
                tscPc += 19;
                tscActive = false;
                tscKeyLock = false;
                textBox.setVisible(false);
                loadStage(stg, ev, mx, my);
                break;
            } else if (cmd.equals("CMP")) {
                int mx = parseTscInt(activeTsc, tscPc);
                int my = parseTscInt(activeTsc, tscPc + 5);
                int mt = parseTscInt(activeTsc, tscPc + 10);
                tscPc += 14;
                if (mapTiles != null && mx >= 0 && mx < mapW && my >= 0 && my < mapH) {
                    mapTiles[my * mapW + mx] = (byte) mt;
                }
            } else if (cmd.equals("HMC")) {
                playerHidden = true;
            } else if (cmd.equals("INI")) {
                tscActive = false;
                tscKeyLock = false;
                textBox.setVisible(false);
                startNewGame();
                break;
            } else if (cmd.equals("SMC")) {
                playerHidden = false;
            } else if (cmd.equals("DNA")) {
                int tp = parseTscInt(activeTsc, tscPc);
                tscPc += 4;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcType[i] == tp) vanishNpc(i);
                }
            } else if (cmd.equals("NCJ")) {
                int tp = parseTscInt(activeTsc, tscPc);
                int ev = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                boolean found = false;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcType[i] == tp) { found = true; break; }
                }
                if (found) {
                    startTscEvent(ev);
                    break;
                }
            } else if (cmd.equals("AMJ")) {
                int wc = parseTscInt(activeTsc, tscPc);
                int ev = parseTscInt(activeTsc, tscPc + 5);
                tscPc += 9;
                boolean has = false;
                for (int i = 0; i < armCount; i++) {
                    if (armsCode[i] == wc) { has = true; break; }
                }
                if (has) {
                    startTscEvent(ev);
                    break;
                }
            } else if (cmd.equals("MNP")) {
                int ev = parseTscInt(activeTsc, tscPc);
                int mx = parseTscInt(activeTsc, tscPc + 5);
                int my = parseTscInt(activeTsc, tscPc + 10);
                int dr = parseTscInt(activeTsc, tscPc + 15);
                tscPc += 19;
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcEvent[i] == ev) {
                        npcX[i] = mx * 16 * 0x200;
                        npcY[i] = my * 16 * 0x200;
                        npcDir[i] = resolveTscDir(i, dr);
                        break;
                    }
                }
            } else if (cmd.equals("ESC")) {
                tscActive = false;
                tscKeyLock = false;
                textBox.setVisible(false);
                enterTitle();
                break;
            } else {
                while (tscPc < activeTsc.length()) {
                    char nc = activeTsc.charAt(tscPc);
                    if ((nc >= '0' && nc <= '9') || nc == ':') tscPc++;
                    else break;
                }
            }
            while (tscPc < activeTsc.length() && (activeTsc.charAt(tscPc) == '\r' || activeTsc.charAt(tscPc) == '\n')) {
                tscPc++;
            }
        }
    }

    private void openCampMenu() {
        if (tscActive || tscKeyLock) return;
        gameMode = MODE_CAMP;
        campSection = (armCount > 0) ? 0 : 1;
        campCursor = (campSection == 0) ? selectedArm : 0;
        textBox.markCampDirty();
        triggerCampDescription();
    }

    private void triggerCampDescription() {
        int ev = 0;
        if (campSection == 0 && campCursor < armCount) {
            ev = 1000 + armsCode[campCursor];
        } else if (campSection == 1 && campCursor < itemCount) {
            ev = 5000 + items[campCursor];
        }
        String aiTsc = readUtf8Resource("/ArmsItem.tsc");
        if (ev > 0 && aiTsc.length() > 0) {
            startTscEventFrom(aiTsc, ev);
            muteSfx = true;                 // the description box is typed out instantly: no blips
            for (int i = 0; i < 120 && tscActive && !tscWaitNod; i++) {
                updateTsc();
            }
            muteSfx = false;
            tscActive = false;
        }
    }

    private void updateCampMenu() {
        if (keyInspect && !prevInspect || (keyCamp && !prevCamp)) {
            gameMode = MODE_PLAY;
            textBox.setVisible(false);
            activeTsc = "";
            return;
        }

        boolean moved = false;
        if (keyUp && !prevUp && campSection == 1 && armCount > 0) {
            campSection = 0;
            campCursor = 0;
            moved = true;
        } else if (keyDown && !prevDown && campSection == 0 && itemCount > 0) {
            campSection = 1;
            campCursor = 0;
            moved = true;
        } else if (keyLeft && !prevLeft) {
            int max = (campSection == 0) ? armCount : itemCount;
            if (max > 0) {
                campCursor = (campCursor + max - 1) % max;
                moved = true;
            }
        } else if (keyRight && !prevRight) {
            int max = (campSection == 0) ? armCount : itemCount;
            if (max > 0) {
                campCursor = (campCursor + 1) % max;
                moved = true;
            }
        }

        if (moved) {
            sfx(1);
            textBox.markCampDirty();
            triggerCampDescription();
        }

        if (keyJump && !prevJump) {
            if (campSection == 0 && campCursor < armCount) {
                if (selectedArm != campCursor) sfx(4);
                selectedArm = campCursor;
                gameMode = MODE_PLAY;
                textBox.setVisible(false);
                activeTsc = "";
            } else if (campSection == 1 && campCursor < itemCount) {
                int useEv = 6000 + items[campCursor];
                gameMode = MODE_PLAY;
                textBox.setVisible(false);
                String aiTsc = readUtf8Resource("/ArmsItem.tsc");
                if (!startTscEventFrom(aiTsc, useEv)) {
                    activeTsc = "";
                }
            }
        }
    }

    /** remove an entity the way the original does (DNP/DNA/kill): it also sets the entity's own flag so
     *  it stays gone when the map is re-entered (bit 0x4000 = "disappear when flag set"). */
    /** TSC direction argument: 0 left, 2 right, 4 = face Quote, 5 = same direction as Quote, 6 = opposite */
    private int resolveTscDir(int i, int dr) {
        int qx = px >> 9, nx = (npcX[i] >> 9);
        switch (dr) {
            case 4: return (nx > qx) ? 0 : 2;
            case 5: return npcDir[i];
            case 6: return (dir == 0) ? 2 : 0;
            default: return dr;
        }
    }

    private void vanishNpc(int i) {
        npcAlive[i] = false;
        int f = npcFlag[i];
        if (f > 0 && f < flags.length) flags[f] = true;
    }

    private boolean isEntityActive(int i) {
        if (!npcAlive[i]) return false;
        int flg = npcFlag[i];
        int bits = npcBits[i];
        return true;
    }

    private void checkEntityInspect() {
        int qx = (px >> 9) + 8;
        int qy = (py >> 9) + 8;
        for (int i = 0; i < npcCount; i++) {
            if (!isEntityActive(i) || npcEvent[i] == 0) continue;
            if ((npcBits[i] & 0x2000) == 0 && npcType[i] != 15 && npcType[i] != 16 &&
                npcType[i] != 17 && npcType[i] != 18 && npcType[i] != 20 && npcType[i] != 32) {
                continue;
            }
            int ex = (npcX[i] >> 9) + 8;
            int ey = (npcY[i] >> 9) + 8;
            if (Math.abs(qx - ex) <= 16 && Math.abs(qy - ey) <= 16) {
                startTscEvent(npcEvent[i]);
                return;
            }
        }
    }

    private int rand(int n) {
        rndSeed = rndSeed * 1103515245 + 12345;
        return ((rndSeed >>> 16) & 0x7FFF) % n;
    }

    private void spawnParticles(int wx, int wy, int n, int color, int speed, int life, int size) {
        for (int k = 0; k < n; k++) {
            int i = -1;
            for (int j = 0; j < MAX_PART; j++) {
                if (partLife[j] <= 0) { i = j; break; }
            }
            if (i < 0) return;
            partX[i] = wx << 3;
            partY[i] = wy << 3;
            partVx[i] = rand(speed * 2 + 1) - speed;
            partVy[i] = rand(speed * 2 + 1) - speed;
            partLife[i] = life - rand(life >> 1);
            partCol[i] = color;
            partSize[i] = size;
        }
    }

    private void updateParticles() {
        for (int i = 0; i < MAX_PART; i++) {
            if (partLife[i] <= 0) continue;
            partLife[i]--;
            partX[i] += partVx[i];
            partY[i] += partVy[i];
            partVx[i] -= partVx[i] >> 4;
            partVy[i] -= partVy[i] >> 4;
        }
    }

    private int countBullets(int code) {
        int n = 0;
        for (int i = 0; i < MAX_BULLETS; i++) if (bulAlive[i] && bulCode[i] == code) n++;
        return n;
    }

    private void fireWeapon() {
        fireWeaponPower(0);
    }

    /** Spur: hold to charge, release to fire; every other weapon uses fireWeapon() */
    private void updateSpur() {
        if (armCount == 0 || armsCode[selectedArm] != 13) { spurCharge = 0; return; }
        if (keyShoot) {
            if (spurCharge < 120) spurCharge++;
            spurBeep++;
            if ((spurBeep & 3) == 2) {          // the charging blip (original restarts it every 2 frames)
                int slv = armsLevel[selectedArm];
                if (slv < 3 || armsExp[selectedArm] < armsReq(13)[2]) sfx(58 + (slv < 1 ? 1 : (slv > 3 ? 3 : slv)));
            }
        } else if (prevShoot) {
            spurBeep = 0;
            fireWeaponPower(spurCharge);
            spurCharge = 0;
        }
    }

    private void fireWeaponPower(int charge) {
        if (armCount == 0 || shootCooldown > 0) return;
        int code = armsCode[selectedArm];
        int lv = armsLevel[selectedArm];
        if (lv < 1) lv = 1;
        if (lv > 3) lv = 3;
        boolean usesAmmo = armsMaxAmmo[selectedArm] > 0;
        if (usesAmmo && armsAmmo[selectedArm] <= 0) {
            if (!prevShoot && shootCooldown <= 0 && charge == 0) sfx(37);     // empty click
            return;
        }

        int maxB, cool, speed, life, dmg, aoe = 0;
        boolean pierce = false;
        switch (code) {
            case 1:  maxB = 4; cool = 10; speed = 0x800; life = (lv == 1) ? 20 : (lv == 2 ? 23 : 30); dmg = 2 + 2 * lv; break;
            case 2:  maxB = lv + 1; cool = 6; speed = 0x1000; life = 4 + 4 * lv; dmg = (lv == 1) ? 1 : (lv == 2 ? 2 : 4); break;
            case 3:  maxB = lv + 1; cool = 14; speed = 0x600; life = 100; dmg = (lv == 1) ? 2 : 3; break;
            case 4:  maxB = 6; cool = 6; speed = 0x1000; life = 20; dmg = 2 * lv; break;
            case 5:  maxB = 1; cool = 12; speed = 0x200; life = 40 + 20 * lv; dmg = 2 + 2 * lv; aoe = 24; break;
            case 10: maxB = 1; cool = 14; speed = 0x200; life = 50 + 20 * lv; dmg = 4 + 4 * lv; aoe = 32; break;
            case 7:  maxB = 4; cool = 8; speed = 0x400; life = 28 + 8 * lv; dmg = (lv == 1) ? 1 : 2; break;
            case 9:  maxB = 3; cool = 18; speed = 0x600; life = (lv == 3) ? 26 : 12; dmg = (lv == 1) ? 15 : (lv == 2 ? 18 : 3); pierce = (lv == 3); break;
            case 12: maxB = 4; cool = 12; speed = 0x1000; life = 24 + 6 * lv; dmg = 2 + 2 * lv; pierce = true; break;
            case 13: {
                maxB = 2; cool = 10; speed = 0x1000;
                boolean full = charge >= 90;
                life = full ? 22 : 10;
                dmg = full ? (lv == 1 ? 12 : (lv == 2 ? 20 : 30)) : (2 + 2 * lv);
                pierce = full;
                break;
            }
            default: maxB = 2; cool = 8; speed = 0x1000; life = 12; dmg = lv; break;
        }
        if (countBullets(code) >= maxB) return;
        int slot = -1;
        for (int i = 0; i < MAX_BULLETS; i++) if (!bulAlive[i]) { slot = i; break; }
        if (slot < 0) return;
        if (usesAmmo) armsAmmo[selectedArm]--;

        int i = slot;
        int bDir = (lookDir != 0) ? lookDir : dir;
        int dx = (bDir == 0) ? -1 : (bDir == 2 ? 1 : 0);
        int dy = (bDir == 1) ? -1 : (bDir == 3 ? 1 : 0);
        bulAlive[i] = true;
        bulCode[i] = code;
        bulLevel[i] = lv;
        bulDmg[i] = dmg;
        bulLife[i] = life;
        bulDir[i] = bDir;
        bulPierce[i] = pierce;
        bulAge[i] = 0;
        bulAoe[i] = aoe;
        bulVx[i] = dx * speed;
        bulVy[i] = dy * speed;
        bulX[i] = px + dx * (4 << 9);
        bulY[i] = py + (dy == 0 ? (3 << 9) : dy * (6 << 9));
        if (code == 3 && dy == 0) bulVy[i] = -0x200;
        if (code == 4 && lv < 3) {
            // machine gun spread
            if (dy == 0) bulY[i] += (rand(9) - 4) << 9; else bulX[i] += (rand(9) - 4) << 9;
        }
        if (code == 7) {
            // bubbles come out with a little vertical scatter
            if (dy == 0) bulVy[i] = (rand(5) - 2) * 0x40; else bulVx[i] = (rand(5) - 2) * 0x40;
        }
        if (code == 2 || code == 4 || code == 13) spawnCaret(bulX[i], bulY[i]);

        // machine gun level 3: recoil. Shooting down is a jetpack, shooting up pushes you down.
        if (code == 4 && lv == 3) {
            if (bDir == 3) {
                if (vy > 0) vy /= 2;
                if (vy > -0x400) {
                    vy -= 0x200;
                    if (vy < -0x400) vy = -0x400;
                }
                grounded = false;
            } else if (bDir == 1) {
                if (vy < 0) vy /= 2;
                if (vy < 0x200) vy += 0x100;
            }
        }
        shootCooldown = cool;
        switch (code) {
            case 1: sfx(33); break;
            case 2: case 4: sfx(lv == 3 ? 49 : 32); break;
            case 3: case 9: sfx(34); break;
            case 5: case 10: sfx(32); break;
            case 7: sfx(48); break;
            case 12: sfx(lv == 1 ? 117 : (lv == 2 ? 49 : 60)); break;
            case 13: sfx(charge >= 90 ? 61 + lv : 49); break;
            default: sfx(32); break;
        }
    }

    private void damageNpc(int n, int dmg) {
        npcHp[n] -= dmg;
        if (npcHp[n] > 0) {
            if (npcShock[n] < 14) sfx(hitVoice(npcType[n]));
            npcShock[n] = 16;
        }
        if (npcHp[n] <= 0) {
            npcHp[n] = 0;
            spawnCaret(npcX[n], npcY[n]);
            spawnParticles((npcX[n] >> 9) + 8, (npcY[n] >> 9) + 8, 6, 0xD0D0D0, 10, 22, 3);
            if ((npcBits[n] & 0x0200) != 0 && npcEvent[n] > 0) {
                // like the original: the NPC is not removed, its kill script decides what happens to it
                npcVx[n] = 0;
                npcState[n] = 140;
                npcTimer[n] = 0;
                startTscEvent(npcEvent[n]);
            } else {
                sfx(destroyVoice(npcType[n]));
                vanishNpc(n);
                dropLoot(npcX[n], npcY[n], npcExp[n]);
            }
        }
    }

    private void explodeBullet(int i) {
        sfx(44);
        int cx = (bulX[i] >> 9) + 8, cy = (bulY[i] >> 9) + 8;
        int r = bulAoe[i];
        spawnParticles(cx, cy, 8, 0xFFB030, 14, 18, 3);
        spawnParticles(cx, cy, 5, 0xB0B0B0, 8, 24, 4);
        if (r <= 0) return;
        for (int n = 0; n < npcCount; n++) {
            if (!isEntityActive(n) || npcHp[n] <= 0) continue;
            int tb = tblBits(npcType[n]);
            if ((tb & 0x20) == 0 || (tb & 0x04) != 0) continue;
            int nx = (npcX[n] >> 9) + 8, ny = (npcY[n] >> 9) + 8;
            if (Math.abs(nx - cx) <= r && Math.abs(ny - cy) <= r) damageNpc(n, Math.max(1, bulDmg[i] >> 1));
        }
    }

    private void shiftBlock(int tx, int ty) {
        sfx(12);
        mapTiles[ty * mapW + tx]--;
        for (int k = 0; k < 4; k++) {
            setNpc(4, tx * 16 * 0x200, ty * 16 * 0x200, rnd(-0x200, 0x200), rnd(-0x200, 0x200), 0, -1);
        }
        spawnParticles(tx * 16 + 8, ty * 16 + 8, 3, 0xC8A878, 12, 22, 3);
    }

    private void updateBullets() {
        if (shootCooldown > 0) shootCooldown--;
        for (int n = 0; n < npcCount; n++) if (npcHitCool[n] > 0) npcHitCool[n]--;
        updateParticles();
        for (int i = 0; i < MAX_BULLETS; i++) {
            if (!bulAlive[i]) continue;
            int code = bulCode[i];
            if (code == 3) {
                bulVy[i] += 0x45;
                if (bulVy[i] > 0x500) bulVy[i] = 0x500;
            } else if (code == 1) {
                if (bulDir[i] == 0 || bulDir[i] == 2) bulVy[i] = ((bulLife[i] & 4) == 0) ? -0x300 : 0x300;
                else bulVx[i] = ((bulLife[i] & 4) == 0) ? -0x300 : 0x300;
            } else if (code == 5 || code == 10) {
                int lim = (code == 10) ? 0xC00 : 0x900;
                if (bulDir[i] == 0 && bulVx[i] > -lim) bulVx[i] -= 0x80;
                else if (bulDir[i] == 2 && bulVx[i] < lim) bulVx[i] += 0x80;
                else if (bulDir[i] == 1 && bulVy[i] > -lim) bulVy[i] -= 0x80;
                else if (bulDir[i] == 3 && bulVy[i] < lim) bulVy[i] += 0x80;
                if ((bulLife[i] & 1) == 0) spawnParticles((bulX[i] >> 9) + 8 - (bulVx[i] > 0 ? 6 : (bulVx[i] < 0 ? -6 : 0)),
                                                         (bulY[i] >> 9) + 8, 1, 0xC0C0C0, 3, 14, 2);
            } else if (code == 7) {
                bulVx[i] -= bulVx[i] >> 5;
                bulVy[i] -= bulVy[i] >> 5;
                bulVy[i] -= 6;
            }

            bulX[i] += bulVx[i];
            bulY[i] += bulVy[i];
            bulLife[i]--;
            bulAge[i]++;
            if (bulLife[i] <= 0) {
                if (code == 5 || code == 10) explodeBullet(i);
                bulAlive[i] = false;
                continue;
            }

            int bx = (bulX[i] >> 9) + 8;
            int by = (bulY[i] >> 9) + 8;
            int tx = bx >> 4;
            int ty = by >> 4;
            int attr = getTileAttr(tx, ty);

            if (attr == 0x43 && code != 3 && code != 7) {
                // like the original: every shot except fireball and bubbler smashes a star block (tile id - 1, plus smoke)
                shiftBlock(tx, ty);
                spawnCaret(bulX[i], bulY[i]);
                if (code == 5 || code == 10) {
                    explodeBullet(i);
                    bulAlive[i] = false;
                    continue;
                }
                if (code != 13) {                       // the Spur passes through the blocks it breaks
                    bulAlive[i] = false;
                    continue;
                }
            } else if (attr == 0x41 || attr == 0x43 || attr == 0x44 || attr == 0x46 || attr == 0x61) {
                if (code == 3) {
                    sfx(34);
                    bulVy[i] = -0x380;
                    bulY[i] -= bulVy[i] < 0 ? 0 : 0;
                } else if (code == 5 || code == 10) {
                    explodeBullet(i);
                    bulAlive[i] = false;
                    continue;
                } else if (code == 1 || code == 9 || code == 12 || (code == 13 && bulPierce[i])) {
                    // snake, blade and nemesis shots stop at walls but do not smash blocks
                    if (code != 13) sfx(28);
                    bulAlive[i] = false;
                    spawnParticles(bx, by, 3, 0xFFFFC0, 8, 10, 2);
                    continue;
                } else {
                    sfx(28);
                    bulAlive[i] = false;
                    spawnCaret(bulX[i], bulY[i]);
                    spawnParticles(bx, by, 3, 0xFFFFC0, 8, 10, 2);
                    continue;
                }
            }

            for (int n = 0; n < npcCount; n++) {
                if (!isEntityActive(n) || npcHp[n] <= 0) continue;
                int t = npcType[n];
                int tb = npcBits[n];
                if ((tb & 0x20) == 0) continue;
                int nx = (npcX[n] >> 9) + 8;
                int ny = (npcY[n] >> 9) + 8;
                int hx = Math.max(nHit(n, 0), nHit(n, 2)) + 2;
                if (Math.abs(bx - nx) <= hx && by >= ny - nHit(n, 1) - 2 && by <= ny + nHit(n, 3) + 2) {
                    if ((tb & 0x04) != 0) {            // invulnerable: bullet bounces off
                        sfx(31);
                        if (!bulPierce[i]) { bulAlive[i] = false; spawnParticles(bx, by, 2, 0xFFFFFF, 8, 8, 2); }
                        break;
                    }
                    if (bulPierce[i] && npcHitCool[n] > 0) continue;
                    if (code == 5 || code == 10) {
                        explodeBullet(i);
                        damageNpc(n, bulDmg[i]);
                        bulAlive[i] = false;
                        break;
                    }
                    spawnParticles(bx, by, 2, 0xFFE070, 8, 10, 2);
                    damageNpc(n, bulDmg[i]);
                    if (bulPierce[i]) {
                        npcHitCool[n] = (code == 9) ? 4 : 8;
                    } else {
                        bulAlive[i] = false;
                        spawnCaret(bulX[i], bulY[i]);
                        break;
                    }
                }
            }
        }
    }

    private void updateNpcs() {
        int qx = (px >> 9) + 8;
        int qy = (py >> 9) + 8;

        for (int i = 0; i < npcCount; i++) {
            if (!isEntityActive(i)) continue;
            int t = npcType[i];
            dbgNpc = i; dbgType = t; dbgState = npcState[i];
            int nx = (npcX[i] >> 9) + 8;
            int ny = (npcY[i] >> 9) + 8;

            if ((npcBits[i] & 0x0100) != 0 && npcEvent[i] > 0 && !tscActive) {
                if (Math.abs(qx - nx) <= 12 && Math.abs(qy - ny) <= 12) {
                    startTscEvent(npcEvent[i]);
                    return;
                }
            }

            if (t == 1) {
                npcVy[i] += 0x2A;
                if (npcVy[i] > 0x400) npcVy[i] = 0x400;
                npcX[i] += npcVx[i];
                npcY[i] += npcVy[i];
                int footY = (npcY[i] >> 9) + 12;
                int slopeY = getSlopeFloorY(nx, footY);
                if (slopeY != -1 && footY >= slopeY) {
                    npcY[i] = (slopeY - 12) << 9;
                    if (npcVy[i] > 0x100) sfx(45);
                    npcVy[i] = -(npcVy[i] >> 1);
                    npcVx[i] = (npcVx[i] * 3) >> 2;
                } else if (isSolidBlock(nx >> 4, footY >> 4)) {
                    npcY[i] = (((footY >> 4) << 4) - 12) << 9;
                    if (npcVy[i] > 0x100) sfx(45);
                    npcVy[i] = -(npcVy[i] >> 1);
                    npcVx[i] = (npcVx[i] * 3) >> 2;
                }
                npcTimer[i]++;
                if (npcTimer[i] > 500) npcAlive[i] = false;
                if (Math.abs(qx - nx) <= 12 && Math.abs(qy - ny) <= 12) {
                    addWeaponExp(npcExp[i] > 0 ? npcExp[i] : 1);
                    sfx(14);
                    npcAlive[i] = false;
                }
            } else if (t == 86 || t == 87) {
                updatePickup(i, t);
            } else if (isPorted(t)) {
                runPorted(i, t);
            } else if (npcHp[i] > 0 && isHostile(t)) {
                npcTimer[i]++;
                int hb = nHit(i, 3);
                if (isFlyingEnemy(t)) {
                    if (Math.abs(qx - nx) < 160) {
                        npcDir[i] = (qx < nx) ? 0 : 2;
                        npcX[i] += (npcDir[i] == 0) ? -0x120 : 0x120;
                        npcY[i] += ((npcTimer[i] & 16) == 0) ? -0xC0 : 0xC0;
                    }
                } else {
                    npcVy[i] += 0x40;
                    if (npcVy[i] > 0x5FF) npcVy[i] = 0x5FF;
                    int dist = Math.abs(qx - nx);
                    boolean isBoss = (npcHp[i] >= 25);
                    if (npcState[i] == 0) {
                        npcVx[i] = 0;
                        if (dist < (isBoss ? 150 : 110)) {
                            npcDir[i] = (qx < nx) ? 0 : 2;
                            npcState[i] = 1;
                            npcTimer[i] = 0;
                        }
                    } else if (npcState[i] == 1) {
                        if (isBoss) {
                            npcVx[i] = (npcDir[i] == 0) ? -0x160 : 0x160;
                        }
                        if (npcTimer[i] > (isBoss ? 32 : 18)) {
                            npcState[i] = 2;
                            npcVy[i] = isBoss ? -0x4C0 : -0x420;
                            npcVx[i] = (npcDir[i] == 0) ? (isBoss ? -0x220 : -0x180) : (isBoss ? 0x220 : 0x180);
                        }
                    }
                    npcX[i] += npcVx[i];
                    npcY[i] += npcVy[i];
                    // entity position is the tile *centre* in the original; our npcX/npcY are the
                    // top-left of a 16x16 cell, so the centre is +8 and the feet are at centre + hit.bottom
                    int footY = (npcY[i] >> 9) + 8 + hb;
                    int slopeY = getSlopeFloorY(nx, footY);
                    if (npcVy[i] >= 0 && slopeY != -1 && footY >= slopeY - 2) {
                        npcY[i] = (slopeY - 8 - hb) << 9;
                        npcVy[i] = 0;
                        if (npcState[i] == 2) npcState[i] = 0;
                    } else if (npcVy[i] >= 0 && isSolidForNpc(nx >> 4, footY >> 4)) {
                        npcY[i] = (((footY >> 4) << 4) - 8 - hb) << 9;
                        npcVy[i] = 0;
                        if (npcState[i] == 2) npcState[i] = 0;
                    }
                }
                if (npcDmg[i] > 0) {
                    int cy = (npcY[i] >> 9) + 8;
                    int rx = Math.max(nHit(i, 0), nHit(i, 2));
                    int ryTop = nHit(i, 1);
                    int ryBot = nHit(i, 3);
                    damageQuoteCheck(nx, cy + ((ryBot - ryTop) >> 1), rx, (ryTop + ryBot) >> 1, npcDmg[i]);
                }
            } else {
                npcTimer[i]++;
                if (isHazard(t)) {
                    int cy = (npcY[i] >> 9) + 8;
                    int rx = Math.max(nHit(i, 0), nHit(i, 2));
                    int ryTop = nHit(i, 1);
                    int ryBot = nHit(i, 3);
                    damageQuoteCheck(nx, cy + ((ryBot - ryTop) >> 1), rx, (ryTop + ryBot) >> 1, npcDmg[i]);
                }
            }
        }
    }

    private void damageQuoteCheck(int ex, int ey, int rx, int ry, int dmg) {
        if (shockTicks > 0 || tscActive) return;
        int qx = (px >> 9) + 8;
        int qy = (py >> 9) + 8;
        if (Math.abs(qx - ex) <= rx + 4 && Math.abs(qy - ey) <= ry + 4) {
            hurtQuote(dmg, (qx < ex) ? -0x280 : 0x280);
        }
    }

    private void hurtQuote(int dmg, int knockVx) {
        if (shockTicks > 0 || tscActive || hp <= 0 || playerHidden) return;
        hp -= dmg;
        sfx(16);
        shockTicks = 128;            // original: 128 frames of invincibility
        vy = -0x340;
        vx = knockVx;
        // the equipped weapon loses experience when Quote is hit
        if (armCount > 0 && armsExp[selectedArm] > 0) {
            armsExp[selectedArm] -= dmg;
            if (armsExp[selectedArm] < 0) armsExp[selectedArm] = 0;
        }
        if (hp <= 0) {
            hp = 0;
            killQuote(40);
        }
    }

    /** Quote bursts into a cloud of smoke, then the "retry?" script runs (#40 hit, #41 drowned) */
    private void killQuote(int event) {
        int cx = (px >> 9) + 8, cy = (py >> 9) + 8;
        sfx(17);
        spawnParticles(cx, cy, 14, 0xFFFFFF, 22, 40, 5);
        spawnParticles(cx, cy, 14, 0xB8B8C0, 16, 46, 4);
        spawnParticles(cx, cy, 8, 0x707078, 12, 50, 3);
        playerHidden = true;
        vx = 0;
        vy = 0;
        for (int i = 0; i < MAX_BULLETS; i++) bulAlive[i] = false;
        spurCharge = 0;
        sound.playMusic(3);
        startTscEvent(event);
    }

    /** attribute 0x42 / 0x62 = "10 damage" tiles (spikes) */
    private void checkHurtTiles() {
        int left = ((px >> 9) + 3) >> 4;
        int right = ((px >> 9) + 12) >> 4;
        int top = ((py >> 9) + 2) >> 4;
        int bottom = ((py >> 9) + 15) >> 4;
        for (int ty = top; ty <= bottom; ty++) {
            for (int tx = left; tx <= right; tx++) {
                int a = getTileAttr(tx, ty);
                if (a == 0x42 || a == 0x62) {
                    hurtQuote(10, (vx >= 0) ? -0x280 : 0x280);
                    return;
                }
            }
        }
    }

    private void updateCarets() {
        for (int i = 0; i < MAX_CARETS; i++) {
            if (!carAlive[i]) continue;
            carTick[i]++;
            if (carTick[i] >= 12) carAlive[i] = false;
        }
    }

    public void start() {
        running = true;
        beat = System.currentTimeMillis();
        loadLastHang();
        new Thread(this).start();
        Thread wd = new Thread(new Runnable() {
            public void run() { watchdog(); }
        });
        try { wd.setPriority(Thread.MIN_PRIORITY); } catch (Throwable t) {}
        wd.start();
    }

    // ---- hang diagnostics ---------------------------------------------------------------------------
    // The game loop stamps `beat` every frame and records which part of the tick it is in.  If the stamp
    // goes stale for 4 s a watchdog thread shows where the game is stuck (on screen if the UI thread still
    // paints, and always saved to the phone's record store so it can be read after a restart).
    private volatile long beat;
    private volatile int dbgPhase, dbgNpc, dbgType, dbgState;
    private volatile String hangMsg;
    private String lastHang;

    private void watchdog() {
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
            long late = System.currentTimeMillis() - beat;
            if (late > 4000 && hangMsg == null && gameMode != MODE_INTRO) {
                String m = "HANG " + (late / 1000) + "s ph" + dbgPhase + " npc" + dbgNpc + " ty" + dbgType + " st" + dbgState
                         + " stage" + curStageId + " pc" + tscPc + " mode" + gameMode
                         + " mem" + (Runtime.getRuntime().freeMemory() / 1024) + "K";
                hangMsg = m;
                saveHang(m);
                repaint();
            } else if (late < 1500 && hangMsg != null) {
                hangMsg = null;       // it was only a long pause
                repaint();
            }
        }
    }

    private void saveHang(String m) {
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore("cshang", true);
            byte[] b = m.getBytes();
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
        } catch (Throwable t) {
        } finally {
            if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable t) {} }
        }
    }

    private void loadLastHang() {
        javax.microedition.rms.RecordStore rs = null;
        try {
            rs = javax.microedition.rms.RecordStore.openRecordStore("cshang", false);
            if (rs.getNumRecords() > 0) lastHang = new String(rs.getRecord(1));
        } catch (Throwable t) {
        } finally {
            if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable t) {} }
        }
        if (lastHang != null) {
            // shown once on the title screen, then forgotten; sound effects start off after a hang (press * to turn them on)
            try { javax.microedition.rms.RecordStore.deleteRecordStore("cshang"); } catch (Throwable t) {}
            Sfx.markCrash();
        } else if (Sfx.disabledByCrash) {
            lastHang = "sound effects are off because the phone froze while playing one (press * to try again)";
        }
    }

    private void paintDiag(Graphics g) {
        String m = hangMsg;
        boolean old = false;
        if (m == null && gameMode == MODE_TITLE) { m = lastHang; old = true; }
        if (m == null) return;
        if (old) m = "last " + m;
        g.setColor(0x000000);
        g.fillRect(0, 0, 240, 74);
        g.setColor(old ? 0xA0A0A0 : 0xFF4040);
        int per = 34;
        for (int i = 0; i < 5 && i * per < m.length(); i++) {
            g.drawString(m.substring(i * per, Math.min(m.length(), (i + 1) * per)), 2, 2 + i * 14, Graphics.TOP | Graphics.LEFT);
        }
    }

    public void stop() {
        running = false;
        if (sound != null) sound.shutdown();
    }

    /** shown on screen instead of a silent black screen (real phones have no console) */
    private String[] errLines;

    private void fail(Throwable t) {
        try {
            Runtime rt = Runtime.getRuntime();
            String m = t.getClass().getName() + " " + t.getMessage()
                     + " | stage " + curStageId + " mode " + gameMode
                     + " | free " + (rt.freeMemory() / 1024) + "K / " + (rt.totalMemory() / 1024) + "K";
            int per = 30;
            int n = (m.length() + per - 1) / per;
            if (n > 14) n = 14;
            String[] ls = new String[n];
            for (int i = 0; i < n; i++) ls[i] = m.substring(i * per, Math.min(m.length(), (i + 1) * per));
            errLines = ls;
        } catch (Throwable x) {
            errLines = new String[]{"error"};
        }
        running = false;
        repaint();
    }

    private void paintError(Graphics g) {
        g.setColor(0x000000);
        g.fillRect(0, 0, 240, 320);
        g.setColor(0xFFFFFF);
        for (int i = 0; i < errLines.length; i++) {
            g.drawString(errLines[i], 4, 4 + i * 14, Graphics.TOP | Graphics.LEFT);
        }
    }

    public void run() {
        try {
            runLoop();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void runLoop() {
        long lastTime = System.currentTimeMillis();
        long accum = 0;

        while (running) {
            long now = System.currentTimeMillis();
            beat = now;
            long delta = now - lastTime;
            lastTime = now;
            if (delta > 100) delta = 100;
            accum += delta;

            while (accum >= TICK_MS) {
                if (gameMode == MODE_INTRO) {
                    introTimer++;
                    if (introTimer > 90 || (keyJump && !prevJump) || (keyShoot && !prevShoot)) {
                        enterTitle();
                    }
                } else if (gameMode == MODE_TITLE) {
                    if ((keyUp && !prevUp) || (keyDown && !prevDown)) {
                        if (hasSaveFile) { titleCursor = 1 - titleCursor; sfx(1); }
                    } else if ((keyJump && !prevJump) || (keyShoot && !prevShoot) || (keyInspect && !prevInspect)) {
                        sfx(18);
                        if (titleCursor == 1 && hasSaveFile) continueSavedGame();
                        else startNewGame();
                    }
                } else if (gameMode == MODE_CAMP) {
                    updateCampMenu();
                } else if (gameMode == MODE_TELEPORT) {
                    updateTeleportMenu();
                } else if (gameMode == MODE_CREDITS) {
                    updateCredits();
                } else if (gameMode == MODE_PLAY) {
                    if (quakeTicks > 0) quakeTicks--;
                    if (shockTicks > 0) shockTicks--;
                    dbgPhase = 1; dbgState = tscPc;
                    updateTsc();
                    if (!tscKeyLock && fadeMode == 0 && gameMode == MODE_PLAY) {
                        if (keyCamp && !prevCamp) {
                            openCampMenu();
                        } else {
                            if (keyNextArm && !prevNextArm && armCount > 1) {
                                selectedArm = (selectedArm + 1) % armCount;
                                sfx(4);
                            }
                            dbgPhase = 2;
                            updatePhysics();
                            dbgPhase = 3;
                            if (!tscActive) {
                                int wc = (armCount > 0) ? armsCode[selectedArm] : 0;
                                if (wc == 13) {
                                    updateSpur();
                                } else if (keyShoot && (!prevShoot || wc == 4 || wc == 7 || wc == 12)) {
                                    fireWeapon();
                                }
                            } else {
                                spurCharge = 0;
                            }
                            if ((keyInspect && !prevInspect || (keyDown && !prevDown && grounded && vx == 0)) && !tscActive) {
                                checkEntityInspect();
                            }
                        }
                    }
                    if (gameMode == MODE_PLAY) {
                        dbgPhase = 4;
                        updateBullets();
                        dbgPhase = 5;
                        updateNpcs();
                        dbgPhase = 6;
                        updateCarets();
                    }
                }

                prevLeft = keyLeft;
                prevRight = keyRight;
                prevUp = keyUp;
                prevDown = keyDown;
                prevJump = keyJump;
                prevShoot = keyShoot;
                prevInspect = keyInspect;
                prevNextArm = keyNextArm;
                prevCamp = keyCamp;
                accum -= TICK_MS;
            }

            dbgPhase = 7;
            repaint();
            serviceRepaints();
            dbgPhase = 8;

            long sleepTime = TICK_MS - (System.currentTimeMillis() - now);
            if (sleepTime > 2) {
                try { Thread.sleep(sleepTime); } catch (InterruptedException ie) {}
            } else {
                Thread.yield();
            }
        }
    }

    private int getTileAttr(int tx, int ty) {
        if (ty >= mapH) return 0;                    // below the map: let Quote fall out (-> respawn)
        if (tx < 0 || tx >= mapW || ty < 0) return 0x41;
        int tileId = mapTiles[ty * mapW + tx] & 0xFF;
        return tileAttrs[tileId] & 0xFF;
    }

    /**
     * PXA attribute bits: 0x40 = foreground/solid family, 0x20 = water, 0x10 = slope.
     * low nibble (non-slope): 1 solid, 2 hurt, 3 breakable block, 4 NPC-only blocker, 6 player blocker.
     * 0x40 and 0x45 are plain foreground decoration; 0x44/0x64 only block NPCs, never Quote
     * (the old code made them walls for Quote too).
     */
    private static boolean attrSolidForPlayer(int attr) {
        if (attr == 0x05) return true;
        if ((attr & 0x50) != 0x40) return false;      // not foreground, or a slope
        int n = attr & 0x0F;
        return n == 1 || n == 3 || n == 6;
    }

    private boolean isSolidBlock(int tx, int ty) {
        return attrSolidForPlayer(getTileAttr(tx, ty));
    }

    /** solid for NPCs: everything Quote collides with plus the NPC-blocker tiles */
    private boolean isSolidForNpc(int tx, int ty) {
        int attr = getTileAttr(tx, ty);
        return attrSolidForPlayer(attr) || (attr & 0x5F) == 0x44;
    }

    private int getSlopeFloorY(int worldX, int worldY) {
        int tx = worldX >> 4;
        int ty = worldY >> 4;
        int attr = getTileAttr(tx, ty);
        int localX = worldX & 15;
        int tileTop = ty << 4;

        switch (attr & 0xDF) {
            // shapes verified against the tile art of PrtCave: 0x54/0x55 are the two halves of a
            // "\" slope (high on the left), 0x56/0x57 the two halves of a "/" slope (low on the left)
            case 0x54: return tileTop + (localX >> 1);
            case 0x55: return tileTop + 8 + (localX >> 1);
            case 0x56: return tileTop + 16 - (localX >> 1);
            case 0x57: return tileTop + 8 - (localX >> 1);
            default:   return -1;
        }
    }

    private int getSlopeCeilingY(int worldX, int worldY) {
        int tx = worldX >> 4;
        int ty = worldY >> 4;
        int attr = getTileAttr(tx, ty);
        int localX = worldX & 15;
        int tileTop = ty << 4;

        // ceiling slopes are 0x50..0x53 (0x70..0x73 under water); shapes mirror the floor slopes
        if ((attr & 0xDC) != 0x50) return -1;
        switch (attr & 0x03) {
            case 0: return tileTop + 16 - (localX >> 1);   // "/" ceiling, lower half
            case 1: return tileTop + 8 - (localX >> 1);    // "/" ceiling, upper half
            case 2: return tileTop + (localX >> 1);        // "\" ceiling, upper half
            default: return tileTop + 8 + (localX >> 1);   // "\" ceiling, lower half
        }
    }

    private void updatePhysics() {
        int centerAttr = getTileAttr(((px >> 9) + 8) >> 4, ((py >> 9) + 8) >> 4);
        boolean wasInWater = inWater;
        inWater = (centerAttr == 0x02 || (centerAttr >= 0x60 && centerAttr <= 0x64));
        if (inWater && !wasInWater && !grounded && (vy > 0x200 || vx > 0x200 || vx < -0x200)) sfx(56);
        if (inWater && !hasItem(21)) {
            if (airTicks > 0) airTicks--;
            else if (!tscActive && hp > 0) {
                hp = 0;
                killQuote(41);
            }
        } else {
            airTicks = 1000;
        }

        // constants from Cave Story's MyChar code (units: 1 px = 0x200)
        int maxDash = inWater ? 0x196 : 0x32C;
        int maxMove = inWater ? 0x2FF : 0x5FF;
        int dash = grounded ? (inWater ? 0x2A : 0x55) : (inWater ? 0x10 : 0x20);
        int resist = inWater ? 0x19 : 0x33;
        int jumpVel = inWater ? -0x280 : -0x500;
        int gravity1 = inWater ? 0x28 : 0x50;
        int gravity2 = inWater ? 0x10 : 0x20;

        if (keyUp) lookDir = 1;
        else if (keyDown && !grounded) lookDir = 3;
        else lookDir = 0;

        // like the original: acceleration only applies while below the walk limit, nothing clamps
        // a higher speed (booster / knock-back momentum is kept in the air)
        if (keyLeft && !keyRight) {
            dir = 0;
            if (vx > -maxDash) vx -= dash;
            animTick++;
            if (grounded && animTick % 10 == 5) sfx(24);
        } else if (keyRight && !keyLeft) {
            dir = 2;
            if (vx < maxDash) vx += dash;
            animTick++;
            if (grounded && animTick % 10 == 5) sfx(24);
        } else {
            if (grounded) {
                if (vx > 0) vx = (vx > resist) ? vx - resist : 0;
                else if (vx < 0) vx = (vx < -resist) ? vx + resist : 0;
            }
            if (animTick > 0 && grounded) sfx(24);      // the little scuff when Quote stops walking
            animTick = 0;
        }

        if (grounded) {
            boostFuel = 50;
            if (boosting) boosting = false;
        }

        boolean booster20 = hasItem(23);
        boolean booster08 = hasItem(18) && !booster20;
        if (keyJump && !prevJump) {
            if (grounded) {
                vy = jumpVel;
                grounded = false;
                sfx(15);
            } else if ((booster08 || booster20) && boostFuel > 0) {
                boosting = true;
                sfx(113);
                if (booster20) {
                    if (keyUp) boostDir = 1;
                    else if (keyDown) boostDir = 3;
                    else if (keyLeft) boostDir = 0;
                    else if (keyRight) boostDir = 2;
                    else boostDir = 1;
                    if (boostDir == 0 || boostDir == 2) vy = 0;
                } else {
                    boostDir = 1;
                    if (vy > 0x100) vy /= 2;
                }
            }
        }

        if (boosting && keyJump && boostFuel > 0) {
            boostFuel--;
            if (booster20) {
                if (boostDir == 1) {
                    vy -= 0x20;
                    if (vy < -0x5FF) vy = -0x5FF;
                } else if (boostDir == 3) {
                    vy += 0x20;
                    if (vy > 0x5FF) vy = 0x5FF;
                } else {
                    vy = 0;
                    if (boostDir == 0) { vx -= 0x20; if (vx < -0x5FF) vx = -0x5FF; }
                    else { vx += 0x20; if (vx > 0x5FF) vx = 0x5FF; }
                }
            } else {
                vy -= 0x20;
                if (vy < -0x400) vy = -0x400;
            }
            if (boostFuel % 3 == 1) sfx(113);
            if ((boostFuel & 1) == 0) {
                int fx = (px >> 9) + 8, fy = (py >> 9) + 14;
                if (boostDir == 0) fx += 8; else if (boostDir == 2) fx -= 8;
                else if (boostDir == 3) fy -= 14;
                spawnParticles(fx, fy, 1, 0xE0E0E0, 6, 14, 3);
            }
        } else {
            boosting = false;
            vy += (vy < 0 && keyJump) ? gravity2 : gravity1;
        }
        if (vy > maxMove) vy = maxMove;
        if (vy < -maxMove) vy = -maxMove;

        px += vx;
        int charX = px >> 9;
        int charY = py >> 9;
        if (vx < 0) {
            if (isSolidBlock((charX + 3) >> 4, (charY + 3) >> 4) ||
                isSolidBlock((charX + 3) >> 4, (charY + 9) >> 4)) {
                px = (((((charX + 3) >> 4) + 1) << 4) - 3) << 9;
                vx = 0;
            }
        } else if (vx > 0) {
            if (isSolidBlock((charX + 12) >> 4, (charY + 3) >> 4) ||
                isSolidBlock((charX + 12) >> 4, (charY + 9) >> 4)) {
                px = ((((charX + 12) >> 4) << 4) - 13) << 9;
                vx = 0;
            }
        }

        boolean wasGrounded = grounded;
        py += vy;
        charX = px >> 9;
        charY = py >> 9;
        grounded = false;

        int footX = charX + 8;
        int footY = charY + 16;
        int slopeY = getSlopeFloorY(footX, footY - 2);
        if (slopeY == -1) slopeY = getSlopeFloorY(footX, footY + 4);

        if (vy >= 0 && slopeY != -1 && (footY >= slopeY - 3 || wasGrounded)) {
            py = (slopeY - 16) << 9;
            if (vy > 0x400) sfx(23);
            vy = 0;
            grounded = true;
        } else if (vy < 0) {
            int ceilSlopeY = getSlopeCeilingY(footX, charY + 2);
            if (ceilSlopeY != -1 && charY <= ceilSlopeY) {
                py = ceilSlopeY << 9;
                if (vy < -0x200) sfx(3);
                vy = 0;
            } else if (isSolidBlock((charX + 4) >> 4, (charY + 1) >> 4) ||
                       isSolidBlock((charX + 11) >> 4, (charY + 1) >> 4)) {
                py = ((((charY + 1) >> 4) + 1) << 4) << 9;
                if (vy < -0x200) sfx(3);
                vy = 0;
            }
        } else {
            if (isSolidBlock((charX + 4) >> 4, footY >> 4) ||
                isSolidBlock((charX + 11) >> 4, footY >> 4)) {
                py = (((footY >> 4) << 4) - 16) << 9;
                if (vy > 0x400) sfx(23);
                vy = 0;
                grounded = true;
            }
        }

        checkHurtTiles();
        if ((charY >> 4) >= mapH) {
            resetQuote();
        }
    }

    private void drawLandscapeRegion(Graphics g, Image rotSheet, int origSheetH,
                                     int srcX, int srcY, int w, int h,
                                     int dstX, int dstY) {
        if (rotSheet == null || dstX + w <= 0 || dstX >= 320 || dstY + h <= 0 || dstY >= 240) return;
        if (srcX < 0 || srcY < 0 || srcX + w > rotSheet.getHeight() || srcY + h > origSheetH) return;

        int rotSrcX = origSheetH - srcY - h;
        int rotSrcY = srcX;
        int rotDstX = 240 - dstY - h;
        int rotDstY = dstX;
        g.drawRegion(rotSheet, rotSrcX, rotSrcY, h, w, 0, rotDstX, rotDstY, Graphics.TOP | Graphics.LEFT);
    }

    private void fillLandscapeRect(Graphics g, int dstX, int dstY, int w, int h) {
        if (w <= 0 || h <= 0) return;
        int rotDstX = 240 - dstY - h;
        int rotDstY = dstX;
        g.fillRect(rotDstX, rotDstY, h, w);
    }

    /** sheet for an NPC surface id, or null when we cannot draw that surface */
    private Image npcSheet(int surf) {
        switch (surf) {
            case 20: return imgNpcSym;
            case 21: return imgNpc1;
            case 22: return imgNpc2;
            case 23: ensureNpcReguLoaded(); return imgNpcRegu;
            case 2: return imgTileset;
            case 16: return imgMyChar;
            default: return null;
        }
    }

    private void drawNpcByRect(Graphics g, int i, int ex, int ey) {
        int t = npcType[i];
        int surf = npcSurf[i];
        Image sheet = npcSheet(surf);
        if (sheet == null) return;
        int nl = npcRcLeft[t];
        int nr = npcRcRight[t];
        int st = npcState[i];
        int tm = npcTimer[i];
        boolean right = (npcDir[i] != 0 || (t == 86 && npcExp[i] == 3)) && nr > 0;
        int n = right ? nr : nl;
        int f = 0;
        if (t == 211) {
            f = npcEvent[i] & 3;                       // spike orientation is the entity's event number
        } else if (n > 1) {
            if (isHostile(t)) {
                if (isFlyingEnemy(t)) f = (tm >> 2) % n;
                else f = (st == 2) ? 2 : (st == 1 ? 1 : 0);
            } else if (npcVx[i] != 0) {
                f = (tm >> 3) % n;
            } else if (t == 18) {
                f = (st > 0) ? 1 : 0;
            }
        }
        if (npcAnim[i] < 0) return;                   // blink-off frame
        if (npcAnim[i] > 0) f = npcAnim[i] - 1;
        if (f >= n) f = n - 1;
        int idx = npcRcOff[t] + ((right ? nl : 0) + f) * 4;
        int l = npcRcData[idx], tp = npcRcData[idx + 1];
        int w = npcRcData[idx + 2] - l, h = npcRcData[idx + 3] - tp;
        int vf = nView(i, 0), vt = nView(i, 1), vb = nView(i, 2);
        int drawX, drawY;
        if (vf == 0 && vt == 0 && vb == 0) {
            drawX = ex + 8 - (w >> 1);
            drawY = ey + 8 - (h >> 1);
        } else {
            drawX = ex + 8 - ((npcDir[i] == 0) ? vf : vb);
            drawY = ey + 8 - vt;
        }
        if (w <= 0 || h <= 0) return;
        drawLandscapeRegion(g, sheet, sheet.getWidth(), l, tp, w, h, drawX, drawY);
    }

    private void drawNpcEntity(Graphics g, int i, int ex, int ey) {
        int t = npcType[i];
        int d = npcDir[i];
        int st = npcState[i];
        int tm = npcTimer[i];
        int symOrigH = (imgNpcSym != null) ? imgNpcSym.getWidth() : 0;

        // 1. Common NpcSym objects (surf == 20)
        if (t == 1 && imgNpcSym != null) { // XP Crystal
            if (tm > 400 && ((tm >> 1) & 1) == 1) return;                    // blinks before it disappears
            int row = (npcExp[i] == 20) ? 48 : (npcExp[i] == 5 ? 32 : 16);   // bigger crystals use lower rows
            drawLandscapeRegion(g, imgNpcSym, symOrigH, ((tm >> 2) % 6) << 4, row, 16, 16, ex, ey);
            return;
        } else if (t == 15 && imgNpcSym != null) { // Chest
            drawLandscapeRegion(g, imgNpcSym, symOrigH, (st > 0) ? 256 : 240, 0, 16, 16, ex, ey);
            return;
        } else if (t == 16 && imgNpcSym != null) { // Save Disk
            drawLandscapeRegion(g, imgNpcSym, symOrigH, 96 + (((animTick >> 2) & 7) << 4), 16, 16, 16, ex, ey);
            return;
        } else if (t == 17 && imgNpcSym != null) { // Refill Station
            drawLandscapeRegion(g, imgNpcSym, symOrigH, 288, 0, 16, 16, ex, ey);
            return;
        }

        // 1b. every NPC type whose sprite rectangles were found in the exe
        if (t != 1 && t != 15 && t != 16 && hasRects(t)) {
            drawNpcByRect(g, i, ex, ey);
            return;
        }

        // 2. Named characters and bosses with exact sheet coordinates and sizes
        int surf = (npcSurfTbl != null && t >= 0 && t < npcSurfTbl.length) ? (npcSurfTbl[t] & 0xFF) : 21;
        // surface ids: 20 = NpcSym, 21 = stage NPC sheet 1, 22 = stage NPC sheet 2 / boss, 23 = NpcRegu
        Image sheet = (surf == 23) ? imgNpcRegu
                    : ((surf == 22) ? imgNpc2
                    : ((surf == 21) ? imgNpc1 : imgNpcSym));
        if (sheet == null) return;
        int shH = sheet.getWidth();

        int w = Math.max(16, tblView(t, 0) + tblView(t, 2));
        int h = Math.max(16, tblView(t, 1) + tblView(t, 3));
        int sx = 0;
        int sy = (d == 0) ? 0 : h;

        switch (t) {
            case 9: case 12: case 36: case 68: case 169: // Balrog (40x24 on NpcRegu)
                w = 40; h = 24;
                sy = (d == 0) ? 0 : 24;
                if (st == 2 || npcVy[i] != 0) sx = 80;
                else if (st == 1) sx = ((tm >> 2) & 1) * 40;
                else sx = ((tm & 63) > 56) ? 40 : 0;
                break;
            case 5: case 88: // Igor & Igor Boss (40x40 on NpcGuest)
                w = 40; h = 40;
                sy = (d == 0) ? 0 : 40;
                if (st == 1) sx = 120;
                else if (st == 2) sx = 160;
                else if (npcVx[i] != 0) sx = (((tm >> 3) & 1) + 1) * 40;
                else sx = 0;
                break;
            case 55: case 61: case 131: // Toroko (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = (d == 0) ? 64 : 80;
                sx = (st == 1 || npcVx[i] != 0) ? ((((tm >> 2) & 1) + 1) * 16) : 0;
                break;
            case 140: // Frenzy Toroko Boss (32x32 on NpcRavil)
                w = 32; h = 32;
                sy = (d == 0) ? 0 : 32;
                sx = (st == 2) ? 64 : (((tm >> 3) & 1) * 32);
                break;
            case 114: case 118: case 122: case 184: case 220: case 253: case 311: // Curly Brace (16x16 on NpcCurly)
                if (t == 184 || (t == 118 && st == 1)) {
                    w = 24; h = 16; sx = 0; sy = 96;
                } else {
                    w = 16; h = 16;
                    sy = (d == 0) ? 0 : 16;
                    sx = (npcVx[i] != 0) ? ((((tm >> 2) & 1) + 1) * 16) : 0;
                }
                break;
            case 117: // Curly Boss (24x24 on NpcCurly)
                w = 24; h = 24;
                sy = (d == 0) ? 32 : 56;
                sx = (st == 2) ? 48 : (((tm >> 3) & 1) * 24);
                break;
            case 66: case 67: case 82: // Misery (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = (d == 0) ? 96 : 112;
                sx = ((tm >> 3) & 1) * 16;
                break;
            case 247: case 284: // Misery Boss (24x24 on NpcMiza)
                w = 24; h = 24;
                sy = (d == 0) ? 0 : 24;
                sx = ((tm >> 3) & 3) * 24;
                break;
            case 53: case 74: case 75: // Kazuma (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = (d == 0) ? 128 : 144;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 50: case 76: case 130: case 145: // King (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = (d == 0) ? 160 : 176;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 42: case 92: case 132: case 141: case 280: // Sue Sakamoto (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = (d == 0) ? 192 : 208;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 113: case 149: // Professor Booster (16x16 on NpcRegu)
                w = 16; h = 16;
                sy = 224;
                sx = (d == 0) ? 0 : 32;
                break;
            case 52: // Hermit Gunsmith (16x16 on NpcGuest)
                w = 16; h = 16;
                sy = (d == 0) ? 160 : 176;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 62: // Santa (16x16 on NpcGuest)
                w = 16; h = 16;
                sy = (d == 0) ? 80 : 96;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 63: // Chaco (16x16 on NpcGuest)
                w = 16; h = 16;
                sy = (d == 0) ? 112 : 128;
                sx = ((tm >> 4) & 1) * 16;
                break;
            case 104: // Balfrog Boss (64x64 on NpcBllg)
                w = 64; h = 64;
                sy = (d == 0) ? 0 : 64;
                sx = (st == 2) ? 128 : ((st == 1) ? 64 : 0);
                break;
            case 47: // Omega Boss (72x56)
                w = 72; h = 56;
                sx = 0; sy = 0;
                break;
            case 179: // The Core Boss (64x96 on NpcAlmo1)
                w = 64; h = 96;
                sx = (st == 1) ? 64 : 0;
                sy = 0;
                break;
            case 268: case 269: case 270: // The Doctor / Muscle Doctor (24x32 or 40x40 on NpcDr)
                if (t == 268) { w = 24; h = 32; } else { w = 40; h = 40; }
                sy = (d == 0) ? 0 : h;
                sx = ((tm >> 3) & 1) * w;
                break;
            case 64: case 94: case 138: case 174: // Critters
                w = 16; h = 16;
                sy = (d == 0) ? 0 : 16;
                sx = (st == 2) ? 32 : (st == 1 ? 16 : 0);
                break;
            case 58: case 59: case 60: case 91: // Bats / Basu
                w = 16; h = 16;
                sy = (d == 0) ? 32 : 48;
                sx = ((tm >> 2) & 1) * 16;
                break;
            case 13: // Behemoth (32x24 on NpcCemet)
                w = 32; h = 24;
                sy = (d == 0) ? 112 : 136;
                sx = ((tm >> 3) & 1) * 32;
                break;
            case 96: // Sand Croc (48x32 on NpcSand)
                w = 48; h = 32;
                sy = (d == 0) ? 0 : 32;
                sx = (st == 1) ? 48 : 0;
                break;
            default:
                // The sprite rectangle of every other NPC type lives in the exe's code, not in a data
                // file.  Drawing "the first sprite of the sheet" (what v1 did) puts the wrong picture
                // on dozens of different NPC types, so unknown types are simply not drawn.
                return;
        }

        // Entity coordinates are the tile CENTRE in the original: centre = (ex + 8, ey + 8).
        // Centre the sprite horizontally and place its feet at centre + view.bottom.
        int drawX = ex + 8 - (w >> 1);
        int drawY = ey + 8 + tblView(t, 3) - h;
        drawLandscapeRegion(g, sheet, shH, sx, sy, w, h, drawX, drawY);
    }

    private void paintIntro(Graphics g) {
        g.setColor(0x000000);
        g.fillRect(0, 0, 240, 320);
        if (imgPixel != null) {
            int w = imgPixel.getHeight();
            int h = imgPixel.getWidth();
            drawLandscapeRegion(g, imgPixel, h, 0, 0, w, h, (320 - w) >> 1, (240 - h) >> 1);
        } else {
            Graphics mg = textBox.getSharedGraphics();
            mg.setColor(0x000000);
            mg.fillRect(0, 0, 244, 120);
            mg.setColor(0xFFFFFF);
            mg.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM));
            mg.drawString("Studio Pixel presents", 48, 50, Graphics.TOP | Graphics.LEFT);
            textBox.markCampDirty();
            g.drawRegion(textBox.getSharedBuffer(), 0, 0, 244, 120, Sprite.TRANS_ROT90, 60, 38, Graphics.TOP | Graphics.LEFT);
        }
    }

    private void paintTitle(Graphics g) {
        g.setColor(0x202028);
        g.fillRect(0, 0, 240, 320);
        if (imgTitle != null) {
            int w = Math.min(144, imgTitle.getHeight());
            int h = Math.min(40, imgTitle.getWidth());
            drawLandscapeRegion(g, imgTitle, imgTitle.getWidth(), 0, 0, w, h, (320 - w) >> 1, 44);
        }

        Graphics mg = textBox.getSharedGraphics();
        mg.setColor(0x202028);
        mg.fillRect(0, 0, 244, 120);
        mg.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM));
        mg.setColor(0xFFFFFF);
        if (imgTitle == null) {
            mg.drawString("洞窟物語 - Cave Story", 46, 8, Graphics.TOP | Graphics.LEFT);
        }
        mg.drawString("はじめから", 84, 42, Graphics.TOP | Graphics.LEFT);
        mg.setColor(hasSaveFile ? 0xFFFFFF : 0x777777);
        mg.drawString("つづきから", 84, 72, Graphics.TOP | Graphics.LEFT);
        textBox.markCampDirty();

        g.drawRegion(textBox.getSharedBuffer(), 0, 0, 244, 120, Sprite.TRANS_ROT90, 30, 38, Graphics.TOP | Graphics.LEFT);

        // footnote (the screen is drawn rotated, so use the shared buffer like the menu text)
        Graphics fg = textBox.getSharedGraphics();
        fg.setColor(0x202028);
        fg.fillRect(0, 0, 244, 40);
        fg.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));
        fg.setColor(0xA0A0B0);
        fg.drawString("Cave Story (c) 2004 Studio Pixel", 6, 4, Graphics.TOP | Graphics.LEFT);
        fg.drawString("Ported by TsuuGW", 6, 20, Graphics.TOP | Graphics.LEFT);
        textBox.markCampDirty();
        g.drawRegion(textBox.getSharedBuffer(), 0, 0, 244, 40, Sprite.TRANS_ROT90, 2, 38, Graphics.TOP | Graphics.LEFT);

        int curY = (titleCursor == 0) ? 130 : 160;
        int f = ((introTimer++ >> 2) & 1) == 0 ? 0 : 1;
        if (imgMyChar != null) {
            drawLandscapeRegion(g, imgMyChar, imgMyChar.getWidth(), f << 4, 16, 16, 16, 98, curY);
        }
    }

    private void paintCredits(Graphics g) {
        g.setColor(0x000010);
        g.fillRect(0, 0, 240, 320);

        if (imgCreditIllust != null) {
            int w = Math.min(160, imgCreditIllust.getHeight());
            int h = Math.min(240, imgCreditIllust.getWidth());
            drawLandscapeRegion(g, imgCreditIllust, imgCreditIllust.getWidth(), 0, 0, w, h, 0, 0);
        }

        if (credG != null && credBuffer != null) {
            credG.setColor(0x000010);
            credG.fillRect(0, 0, 168, 240);
            credG.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));

            for (int i = 0; i < MAX_CRED_LINES; i++) {
                if (!credActive[i]) continue;
                String line = credText[i];
                if (line.startsWith("[") && line.endsWith("]")) {
                    credG.setColor(0x88CCFF);
                } else {
                    credG.setColor(0xFFFFFF);
                }
                credG.drawString(line, credX[i], credY[i], Graphics.TOP | Graphics.LEFT);
            }

            if (creditEnded || creditPaused) {
                credG.setColor(0xFFFF80);
                credG.drawString("5: タイトルへ", 48, 218, Graphics.TOP | Graphics.LEFT);
            }

            g.drawRegion(credBuffer, 0, 0, 168, 240, Sprite.TRANS_ROT90, 0, 152, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** blit a rectangle of the original TextBox sheet (original coordinates, y >= 24) */
    private void hudBlit(Graphics g, int sx, int sy, int w, int h, int dx, int dy) {
        if (w <= 0) return;
        drawLandscapeRegion(g, imgHud, 80, sx, sy - 24, w, h, dx, dy);
    }

    /** right-aligned number made of 8x8 digits; the digit row is y=56 (white) or y=64 (red) */
    private void hudNumber(Graphics g, int val, int xRight, int y, boolean red) {
        if (val < 0) val = 0;
        int x = xRight;
        do {
            x -= 8;
            hudBlit(g, (val % 10) * 8, red ? 64 : 56, 8, 8, x, y);
            val /= 10;
        } while (val > 0 && x > 0);
    }

    private void paintHud(Graphics g) {
        if (imgHud == null) {
            paintHudPlain(g);
            return;
        }
        if (armCount > 0 && imgArms != null) {
            int code = armsCode[selectedArm];
            int lv = armsLevel[selectedArm];
            drawLandscapeRegion(g, imgArms, imgArms.getWidth(), code << 4, 0, 16, 16, 16, 8);
            // ammo: current over "/" max, or "--" for weapons without ammo
            if (armsMaxAmmo[selectedArm] > 0) {
                hudNumber(g, armsAmmo[selectedArm], 72, 8, false);
                hudBlit(g, 72, 48, 8, 8, 40, 16);
                hudNumber(g, armsMaxAmmo[selectedArm], 72, 16, false);
            } else {
                hudBlit(g, 80, 48, 16, 8, 48, 8);
                hudBlit(g, 72, 48, 8, 8, 40, 16);
                hudBlit(g, 80, 48, 16, 8, 48, 16);
            }
            // Lv / exp bar
            hudBlit(g, 80, 80, 16, 8, 16, 28);
            if (lv < 1) lv = 1;
            hudNumber(g, lv, 40, 28, false);
            int[] req = armsReq(code);
            hudBlit(g, 0, 72, 40, 8, 40, 28);
            int need = req[lv - 1];
            int fillW = (armsExp[selectedArm] * 40) / need;
            if (fillW > 40) fillW = 40;
            hudBlit(g, 0, 80, fillW, 8, 40, 28);
            if (lv >= 3 && armsExp[selectedArm] >= need) hudBlit(g, 40, 72, 40, 8, 40, 28);
        }

        // HP: heart + number in the case, red bar, yellow "recent damage" bar
        hudBlit(g, 0, 40, 64, 8, 16, 40);
        int hpW = (maxHp > 0) ? ((hp * 40) / maxHp) : 0;
        if (hpW <= 0 && hp > 0) hpW = 1;
        hudBlit(g, 0, 32, hpW, 8, 40, 40);                 // yellow underlay
        hudBlit(g, 0, 24, hpW, 8, 40, 40);                 // red bar
        hudNumber(g, hp, 40, 40, hp <= 5 && ((animTick >> 3) & 1) == 0 && hp > 0);

        if (inWater) {
            g.setColor(0x002050);
            fillLandscapeRect(g, 120, 12, 82, 8);
            g.setColor(0x40C0FF);
            fillLandscapeRect(g, 121, 13, (airTicks * 80) / 1000, 6);
        }
    }

    /** health bar for the toughest enemy on screen (boss fights) */
    private void paintBossBar(Graphics g) {
        if (imgHud == null || npcLifeTbl == null) return;
        int camGuess = 0;
        for (int i = 0; i < npcCount; i++) {
            if (!isEntityActive(i) || npcHp[i] <= 0) continue;
            int t = npcType[i];
            if ((npcBits[i] & 0x20) == 0 || (npcBits[i] & 0x04) != 0) continue;
            int max = npcLifeTbl[t];
            if (max < 40) continue;
            int dx = Math.abs(((npcX[i] >> 9) + 8) - ((px >> 9) + 8));
            int dy = Math.abs(((npcY[i] >> 9) + 8) - ((py >> 9) + 8));
            if (dx > 160 || dy > 120) continue;
            int w = (npcHp[i] * 80) / max;
            if (w <= 0) w = 1;
            g.setColor(0x000000);
            fillLandscapeRect(g, 95, 217, 130, 12);
            hudBlit(g, 0, 48, 24, 8, 98, 219);          // "てき" label
            g.setColor(0x400000);
            fillLandscapeRect(g, 125, 219, 82, 8);
            hudBlit(g, 0, 32, w, 8, 126, 219);
            hudBlit(g, 0, 24, w, 8, 126, 219);
            return;
        }
    }

    /** bullets: Snake and Polar Star use the real sprites, everything else is a few cheap rectangles */
    // ---- weapon shot sprites: one small atlas per weapon, loaded the first time that weapon fires
    private int[][] bulAtlas = new int[14][];
    private Image[] imgBul = new Image[14];
    private boolean[] imgBulTried = new boolean[14];

    private void loadBulletIndex() {
        try {
            java.io.InputStream is = getClass().getResourceAsStream("/bulrects.bin");
            if (is == null) return;
            java.io.DataInputStream in = new java.io.DataInputStream(is);
            int fams = in.readUnsignedByte();
            for (int f = 0; f < fams; f++) {
                int code = in.readUnsignedByte();
                int n = in.readUnsignedByte();
                int[] a = new int[n * 6];
                for (int k = 0; k < n * 6; k++) a[k] = in.readUnsignedShort();
                if (code < 14) bulAtlas[code] = a;
            }
            in.close();
        } catch (Exception e) {
            // shots fall back to plain shapes
        }
    }

    /** draws sprite (sx,sy,w,h) of the original Bullet sheet centred on (cx,cy); false when the atlas lacks it */
    private boolean drawBulSprite(Graphics g, int code, int sx, int sy, int w, int h, int cx, int cy) {
        int[] a = (code >= 0 && code < 14) ? bulAtlas[code] : null;
        if (a == null) return false;
        if (imgBul[code] == null) {
            if (imgBulTried[code]) return false;
            imgBulTried[code] = true;
            try { imgBul[code] = Image.createImage("/B" + code + ".png"); } catch (Exception e) { return false; }
        }
        for (int k = 0; k < a.length; k += 6) {
            if (a[k] == sx && a[k + 1] == sy && a[k + 2] == w && a[k + 3] == h) {
                Image im = imgBul[code];
                drawLandscapeRegion(g, im, im.getWidth(), a[k + 4], a[k + 5], w, h, cx - (w >> 1), cy - (h >> 1));
                return true;
            }
        }
        return false;
    }

    /** the sprite the original ActBullet_* code would pick; false when this shot has no sprite */
    private boolean drawBulletSprite(Graphics g, int i, int cx, int cy) {
        int code = bulCode[i];
        int lv = bulLevel[i];
        int d = bulDir[i];
        int age = bulAge[i];
        boolean horiz = (d == 0 || d == 2);
        switch (code) {
            case 1: {   // snake
                if (lv <= 1) {
                    int an = (age + i) & 3;
                    int[] ord = (d == 0) ? SNAKE_L : SNAKE_R;
                    int k = ord[an];
                    if (k < 0) return true;           // the original's degenerate frame draws nothing
                    return drawBulSprite(g, 1, 120 + ((k & 1) << 4), 64 + ((k >> 1) << 4), 16, 16, cx, cy);
                }
                return drawBulSprite(g, 1, 192 + ((age % 3) << 4), 16, 16, 16, cx, cy);
            }
            case 2:
                return drawBulSprite(g, 2, polarX(lv, horiz), polarY(lv), 16, 16, cx, cy);
            case 3: {   // fireball
                if (lv <= 1) {
                    int an = age & 3;
                    return drawBulSprite(g, 3, 128 + (an << 4), (d == 0) ? 0 : 16, 16, 16, cx, cy);
                }
                int an = age % 3;
                if (d != 0) an = 2 - an;
                return drawBulSprite(g, 3, 192 + (an << 4), 16, 16, 16, cx, cy);
            }
            case 4:
                return drawBulSprite(g, 4, 64 + (d << 4), (lv - 1) << 4, 16, 16, cx, cy);
            case 5:
                return drawBulSprite(g, 5, d << 4, (lv - 1) << 4, 16, 16, cx, cy);
            case 10:
                return drawBulSprite(g, 10, ((lv == 2) ? 184 : 120) + (d << 4), 96, 16, 16, cx, cy);
            case 7: {   // bubbler
                int an = age >> 2;
                if (an > 3) an = 3;
                if (lv <= 1) return drawBulSprite(g, 7, 192 + (an << 3), 0, 8, 8, cx, cy);
                if (lv == 2) return drawBulSprite(g, 7, 192 + (an << 3), 8, 8, 8, cx, cy);
                return drawBulSprite(g, 7, 240 + ((an & 1) << 3), 16 + ((an >> 1) << 3), 8, 8, cx, cy);
            }
            case 9: {   // blade
                if (lv <= 1) {
                    int an = (age >> 1) & 3;
                    return drawBulSprite(g, 9, ((d == 0) ? 0 : 64) + (an << 4), 48, 16, 16, cx, cy);
                }
                if (lv == 2) {
                    int an = (age >> 1) & 3;
                    return drawBulSprite(g, 9, 160 + (an << 3) * 3, (d == 0) ? 48 : 72, 24, 24, cx, cy);
                }
                int an = (age >> 2) & 1;
                int sx, sy;
                switch (d) {
                    case 0: sx = 272 + an * 24; sy = 0; break;
                    case 1: sx = an == 0 ? 272 : 296; sy = an == 0 ? 48 : 0; break;
                    case 2: sx = 272 + an * 24; sy = 24; break;
                    default: sx = 296; sy = an == 0 ? 48 : 24; break;
                }
                return drawBulSprite(g, 9, sx, sy, 24, 24, cx, cy);
            }
            case 12: {  // nemesis
                int an = (age >> 1) & 1;
                int offY = ((lv - 1) / 2) * 32, offX = ((lv - 1) % 2) * 128;
                switch (d) {
                    case 0: return drawBulSprite(g, 12, offX, 112 + an * 16 + offY, 32, 16, cx, cy);
                    case 1: return drawBulSprite(g, 12, 32 + an * 16 + offX, 112 + offY, 16, 32, cx, cy);
                    case 2: return drawBulSprite(g, 12, 64 + offX, 112 + an * 16 + offY, 32, 16, cx, cy);
                    default: return drawBulSprite(g, 12, 96 + an * 16 + offX, 112 + offY, 16, 32, cx, cy);
                }
            }
            case 13: {  // spur
                if (!bulPierce[i]) return drawBulSprite(g, 13, polarX(lv, horiz), polarY(lv), 16, 16, cx, cy);
                int an = age % 3;
                int sx = ((lv == 1) ? 192 : (lv == 2 ? 216 : 240)) + (an << 3);
                return drawBulSprite(g, 13, sx, horiz ? 32 : 40, 8, 8, cx, cy);
            }
        }
        return false;
    }

    private static final int[] SNAKE_L = {-1, 2, 1, 0};
    private static final int[] SNAKE_R = {0, 1, 2, -1};

    private int polarX(int lv, boolean horiz) {
        int base = (lv <= 1) ? 128 : (lv == 2 ? 160 : 128);
        return base + (horiz ? 0 : 16);
    }

    private int polarY(int lv) {
        return (lv >= 3) ? 48 : 32;
    }

    private void drawBullet(Graphics g, int i, int bx, int by) {
        int code = bulCode[i];
        int lv = bulLevel[i];
        boolean horiz = (bulDir[i] == 0 || bulDir[i] == 2);
        if (drawBulletSprite(g, i, bx + 8, by + 8)) return;
        int cx = bx + 8, cy = by + 8;
        switch (code) {
            case 3:  // fireball
                g.setColor(0xFF7010); fillLandscapeRect(g, cx - 4, cy - 4, 8, 8);
                g.setColor(0xFFE040); fillLandscapeRect(g, cx - 2, cy - 2, 4, 4);
                break;
            case 4:  // machine gun
                g.setColor(lv == 3 ? 0xFFFFFF : 0xFFE860);
                if (horiz) fillLandscapeRect(g, cx - 5, cy - 1, 10, lv == 1 ? 2 : 3);
                else fillLandscapeRect(g, cx - 1, cy - 5, lv == 1 ? 2 : 3, 10);
                break;
            case 5: case 10: {  // missiles
                int l = (code == 10) ? 12 : 10, t = (code == 10) ? 5 : 4;
                g.setColor(0xA0A8B0);
                if (horiz) fillLandscapeRect(g, cx - (l >> 1), cy - (t >> 1), l, t);
                else fillLandscapeRect(g, cx - (t >> 1), cy - (l >> 1), t, l);
                g.setColor(0xFF8020);
                if (horiz) fillLandscapeRect(g, cx + (bulDir[i] == 2 ? -(l >> 1) - 2 : (l >> 1)), cy - 1, 2, 2);
                else fillLandscapeRect(g, cx - 1, cy + (bulDir[i] == 3 ? -(l >> 1) - 2 : (l >> 1)), 2, 2);
                break;
            }
            case 7: {  // bubbler
                int r = 2 + lv;
                g.setColor(0x80C8FF); fillLandscapeRect(g, cx - r, cy - r, r * 2, r * 2);
                g.setColor(0xE0F4FF); fillLandscapeRect(g, cx - r + 1, cy - r + 1, 2, 2);
                break;
            }
            case 9:  // blade
                g.setColor(lv == 3 ? 0xA0C0FF : 0xFFFFFF);
                if (horiz) fillLandscapeRect(g, cx - 7, cy - 3, 14, 6); else fillLandscapeRect(g, cx - 3, cy - 7, 6, 14);
                g.setColor(0x50A050);
                fillLandscapeRect(g, cx - 2, cy - 2, 4, 4);
                break;
            case 12:  // nemesis
                g.setColor(0xB080FF);
                if (horiz) fillLandscapeRect(g, cx - 8, cy - 2, 16, 4); else fillLandscapeRect(g, cx - 2, cy - 8, 4, 16);
                g.setColor(0xFFFFFF);
                fillLandscapeRect(g, cx - 1, cy - 1, 2, 2);
                break;
            case 13:  // spur
                g.setColor(bulPierce[i] ? 0x80FFFF : 0xC0F0FF);
                if (bulPierce[i]) {
                    if (horiz) fillLandscapeRect(g, cx - 10, cy - 4, 20, 8); else fillLandscapeRect(g, cx - 4, cy - 10, 8, 20);
                } else {
                    if (horiz) fillLandscapeRect(g, cx - 5, cy - 2, 10, 4); else fillLandscapeRect(g, cx - 2, cy - 5, 4, 10);
                }
                break;
            default:
                g.setColor(0xFFFFFF); fillLandscapeRect(g, cx - 2, cy - 2, 4, 4);
        }
    }

    private void paintHudPlain(Graphics g) {
        if (armCount > 0 && imgArms != null) {
            int code = armsCode[selectedArm];
            drawLandscapeRegion(g, imgArms, imgArms.getWidth(), code << 4, 0, 16, 16, 12, 10);
        }
        g.setColor(0x400000);
        fillLandscapeRect(g, 12, 30, 62, 8);
        int hpW = (maxHp > 0) ? ((hp * 60) / maxHp) : 0;
        g.setColor(0xFF3030);
        fillLandscapeRect(g, 13, 31, hpW, 6);
    }

    private void paintBackground(Graphics g, int camX, int camY) {
        if (imgBack == null) return;
        int bw = imgBack.getHeight();     // sheet is stored rotated: original width = rotated height
        int bh = imgBack.getWidth();
        if (bw <= 0 || bh <= 0) return;
        int ox = 0;
        int oy = 0;
        if (bkType != 0) {                // parallax: background moves at half the camera speed
            ox = -((camX >> 1) % bw);
            oy = -((camY >> 1) % bh);
            if (ox > 0) ox -= bw;
            if (oy > 0) oy -= bh;
        }
        for (int y = oy; y < 240; y += bh) {
            for (int x = ox; x < 320; x += bw) {
                drawLandscapeRegion(g, imgBack, bh, 0, 0, bw, bh, x, y);
            }
        }
    }

    private void paintFade(Graphics g) {
        if (fadeStep <= 0) return;
        g.setColor(0x000010);
        if (fadeStep >= 16) {
            g.fillRect(0, 0, 240, 320);
            return;
        }
        int w = (320 * fadeStep) >> 4;
        int h = (240 * fadeStep) >> 4;
        switch (fadeDir) {
            case 0: fillLandscapeRect(g, 0, 0, w, 240); break;
            case 1: fillLandscapeRect(g, 0, 0, 320, h); break;
            case 2: fillLandscapeRect(g, 320 - w, 0, w, 240); break;
            case 3: fillLandscapeRect(g, 0, 240 - h, 320, h); break;
            default:
                int hw = w >> 1;
                int hh = h >> 1;
                fillLandscapeRect(g, 0, 0, hw, 240);
                fillLandscapeRect(g, 320 - hw, 0, hw, 240);
                fillLandscapeRect(g, 0, 0, 320, hh);
                fillLandscapeRect(g, 0, 240 - hh, 320, hh);
                break;
        }
    }

    protected void paint(Graphics g) {
        if (errLines != null) {
            paintError(g);
            return;
        }
        try {
            paintGame(g);
            paintDiag(g);
        } catch (Throwable t) {
            fail(t);
            paintError(g);
        }
    }

    private void paintGame(Graphics g) {
        if (gameMode == MODE_INTRO) {
            paintIntro(g);
            return;
        }
        if (gameMode == MODE_TITLE) {
            paintTitle(g);
            return;
        }
        if (gameMode == MODE_CREDITS) {
            paintCredits(g);
            return;
        }

        g.setColor(0x000010);
        g.fillRect(0, 0, 240, 320);
        if (mapTiles == null || imgTileset == null || imgMyChar == null) return;

        if (fadeStep < 16) {
            int targetX = px >> 9;
            int targetY = py >> 9;
            if (focusNpcEvent > 0) {
                for (int i = 0; i < npcCount; i++) {
                    if (npcAlive[i] && npcEvent[i] == focusNpcEvent) {
                        targetX = npcX[i] >> 9;
                        targetY = npcY[i] >> 9;
                        break;
                    }
                }
            }

            int camX = targetX - 152;
            int camY = targetY - 112;
            if (quakeTicks > 0) {
                camX += ((quakeTicks & 1) == 0) ? -2 : 2;
                camY += ((quakeTicks & 2) == 0) ? -2 : 2;
            }
            int maxCamX = (mapW << 4) - 320;
            int maxCamY = (mapH << 4) - 240;
            if (camX < 0) camX = 0;
            if (camY < 0) camY = 0;
            if (maxCamX > 0 && camX > maxCamX) camX = maxCamX;
            if (maxCamY > 0 && camY > maxCamY) camY = maxCamY;

            paintBackground(g, camX, camY);

            int startCol = camX >> 4;
            int startRow = camY >> 4;
            int tilesetOrigH = imgTileset.getWidth();

            for (int r = startRow; r <= startRow + 16 && r < mapH; r++) {
                for (int c = startCol; c <= startCol + 21 && c < mapW; c++) {
                    int tile = mapTiles[r * mapW + c] & 0xFF;
                    if (tile == 0) continue;
                    int sx = (tile & 15) << 4;
                    int sy = (tile >> 4) << 4;
                    drawLandscapeRegion(g, imgTileset, tilesetOrigH, sx, sy, 16, 16, (c << 4) - camX, (r << 4) - camY);
                }
            }

            for (int i = 0; i < npcCount; i++) {
                if (!isEntityActive(i)) continue;
                drawNpcEntity(g, i, (npcX[i] >> 9) - camX, (npcY[i] >> 9) - camY);
            }

            for (int i = 0; i < MAX_BULLETS; i++) {
                if (bulAlive[i]) drawBullet(g, i, (bulX[i] >> 9) - camX, (bulY[i] >> 9) - camY);
            }

            if ((shockTicks & 2) == 0 && !playerHidden) {
                int[] walkFrames = {0, 1, 0, 2};
                int frame = grounded ? walkFrames[(animTick >> 2) & 3] : 1;
                if (vx == 0 && grounded) frame = 0;
                if (lookDir == 1) frame += 3;
                else if (lookDir == 3) frame = 6;

                int charOrigH = imgMyChar.getWidth();
                int charSrcX = frame << 4;
                int charSrcY = (dir == 0) ? 0 : 16;
                drawLandscapeRegion(g, imgMyChar, charOrigH, charSrcX, charSrcY, 16, 16, (px >> 9) - camX, (py >> 9) - camY);
            }

            if (imgCaret != null) {
                int carOrigH = imgCaret.getWidth();
                for (int i = 0; i < MAX_CARETS; i++) {
                    if (!carAlive[i]) continue;
                    int cx = (carX[i] >> 9) - camX;
                    int cy = (carY[i] >> 9) - camY;
                    int f = (carTick[i] >> 2) & 3;
                    drawLandscapeRegion(g, imgCaret, carOrigH, f << 4, 48, 16, 16, cx, cy);
                }
            }

            for (int i = 0; i < MAX_PART; i++) {
                if (partLife[i] <= 0) continue;
                int sz = partSize[i];
                if (partLife[i] < 6 && sz > 2) sz--;
                g.setColor(partCol[i]);
                fillLandscapeRect(g, (partX[i] >> 3) - camX - (sz >> 1), (partY[i] >> 3) - camY - (sz >> 1), sz, sz);
            }

            if (!tscActive && !tscKeyLock && gameMode == MODE_PLAY) {
                paintHud(g);
                paintBossBar(g);
            }
        }

        paintFade(g);
        if (gameMode == MODE_CAMP) {
            textBox.paintCampMenu(g, campSection, campCursor, armCount, armsCode, armsLevel,
                                  armsAmmo, armsMaxAmmo, itemCount, items);
        } else if (gameMode == MODE_TELEPORT) {
            textBox.paintTeleporterMenu(g, teleportCursor, permitCount, permitIndex);
        }
        if (gameMode != MODE_CAMP) textBox.paint(g);
        if (tscWaitYn && imgHud != null) {
            hudBlit(g, 152, 48, 88, 32, 224, 136);                       // red "はい / いいえ" box
            hudBlit(g, 112, 88, 16, 16, 224 + 4 + tscYnChoice * 41, 136 + 12);   // hand cursor
        }
    }

    // The world is drawn rotated 90 degrees clockwise on the portrait screen (hold the phone sideways,
    // d-pad "up" side on the left).  Seen that way:
    //   logical LEFT  = d-pad UP      logical RIGHT = d-pad DOWN
    //   logical UP    = d-pad RIGHT   logical DOWN  = d-pad LEFT
    // (v1 had UP and DOWN swapped.)  Number keys follow the same layout.
    private void setKey(int keyCode, boolean down) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}

        if (keyCode == -1 || action == UP || keyCode == KEY_NUM2) keyLeft = down;
        else if (keyCode == -2 || action == DOWN || keyCode == KEY_NUM8) keyRight = down;
        else if (keyCode == -4 || action == RIGHT || keyCode == KEY_NUM6) keyUp = down;
        else if (keyCode == -3 || action == LEFT || keyCode == KEY_NUM4) keyDown = down;
        else if (keyCode == KEY_NUM5 || keyCode == -5 || action == FIRE) keyJump = down;
        else if (keyCode == KEY_NUM7 || keyCode == KEY_NUM9) keyShoot = down;
        else if (keyCode == KEY_NUM0) keyInspect = down;
        else if (keyCode == KEY_NUM1 || keyCode == KEY_NUM3) keyNextArm = down;
        else if (keyCode == KEY_POUND || keyCode == -6) keyCamp = down;
        else if (keyCode == KEY_STAR && down) { if (sound != null && sound.toggleSfx()) sfx(18); }   // '*' = sound effects on/off
    }

    protected void keyPressed(int keyCode) {
        setKey(keyCode, true);
    }

    protected void keyReleased(int keyCode) {
        setKey(keyCode, false);
    }
    // ==== glue for the ported NPC code ====
    private int[] npcWait = new int[MAX_NPCS];
    private int[] npcAniNo = new int[MAX_NPCS];
    private int[] npcAniWait = new int[MAX_NPCS];
    private int[] npcTx = new int[MAX_NPCS];
    private int[] npcTy = new int[MAX_NPCS];
    private int[] npcC1 = new int[MAX_NPCS];
    private int[] npcC2 = new int[MAX_NPCS];
    private int[] npcShock = new int[MAX_NPCS];
    private int[] npcCol = new int[MAX_NPCS];
    private int[] npcPar = new int[MAX_NPCS];

    private void resetNpcAi(int i) {
        npcWait[i] = 0; npcAniNo[i] = 0; npcAniWait[i] = 0; npcTx[i] = 0; npcTy[i] = 0;
        npcC1[i] = 0; npcC2[i] = 0; npcShock[i] = 0; npcCol[i] = 0; npcPar[i] = -1;
    }

    private int par(int n) {
        int p = npcPar[n];
        return (p >= 0 && p < MAX_NPCS) ? p : n;
    }

    private void ensureSurf(int s) { if (s == 23) ensureNpcReguLoaded(); }

    private int rnd(int a, int b) { return a + rand(b - a + 1); }
    private boolean muteSfx;
    private void sfx(int n) { if (sound != null && !muteSfx) sound.playSfx(n); }
    private void sfxLoop(int n) { if (sound != null) sound.loopSfx(n); }
    private void sfxStop(int n) { if (sound != null) sound.stopSfx(n); }
    private int qFlag() { return grounded ? 8 : 0; }
    private int quoteDir() { return dir; }
    private boolean getFlag(int n) { return n >= 0 && n < flags.length && flags[n]; }
    private void quake(int n) { quakeTicks = n / 2; }
    private void flash(int a, int b, int c) { }
    private void expFx(int x, int y, int n) { }
    private void caret(int x, int y, int type, int d) { spawnCaret(x, y); }

    private int findEvent(int ev) {
        for (int n = 0; n < npcCount; n++) if (npcAlive[n] && npcEvent[n] == ev) return n;
        return -1;
    }

    private int setNpc(int code, int x, int y, int xm, int ym, int d, int parent) {
        int n = -1;
        for (int k = 0; k < MAX_NPCS; k++) {
            if (!npcAlive[k]) { n = k; break; }
        }
        if (n < 0) return -1;
        npcAlive[n] = true;
        npcType[n] = code;
        npcX[n] = x; npcY[n] = y; npcVx[n] = xm; npcVy[n] = ym;
        npcFlag[n] = 0; npcEvent[n] = 0;
        npcBits[n] = tblBits(code);
        initNpcStats(n);
        npcDir[n] = d;
        applySpawnOffset(n);
        npcPar[n] = parent;
        if (n >= npcCount) npcCount = n + 1;
        return n;
    }

    private void destroyFx(int x, int y, int w, int num) {
        spawnParticles((x >> 9) + 8, (y >> 9) + 8, Math.min(num, 8) + 2, 0xD0D0D0, 10, 22, 3);
    }
    private void destroyFxUp(int x, int y, int w, int num) { destroyFx(x, y, w, num); }
    private void vanishFx(int n) {
        destroyFx(npcX[n], npcY[n], 0x1000, 4);
        npcAlive[n] = false;
    }
    private void deleteNpcCode(int code, boolean fx) {
        for (int n = 0; n < npcCount; n++) {
            if (npcAlive[n] && npcType[n] == code) {
                if (fx) { sfx(destroyVoice(npcType[n])); destroyFx(npcX[n], npcY[n], 0x1000, 4); }
                npcAlive[n] = false;
            }
        }
    }
    private boolean changeMapPart(int x, int y, int part) {
        if (mapTiles != null && x >= 0 && y >= 0 && x < mapW && y < mapH) { mapTiles[y * mapW + x] = (byte) part; return true; }
        return false;
    }
    private int gSuperXpos, gSuperYpos;
    private void damageMyChar(int d) {
        boolean h = playerHidden;
        playerHidden = false;
        hurtQuote(d, (dir == 0) ? 0x280 : -0x280);
        if (hp > 0) playerHidden = h;
    }

    private int gsin(int a) { return (int) (Math.sin((a & 0xFF) * 6.2831998 / 256.0) * 512.0); }
    private int gcos(int a) { return gsin(a + 0x40); }
    private int garktan(int x, int y) {
        // angle in 1/256 turns of the vector (-x,-y) (CLDC 1.1 has no Math.atan2)
        double dx = -x, dy = -y;
        double ax = dx < 0 ? -dx : dx, ay = dy < 0 ? -dy : dy;
        if (ax == 0 && ay == 0) return 0;
        double r = (ay > ax) ? ax / ay : ay / ax;
        double a = r * (0.7853981 + 0.273 * (1.0 - r));
        if (ay > ax) a = 1.5707963 - a;
        if (dx < 0) a = 3.1415926 - a;
        if (dy < 0) a = -a;
        return ((int) (a * 256.0 / 6.2831853)) & 0xFF;
    }

    /** the original HitNpCharMap: pushes a non-ignoring NPC out of solid tiles and sets flag bits 1 (left) 2 (up) 4 (right) 8 (down) */
    private void npcHitMap(int n) {
        int t = npcType[n];
        int hb = nHit(n, 2) << 9;           // the original uses hit.back for both sides
        int ht = nHit(n, 1) << 9;
        int hd = nHit(n, 3) << 9;
        int fl = 0;
        boolean big = nHit(n, 2) > 8 || nHit(n, 1) > 8 || nHit(n, 3) > 8;
        int tx0 = (npcX[n] - (big ? 0x1000 : 0)) >> 13;
        int ty0 = (npcY[n] - (big ? 0x1000 : 0)) >> 13;
        int span = big ? 3 : 2;
        for (int jy = 0; jy < span; jy++) {
            for (int jx = 0; jx < span; jx++) {
                int tx = tx0 + jx, ty = ty0 + jy;
                if (tx < 0 || ty < 0 || tx >= mapW || ty >= mapH) continue;
                if (!isSolidForNpc(tx, ty)) continue;
                int x = tx * 16, y = ty * 16;
                if (npcY[n] - ht < (y + 5) * 0x200 && npcY[n] + hd > (y - 5) * 0x200
                    && npcX[n] - hb < (x + 8) * 0x200 && npcX[n] - hb > x * 0x200) {
                    npcX[n] = ((x + 8) * 0x200) + hb;
                    fl |= 1;
                }
                if (npcY[n] - ht < (y + 5) * 0x200 && npcY[n] + hd > (y - 5) * 0x200
                    && npcX[n] + hb > (x - 8) * 0x200 && npcX[n] + hb < x * 0x200) {
                    npcX[n] = ((x - 8) * 0x200) - hb;
                    fl |= 4;
                }
                if (npcX[n] - hb < (x + 5) * 0x200 && npcX[n] + hb > (x - 5) * 0x200
                    && npcY[n] - ht < (y + 8) * 0x200 && npcY[n] - ht > y * 0x200) {
                    npcY[n] = ((y + 8) * 0x200) + ht;
                    if (npcVy[n] < 0) npcVy[n] = 0;
                    fl |= 2;
                }
                if (npcX[n] - hb < (x + 5) * 0x200 && npcX[n] + hb > (x - 5) * 0x200
                    && npcY[n] + hd > (y - 8) * 0x200 && npcY[n] + hd < y * 0x200) {
                    npcY[n] = ((y - 8) * 0x200) - hd;
                    if (npcVy[n] > 0) npcVy[n] = 0;
                    fl |= 8;
                }
            }
        }
        // slopes: the foot point in world pixels
        int wx = (npcX[n] >> 9) + 8;
        int footY = (npcY[n] >> 9) + 8 + (hd >> 9);
        int slopeY = getSlopeFloorY(wx, footY);
        if (slopeY != -1 && footY >= slopeY - 1 && npcVy[n] >= 0) {
            npcY[n] = (slopeY - 8 - (hd >> 9)) << 9;
            npcVy[n] = 0;
            fl |= 8;
        }
        npcCol[n] = fl;
    }

    private void runPorted(int i, int t) {
        npcCol[i] = npcCol[i] & 0xFF;
        actNpcPort(i, t);
        if (!npcAlive[i]) return;
        npcAnim[i] = npcAniNo[i] + 1;
        if (npcShock[i] > 0) npcShock[i]--;
        if ((npcBits[i] & 0x08) == 0) npcHitMap(i);
        if (npcDmg[i] > 0 && npcHp[i] >= 0 && !tscActive) {
            int t2 = npcType[i];
            int rx = (nHit(i, 2) + 5) << 9;
            int ryT = (nHit(i, 1) + 8) << 9;
            int ryB = (nHit(i, 3) + 8) << 9;
            if (px > npcX[i] - rx && px < npcX[i] + rx && py > npcY[i] - ryT && py < npcY[i] + ryB) {
                hurtQuote(npcDmg[i], (px < npcX[i]) ? -0x280 : 0x280);
            }
        }
    }

    // ==== generated from CSE2 (MIT-licensed decompilation of Doukutsu) by tools/port_npcs.py ====
    // 4 Smoke
    private void act004(int N) {


	int deg;

	if (npcState[N] == 0)
	{
		// Move in random direction at random speed
		if (npcDir[N] == 0 || npcDir[N] == 1)
		{
			deg = rnd(0, 0xFF);
			npcVx[N] = gcos(deg) * rnd(0x200, 0x5FF) / 0x200;
			npcVy[N] = gsin(deg) * rnd(0x200, 0x5FF) / 0x200;
		}

		// Set state
		npcAniNo[N] = rnd(0, 4);
		npcAniWait[N] = rnd(0, 3);
		npcState[N] = 1;
	}
	else
	{
		// Slight drag
		npcVx[N] = (npcVx[N] * 20) / 21;
		npcVy[N] = (npcVy[N] * 20) / 21;

		// Move
		npcX[N] += npcVx[N];
		npcY[N] += npcVy[N];
	}

	// Animate
	if (++npcAniWait[N] > 4)
	{
		npcAniWait[N] = 0;
		++npcAniNo[N];
	}

	// Set framerect
	if (npcAniNo[N] > 7)
	{
		// Destroy if over
		npcAlive[N] = false;
	}
	else
	{
		if (npcDir[N] == 1)
			;
		if (npcDir[N] == 0)
			;
		if (npcDir[N] == 2)
			;
	}
    }

    // 62 Kazuma at computer
    private void act062(int N) {

	switch (npcState[N])
	{
		case 0:
			npcX[N] -= 4 * 0x200;
			npcY[N] += 16 * 0x200;
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			if (rnd(0, 80) == 1)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (rnd(0, 120) == 10)
			{
				npcState[N] = 3;
				npcWait[N] = 0;
				npcAniNo[N] = 2;
			}

			break;

		case 2:
			if (++npcWait[N] > 40)
			{
				npcState[N] = 3;
				npcWait[N] = 0;
				npcAniNo[N] = 2;
			}

			break;

		case 3:
			if (++npcWait[N] > 80)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	;
    }

    // 92 Sue at PC
    private void act092(int N) {

	switch (npcState[N])
	{
		case 0:
			npcX[N] -= 4 * 0x200;
			npcY[N] += 16 * 0x200;
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			if (rnd(0, 80) == 1)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (rnd(0, 120) == 10)
			{
				npcState[N] = 3;
				npcWait[N] = 0;
				npcAniNo[N] = 2;
			}

			break;

		case 2:
			if (++npcWait[N] > 40)
			{
				npcState[N] = 3;
				npcWait[N] = 0;
				npcAniNo[N] = 2;
			}

			break;

		case 3:
			if (++npcWait[N] > 80)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	;
    }

    // 12 Balrog (cutscene)
    private void act012(int N) {
	int i;
	int x, y;

	switch (npcState[N])
	{
		case 0:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 1;
			npcAniNo[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 100) == 0)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 16)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 10:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 11;
			npcAniNo[N] = 2;
			npcWait[N] = 0;
			npcTx[N] = 0;
			// Fallthrough
		case 11:
			if (++npcWait[N] > 30)
			{
				npcState[N] = 12;
				npcWait[N] = 0;
				npcAniNo[N] = 3;
				npcVy[N] = -0x800;
				npcBits[N] |= 8;
			}

			break;

		case 12:
			if (((npcCol[N] & 5) != 0))
				npcVx[N] = 0;

			if (npcY[N] < 0)
			{
				npcType[N] = 0;
				sfx(26);
				quake(30);
			}

			break;

		case 20:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 21;
			npcAniNo[N] = 5;
			npcWait[N] = 0;
			npcC1[N] = 0;

			for (i = 0; i < 4; ++i)
				setNpc(4, npcX[N] + (rnd(-12, 12) * 0x200), npcY[N] + (rnd(-12, 12) * 0x200), rnd(-341, 341), rnd(-0x600, 0), 0, -1);

			sfx(72);
			// Fallthrough
		case 21:
			npcTx[N] = 1;

			if (((npcCol[N] & 8) != 0))
				++npcWait[N];

			if (++npcC1[N] / 2 % 2 != 0)
				npcX[N] += 1 * 0x200;
			else
				npcX[N] -= 1 * 0x200;

			if (npcWait[N] > 100)
			{
				npcState[N] = 11;
				npcWait[N] = 0;
				npcAniNo[N] = 2;
			}

			npcVy[N] += 0x20;
			if (npcVy[N] > 0x5FF)
				npcVy[N] = 0x5FF;

			break;

		case 30:
			npcAniNo[N] = 4;

			if (++npcWait[N] > 100)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
			}

			break;

		case 40:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 41;
			npcWait[N] = 0;
			npcAniNo[N] = 5;
			// Fallthrough
		case 41:
			if (++npcAniWait[N] / 2 % 2 != 0)
				npcAniNo[N] = 5;
			else
				npcAniNo[N] = 6;

			break;

		case 42:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 43;
			npcWait[N] = 0;
			npcAniNo[N] = 6;
			// Fallthrough
		case 43:
			if (++npcAniWait[N] / 2 % 2 != 0)
				npcAniNo[N] = 7;
			else
				npcAniNo[N] = 6;

			break;

		case 50:
			npcAniNo[N] = 8;
			npcVx[N] = 0;
			break;

		case 60:
			npcState[N] = 61;
			npcAniNo[N] = 9;
			npcAniWait[N] = 0;
			// Fallthrough
		case 61:
			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;

				if (++npcAniNo[N] == 10 || npcAniNo[N] == 11)
					sfx(23);
			}

			if (npcAniNo[N] > 12)
				npcAniNo[N] = 9;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;

		case 70:
			npcState[N] = 71;
			npcWait[N] = 64;
			sfx(29);
			npcAniNo[N] = 13;
			// Fallthrough
		case 71:
			if (--npcWait[N] == 0)
				npcAlive[N] = false;

			break;

		case 80:
			npcC1[N] = 0;
			npcState[N] = 81;
			// Fallthrough
		case 81:
			if (++npcC1[N] / 2 % 2 != 0)
				npcX[N] += 1 * 0x200;
			else
				npcX[N] -= 1 * 0x200;

			npcAniNo[N] = 5;
			npcVx[N] = 0;
			npcVy[N] += 0x20;

			break;

		case 100:
			npcState[N] = 101;
			npcWait[N] = 0;
			npcAniNo[N] = 2;
			// Fallthrough
		case 101:
			if (++npcWait[N] > 20)
			{
				npcState[N] = 102;
				npcWait[N] = 0;
				npcAniNo[N] = 3;
				npcVy[N] = -0x800;
				npcBits[N] |= 8;
				deleteNpcCode(150, false);
				deleteNpcCode(117, false);
				setNpc(355, 0, 0, 0, 0, 0, N);
				setNpc(355, 0, 0, 0, 0, 1, N);
			}

			break;

		case 102:
		{
			x = npcX[N] / 0x200 / 0x10;
			y = npcY[N] / 0x200 / 0x10;

			if (y >= 0 && y < 35 && changeMapPart(x, y, 0))
			{
				changeMapPart(x - 1, y, 0);
				changeMapPart(x + 1, y, 0);
				sfx(44);
				quake(10);
			}

			if (npcY[N] < -32 * 0x200)
			{
				npcType[N] = 0;
				quake(30);
			}

			break;
		}
	}

	if ((npcTx[N] != 0) && rnd(0, 10) == 0)
		setNpc(4, npcX[N] + (rnd(-12, 12) * 0x200), npcY[N] + (rnd(-12, 12) * 0x200), rnd(-341, 341), rnd(-0x600, 0), 0, -1);

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];



	if (npcDir[N] == 0)
		;
	else
		;

	if (npcState[N] == 71)
	{
		;

		if (npcWait[N] % 2 != 0)
			;
	}
    }

    // 18 Door
    private void act018(int N) {
	int i;


	switch (npcState[N])
	{
		case 0:
			if (npcDir[N] == 0)
				;
			else
				;

			break;

		case 1:
			for (i = 0; i < 4; ++i)
				setNpc(4, npcX[N], npcY[N], rnd(-341, 341), rnd(-0x600, 0), 0, -1);

			npcState[N] = 0;
			;
			break;
	}
    }

    // 22 Teleporter
    private void act022(int N) {

	switch (npcState[N])
	{
		case 0:
			npcAniNo[N] = 0;
			break;

		case 1:
			if (++npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			break;
	}

	;
    }

    // 23 Teleporter lights
    private void act023(int N) {

	if (++npcAniWait[N] > 1)
	{
		npcAniWait[N] = 0;
		++npcAniNo[N];
	}

	if (npcAniNo[N] > 7)
		npcAniNo[N] = 0;

	;
    }

    // 38 Fireplace
    private void act038(int N) {

	switch (npcState[N])
	{
		case 0:
			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 3)
				npcAniNo[N] = 0;

			;
			break;

		case 10:
			npcState[N] = 11;
			destroyFx(npcX[N], npcY[N], (npcViewI[N * 4 + 2] << 9), 8);
			// Fallthrough
		case 11:
			;
			;
			break;
	}
    }

    // 40 Santa
    private void act040(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (npcX[N] - (32 * 0x200) < px && npcX[N] + (32 * 0x200) > px && npcY[N] - (32 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}
			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				npcAniNo[N]++;
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			break;

		case 5:
			npcAniNo[N] = 6;
			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 41 Busted Door
    private void act041(int N) {

	if (npcState[N] == 0)
	{
		++npcState[N];
		npcY[N] -= 1 * 0x10 * 0x200;	// Move one tile up
	}

	;
    }

    // 42 Sue
    private void act042(int N) {
	int n;



	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;

		case 5:
			npcAniNo[N] = 6;
			npcVx[N] = 0;
			break;

		case 6:
			sfx(50);
			npcWait[N] = 0;
			npcState[N] = 7;
			npcAniNo[N] = 7;
			// Fallthrough
		case 7:
			if (++npcWait[N] > 10)
				npcState[N] = 0;

			break;

		case 8:
			sfx(50);
			npcWait[N] = 0;
			npcState[N] = 9;
			npcAniNo[N] = 7;
			npcVy[N] = -0x200;

			if (npcDir[N] == 0)
				npcVx[N] = 0x400;
			else
				npcVx[N] = -0x400;

			// Fallthrough
		case 9:
			if (++npcWait[N] > 3 && ((npcCol[N] & 8) != 0))
			{
				npcState[N] = 10;

				if (npcDir[N] == 0)
					npcDir[N] = 2;
				else
					npcDir[N] = 0;
			}

			break;

		case 10:
			npcVx[N] = 0;
			npcAniNo[N] = 8;
			break;

		case 11:
			npcState[N] = 12;
			npcWait[N] = 0;
			npcAniNo[N] = 9;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 12:
			if (++npcAniWait[N] > 8)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 10)
				npcAniNo[N] = 9;

			break;

		case 13:
			npcAniNo[N] = 11;
			npcVx[N] = 0;
			npcVy[N] = 0;
			npcState[N] = 14;

			n = findEvent(501);

			if (n < 0)
			{
				npcState[N] = 0;
				break;
			}

			npcPar[N] = n;
			// Fallthrough
		case 14:
			if (npcDir[par(N)] == 0)
				npcDir[N] = 2;
			else
				npcDir[N] = 0;

			if (npcDir[par(N)] == 0)
				npcX[N] = npcX[par(N)] - (6 * 0x200);
			else
				npcX[N] = npcX[par(N)] + (6 * 0x200);

			npcY[N] = npcY[par(N)] + (4 * 0x200);

			if (npcAniNo[par(N)] == 2 || npcAniNo[par(N)] == 4)
				npcY[N] -= 1 * 0x200;

			break;

		case 15:
			npcState[N] = 16;
			setNpc(257, npcX[N] + (128 * 0x200), npcY[N], 0, 0, 0, -1);
			setNpc(257, npcX[N] + (128 * 0x200), npcY[N], 0, 0, 2, -1);
			npcVx[N] = 0;
			npcAniNo[N] = 0;
			// Fallthrough
		case 16:
			gSuperXpos = npcX[N] - (24 * 0x200);
			gSuperYpos = npcY[N] - (8 * 0x200);
			break;

		case 17:
			npcVx[N] = 0;
			npcAniNo[N] = 12;
			gSuperXpos = npcX[N];
			gSuperYpos = npcY[N] - (8 * 0x200);
			break;

		case 20:
			npcState[N] = 21;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 21:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x400;
			else
				npcVx[N] = 0x400;

			if (npcX[N] < px - (8 * 0x200))
			{
				npcDir[N] = 2;
				npcState[N] = 0;
			}

			break;

		case 30:
			npcState[N] = 31;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 31:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x400;
			else
				npcVx[N] = 0x400;

			break;

		case 40:
			npcState[N] = 41;
			npcAniNo[N] = 9;
			npcVy[N] = -0x400;
			break;
	}

	if (npcState[N] != 14)
	{
		npcVy[N] += 0x40;

		if (npcVx[N] > 0x400)
			npcVx[N] = 0x400;
		if (npcVx[N] < -0x400)
			npcVx[N] = -0x400;

		if (npcVy[N] > 0x5FF)
			npcVy[N] = 0x5FF;

		npcX[N] += npcVx[N];
		npcY[N] += npcVy[N];
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 55 Kazuma
    private void act055(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			break;

		case 5:
			npcAniNo[N] = 5;
			break;
	}

	npcVy[N] += 0x20;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 60 Toroko
    private void act060(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (npcX[N] - (16 * 0x200) < px && npcX[N] + (16 * 0x200) > px && npcY[N] - (16 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			if (((npcCol[N] & 1) != 0))
			{
				npcDir[N] = 2;
				npcVx[N] = 0x200;
			}

			if (((npcCol[N] & 4) != 0))
			{
				npcDir[N] = 0;
				npcVx[N] = -0x200;
			}

			if (npcDir[N] == 0)
				npcVx[N] = -0x400;
			else
				npcVx[N] = 0x400;

			break;

		case 6:
			npcState[N] = 7;
			npcWait[N] = 0;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			npcVy[N] = -0x400;
			// Fallthrough
		case 7:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			if (npcDir[N] == 0)
				npcVx[N] = -0x100;
			else
				npcVx[N] = 0x100;

			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
				npcState[N] = 3;

			break;

		case 8:
			npcAniNo[N] = 1;
			npcWait[N] = 0;
			npcState[N] = 9;
			npcVy[N] = -0x200;
			// Fallthrough
		case 9:
			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
				npcState[N] = 0;

			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 6;
			npcVy[N] = -0x400;
			sfx(50);

			if (npcDir[N] == 0)
				npcVx[N] = -0x100;
			else
				npcVx[N] = 0x100;

			break;

		case 11:
			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
			{
				npcState[N] = 12;
				npcAniNo[N] = 7;
				npcBits[N] |= 8192;
			}

			break;

		case 12:
			npcVx[N] = 0;
			break;
	}

	npcVy[N] += 0x40;

	if (npcVx[N] > 0x400)
		npcVx[N] = 0x400;
	if (npcVx[N] < -0x400)
		npcVx[N] = -0x400;

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 61 King
    private void act061(int N) {
	int i;



	switch (npcState[N])
	{
		case 0: // Stood
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2: // Blink
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 5: // Lying down
			npcAniNo[N] = 3;
			npcVx[N] = 0;
			break;

		case 6: // Being knocked-back
			npcState[N] = 7;
			npcWait[N] = 0;
			npcAniWait[N] = 0;
			npcVy[N] = -0x400;
			// Fallthrough
		case 7:
			npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			// If touching ground, enter 'lying down' state (the `act_wait` check is probably
			// so he doesn't do it before he even leaves the ground in the first place)
			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
				npcState[N] = 5;

			break;

		case 8: // Walking
			npcState[N] = 9;
			npcAniNo[N] = 4;
			npcAniWait[N] = 0;
			// Fallthrough
		case 9:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 7)
				npcAniNo[N] = 4;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;

		case 10: // Running
			npcState[N] = 11;
			npcAniNo[N] = 4;
			npcAniWait[N] = 0;
			// Fallthrough
		case 11:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 7)
				npcAniNo[N] = 4;

			if (npcDir[N] == 0)
				npcVx[N] = -0x400;
			else
				npcVx[N] = 0x400;

			break;

		case 20: // Spawn his sword, before entering his 'idle' state
			setNpc(145, 0, 0, 0, 0, 2, N);
			npcAniNo[N] = 0;
			npcState[N] = 0;
			break;

		case 30: // Flying through air after being attacked by Misery
			npcState[N] = 31;
			npcWait[N] = 0;
			npcAniWait[N] = 0;
			npcVy[N] = 0;
			// Fallthrough
		case 31:
			npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x600;
			else
				npcVx[N] = 0x600;

			if (((npcCol[N] & 1) != 0))
			{
				npcDir[N] = 2;
				npcState[N] = 7;
				npcWait[N] = 0;
				npcAniWait[N] = 0;
				npcVy[N] = -0x400;
				npcVx[N] = 0x200;
				sfx(71);
				destroyFx(npcX[N], npcY[N], 0x800, 4);
			}

			break;

		case 40: // Dying
			npcState[N] = 42;
			npcWait[N] = 0;
			npcAniNo[N] = 8;
			sfx(29);
			// Fallthrough
		case 42:
			if (++npcAniNo[N] > 9)
				npcAniNo[N] = 8;

			if (++npcWait[N] > 100)
			{
				for (i = 0; i < 4; ++i)
					setNpc(4, npcX[N] + (rnd(-12, 12) * 0x200), npcY[N] + (rnd(-12, 12) * 0x200), rnd(-341, 341), rnd(-0x600, 0), 0, -1);

				npcState[N] = 50;
				npcSurf[N] = 20; ensureSurf(npcSurf[N]);
				npcAniNo[N] = 10;
			}

			break;

		case 60: // Leap (used to attack Balrog in the Sand Zone storehouse)
			npcAniNo[N] = 6;
			npcState[N] = 61;
			npcVy[N] = -0x5FF;
			npcVx[N] = 0x400;
			npcC2[N] = 1;
			break;

		case 61: // Leap - part 2
			npcVy[N] += 0x40;

			if (((npcCol[N] & 8) != 0))
			{
				npcState[N] = 0;
				npcC2[N] = 0;
				npcVx[N] = 0;
			}

			break;
	}

	// Apply gravity and speed-caps during most states
	if (npcState[N] < 30 || npcState[N] >= 40)
	{
		npcVy[N] += 0x40;

		if (npcVx[N] > 0x400)
			npcVx[N] = 0x400;
		if (npcVx[N] < -0x400)
			npcVx[N] = -0x400;

		if (npcVy[N] > 0x5FF)
			npcVy[N] = 0x5FF;
	}

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 63 Toroko with stick
    private void act063(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcWait[N] = 0;
			npcAniWait[N] = 0;
			npcVy[N] = -0x400;
			// Fallthrough
		case 1:
			if (npcVy[N] > 0)
				npcBits[N] &= ~8;

			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 3)
				npcAniNo[N] = 0;

			if (npcDir[N] == 0)
				npcVx[N] = -0x100;
			else
				npcVx[N] = 0x100;

			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
				npcState[N] = 2;

			break;

		case 2:
			npcState[N] = 3;
			npcWait[N] = 0;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 3:
			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 3)
				npcAniNo[N] = 0;

			if (++npcWait[N] > 50)
			{
				npcWait[N] = 40;
				npcVx[N] *= -1;

				if (npcDir[N] == 0)
					npcDir[N] = 2;
				else
					npcDir[N] = 0;
			}

			if (npcWait[N] > 35)
				npcBits[N] |= 32;

			if (npcDir[N] == 0)
				npcVx[N] -= 0x40;
			else
				npcVx[N] += 0x40;

			if (npcShock[N] != 0)
			{
				npcState[N] = 4;
				npcAniNo[N] = 4;
				npcVy[N] = -0x400;
				npcBits[N] &= ~32;
				npcDmg[N] = 0;
			}

			break;

		case 4:
			if (npcDir[N] == 0)
				npcVx[N] = -0x100;
			else
				npcVx[N] = 0x100;

			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
			{
				npcState[N] = 5;
				npcBits[N] |= 8192;
			}

			break;

		case 5:
			npcVx[N] = 0;
			npcAniNo[N] = 5;
			break;
	}

	npcVy[N] += 0x40;

	if (npcVx[N] > 0x400)
		npcVx[N] = 0x400;
	if (npcVx[N] < -0x400)
		npcVx[N] = -0x400;

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 68 Balrog (running)
    private void act068(int N) {
	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcWait[N] = 30;

			if (npcX[N] > px)
				npcDir[N] = 0;
			else
				npcDir[N] = 2;
			// Fallthrough
		case 1:
			if (--npcWait[N] != 0)
				break;

			npcState[N] = 2;
			++npcC1[N];

			break;

		case 2:
			npcState[N] = 3;
			npcWait[N] = 0;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			// Fallthrough
		case 3:
			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;

				if (++npcAniNo[N] == 2 || npcAniNo[N] == 4)
					sfx(23);
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			if (npcDir[N] == 0)
				npcVx[N] -= 0x10;
			else
				npcVx[N] += 0x10;

			if (npcWait[N] >= 8 && npcX[N] - (12 * 0x200) < px && npcX[N] + (12 * 0x200) > px && npcY[N] - (12 * 0x200) < py && npcY[N] + (8 * 0x200) > py)
			{
				npcState[N] = 10;
				npcAniNo[N] = 5;
				playerHidden = true;
				damageMyChar(2);
				break;
			}

			++npcWait[N];

			if (((npcCol[N] & 5) != 0) || npcWait[N] > 75)
			{
				npcState[N] = 9;
				npcAniNo[N] = 0;
				break;
			}

			if ((npcC1[N] % 3) == 0 && npcWait[N] > 25)
			{
				npcState[N] = 4;
				npcAniNo[N] = 7;
				npcVy[N] = -0x400;
				break;
			}

			break;

		case 4:
			if (((npcCol[N] & 8) != 0))
			{
				npcState[N] = 9;
				npcAniNo[N] = 8;
				quake(30);
				sfx(26);
			}

			if (npcWait[N] >= 8 && npcX[N] - (12 * 0x200) < px && npcX[N] + (12 * 0x200) > px && npcY[N] - (12 * 0x200) < py && npcY[N] + (8 * 0x200) > py)
			{
				npcState[N] = 10;
				npcAniNo[N] = 5;
				playerHidden = true;
				damageMyChar(2);
			}

			break;

		case 9:
			npcVx[N] = (npcVx[N] * 4) / 5;

			if (npcVx[N] != 0)
				break;

			npcState[N] = 0;

			break;

		case 10:
			px = npcX[N];
			py = npcY[N];

			npcVx[N] = (npcVx[N] * 4) / 5;

			if (npcVx[N] != 0)
				break;

			npcState[N] = 11;
			npcWait[N] = 0;
			npcAniNo[N] = 5;
			npcAniWait[N] = 0;

			break;

		case 11:
			px = npcX[N];
			py = npcY[N];

			if (++npcAniWait[N] > 2)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 6)
				npcAniNo[N] = 5;

			if (++npcWait[N] > 100)
				npcState[N] = 20;

			break;

		case 20:
			sfx(25);
			playerHidden = false;

			if (npcDir[N] == 0)
			{
				px += 4 * 0x200;
				py -= 8 * 0x200;
				vx = 0x5FF;
				vy = -0x200;
				 dir = 2;
				npcDir[N] = 2;
			}
			else
			{
				px -= 4 * 0x200;
				py -= 8 * 0x200;
				vx = -0x5FF;
				vy = -0x200;
				 dir = 0;
				npcDir[N] = 0;
			}

			npcState[N] = 21;
			npcWait[N] = 0;
			npcAniNo[N] = 7;
			// Fallthrough
		case 21:
			if (++npcWait[N] < 50)
				break;

			npcState[N] = 0;
			break;
	}

	npcVy[N] += 0x20;

	if (npcVx[N] < -0x400)
		npcVx[N] = -0x400;
	if (npcVx[N] > 0x400)
		npcVx[N] = 0x400;

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];



	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 74 Jack
    private void act074(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 8:
			npcState[N] = 9;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 9:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;
	}

	npcVy[N] += 0x40;

	if (npcVx[N] > 0x400)
		npcVx[N] = 0x400;
	if (npcVx[N] < -0x400)
		npcVx[N] = -0x400;

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 79 Mahin
    private void act079(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			break;

		case 2:
			npcAniNo[N] = 0;

			if (rnd(0, 120) == 10)
			{
				npcState[N] = 3;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (npcX[N] - (32 * 0x200) < px && npcX[N] + (32 * 0x200) > px && npcY[N] - (32 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			break;

		case 3:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 2;
				npcAniNo[N] = 0;
			}

			break;
	}

	npcVy[N] += 0x40;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 82 Misery (standing)
    private void act082(int N) {
	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 2;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 3;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 2;
			}

			break;

		case 15:
			npcState[N] = 16;
			npcWait[N] = 0;
			npcAniNo[N] = 4;
			// Fallthrough
		case 16:
			if (++npcWait[N] == 30)
			{
				sfx(21);
				setNpc(66, npcX[N], npcY[N] - (16 * 0x200), 0, 0, 0, N);
			}

			if (npcWait[N] == 50)
				npcState[N] = 14;

			break;

		case 20:
			npcState[N] = 21;
			npcAniNo[N] = 0;
			npcVy[N] = 0;
			npcBits[N] |= 8;
			// Fallthrough
		case 21:
			npcVy[N] -= 0x20;

			if (npcY[N] < -8 * 0x200)
				npcAlive[N] = false;

			break;

		case 25:
			npcState[N] = 26;
			npcWait[N] = 0;
			npcAniNo[N] = 5;
			npcAniWait[N] = 0;
			// Fallthrough
		case 26:
			if (++npcAniNo[N] > 7)
				npcAniNo[N] = 5;

			if (++npcWait[N] == 30)
			{
				sfx(101);
				flash(0, 0, 0);
				npcState[N] = 27;
				npcAniNo[N] = 7;
			}

			break;

		case 27:
			if (++npcWait[N] == 50)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
			}

			break;

		case 30:
			npcState[N] = 31;
			npcAniNo[N] = 3;
			npcAniWait[N] = 0;
			// Fallthrough
		case 31:
			if (++npcAniWait[N] > 10)
			{
				npcState[N] = 32;
				npcAniNo[N] = 4;
				npcAniWait[N] = 0;
			}

			break;

		case 32:
			if (++npcAniWait[N] > 100)
			{
				npcState[N] = 1;
				npcAniNo[N] = 2;
			}

			break;

		case 40:
			npcState[N] = 41;
			npcWait[N] = 0;
			// Fallthrough
		case 41:
			npcAniNo[N] = 4;

			switch (++npcWait[N])
			{
				case 30:
				case 40:
				case 50:
					setNpc(11, npcX[N] + (8 * 0x200), npcY[N] - (8 * 0x200), 0x600, rnd(-0x200, 0), 0, -1);
					sfx(33);
					break;
			}

			if (npcWait[N] > 50)
				npcState[N] = 0;

			break;

		case 50:
			npcAniNo[N] = 8;
			break;
	}

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];



	if (npcState[N] == 11)
	{
		if (npcAniWait[N] != 0)
		{
			--npcAniWait[N];
			npcAniNo[N] = 1;
		}
		else
		{
			if (rnd(0, 100) == 1)
				npcAniWait[N] = 30;

			npcAniNo[N] = 0;
		}
	}

	if (npcState[N] == 14)
	{
		if (npcAniWait[N] != 0)
		{
			--npcAniWait[N];
			npcAniNo[N] = 3;
		}
		else
		{
			if (rnd(0, 100) == 1)
				npcAniWait[N] = 30;

			npcAniNo[N] = 2;
		}
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 83 Igor (cutscene)
    private void act083(int N) {


	switch (npcState[N])
	{
		case 0:
			npcVx[N] = 0;
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (++npcAniWait[N] > 5)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			break;

		case 2:
			npcState[N] = 3;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 3:
			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;

		case 4:
			npcVx[N] = 0;
			npcState[N] = 5;
			npcWait[N] = 0;
			npcAniNo[N] = 6;
			// Fallthrough
		case 5:
			if (++npcWait[N] > 10)
			{
				npcWait[N] = 0;
				npcState[N] = 6;
				npcAniNo[N] = 7;
				sfx(70);
			}

			break;

		case 6:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
			}

			break;

		case 7:
			npcState[N] = 1;
			break;
	}

	npcVy[N] += 0x40;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 88 Igor (boss)
    private void act088(int N) {
	int i;
	int deg;
	int xm, ym;



	switch (npcState[N])
	{
		case 0:
			npcVx[N] = 0;
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (++npcAniWait[N] > 5)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			if (++npcWait[N] > 50)
				npcState[N] = 2;

			break;

		case 2:
			npcState[N] = 3;
			npcWait[N] = 0;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;

			if (++npcC1[N] < 3 || npcHp[N] > 150)
			{
				npcC2[N] = 0;

				if (px < npcX[N])
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}
			else
			{
				npcC2[N] = 1;

				if (px < npcX[N])
					npcDir[N] = 2;
				else
					npcDir[N] = 0;
			}
			// Fallthrough
		case 3:
			++npcWait[N];

			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			if (npcC2[N] != 0)
			{
				if (npcWait[N] > 16)
				{
					npcState[N] = 9;
					npcVx[N] = 0;
					npcAniNo[N] = 10;
					break;
				}
			}
			else if (npcWait[N] > 50)
			{
				npcAniNo[N] = 8;
				npcVy[N] = -0x400;
				npcState[N] = 7;
				npcWait[N] = 0;
				npcVx[N] = (npcVx[N] * 3) / 2;
				npcDmg[N] = 2;
				break;
			}
			else
			{
				if (npcDir[N] == 0)
				{
					if (npcX[N] - (24 * 0x200) < px)
						npcState[N] = 4;
				}
				else
				{
					if (npcX[N] + (24 * 0x200) > px)
						npcState[N] = 4;
				}
			}

			break;

		case 4:
			npcVx[N] = 0;
			npcState[N] = 5;
			npcWait[N] = 0;
			npcAniNo[N] = 6;
			// Fallthrough
		case 5:
			if (++npcWait[N] > 12)
			{
				npcWait[N] = 0;
				npcState[N] = 6;
				npcAniNo[N] = 7;
				sfx(70);
				npcDmg[N] = 5;
				npcHitI[N * 4 + 0] = (24 * 0x200) >> 9;
				npcHitI[N * 4 + 1] = (1) >> 9;
			}

			break;

		case 6:
			if (++npcWait[N] > 10)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
				npcDmg[N] = 0;
				npcHitI[N * 4 + 0] = (8 * 0x200) >> 9;
				npcHitI[N * 4 + 1] = (16 * 0x200) >> 9;
			}

			break;

		case 7:
			if (((npcCol[N] & 8) != 0))
			{
				npcState[N] = 8;
				npcAniNo[N] = 9;
				sfx(26);
				quake(30);
				npcDmg[N] = 0;

				for (i = 0; i < 4; ++i)
					setNpc(4, npcX[N] + (rnd(-12, 12) * 0x200), npcY[N] + (rnd(-12, 12) * 0x200), rnd(-341, 341), rnd(-0x600, 0), 0, -1);
			}

			break;

		case 8:
			npcVx[N] = 0;

			if (++npcWait[N] > 10)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
				npcDmg[N] = 0;
			}

			break;

		case 9:
			npcState[N] = 10;
			npcWait[N] = 0;

			if (px < npcX[N])
				npcDir[N] = 0;
			else
				npcDir[N] = 2;

			// Fallthrough
		case 10:
			if (++npcWait[N] > 100 && npcWait[N] % 6 == 1)
			{
				if (npcDir[N] == 0)
					deg = 0x88;
				else
					deg = 0xF8;

				deg += (char)rnd(-0x10, 0x10);
				ym = gsin(deg) * 3;
				xm = gcos(deg) * 3;
				setNpc(11, npcX[N], npcY[N] + (4 * 0x200), xm, ym, 0, -1);

				sfx(12);
			}

			if (npcWait[N] > 50 && (npcWait[N] / 2 % 2 != 0))
				npcAniNo[N] = 11;
			else
				npcAniNo[N] = 10;

			if (npcWait[N] > 132)
			{
				npcState[N] = 0;
				npcAniNo[N] = 0;
				npcC1[N] = 0;
			}

			break;
	}

	npcVy[N] += 0x40;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 93 Chaco
    private void act093(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (npcX[N] - (32 * 0x200) < px && npcX[N] + (32 * 0x200) > px && npcY[N] - (32 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			break;

		case 10:
			npcAniNo[N] = 6;

			if (++npcWait[N] > 200)
			{
				npcWait[N] = 0;
				caret(npcX[N], npcY[N], 5, 0);
			}

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 107 Malco
    private void act107(int N) {
	int i;

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;

			if (npcDir[N] == 2)
				npcAniNo[N] = 5;

			break;

		case 10:
			npcState[N] = 11;
			npcWait[N] = 0;
			npcAniWait[N] = 0;

			for (i = 0; i < 4; ++i)
				setNpc(4, npcX[N], npcY[N], rnd(-341, 341), rnd(-0x600, 0), 0, -1);

			// Fallthrough
		case 11:
			if (++npcAniWait[N] > 1)
			{
				sfx(43);
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			if (++npcWait[N] > 100)
				npcState[N] = 12;

			break;

		case 12:
			npcState[N] = 13;
			npcWait[N] = 0;
			npcAniNo[N] = 1;
			// Fallthrough
		case 13:
			if (++npcWait[N] > 50)
				npcState[N] = 14;

			break;

		case 14:
			npcState[N] = 15;
			npcWait[N] = 0;
			// Fallthrough
		case 15:
			if (npcWait[N] / 2 % 2 != 0)
			{
				npcX[N] += 1 * 0x200;
				sfx(11);
			}
			else
			{
				npcX[N] -= 1 * 0x200;
			}

			if (++npcWait[N] > 50)
				npcState[N] = 16;

			break;

		case 16:
			npcState[N] = 17;
			npcWait[N] = 0;
			npcAniNo[N] = 2;
			sfx(12);

			for (i = 0; i < 8; ++i)
				setNpc(4, npcX[N], npcY[N], rnd(-341, 341), rnd(-0x600, 0), 0, -1);

			// Fallthrough
		case 17:
			if (++npcWait[N] > 150)
				npcState[N] = 18;

			break;

		case 18:
			npcState[N] = 19;
			npcWait[N] = 0;
			npcAniNo[N] = 3;
			npcAniWait[N] = 0;
			// Fallthrough
		case 19:
			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
			{
				sfx(11);
				npcAniNo[N] = 3;
			}

			if (++npcWait[N] > 100)
			{
				npcState[N] = 20;
				sfx(12);

				for (i = 0; i < 4; ++i)
					setNpc(4, npcX[N], npcY[N], rnd(-341, 341), rnd(-0x600, 0), 0, -1);
			}

			break;

		case 20:
			npcAniNo[N] = 4;
			break;

		case 21:
			npcState[N] = 22;
			npcAniNo[N] = 5;
			sfx(51);
			break;

		case 100:
			npcState[N] = 101;
			npcAniNo[N] = 6;
			npcAniWait[N] = 0;
			// Fallthrough
		case 101:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 9)
				npcAniNo[N] = 6;

			break;

		case 110:
			destroyFx(npcX[N], npcY[N], 0x2000, 16);
			npcAlive[N] = false;
			break;
	}


	;
    }

    // 113 Professor Booster
    private void act113(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcState[N] = 4;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			break;

		case 5:
			npcAniNo[N] = 6;
			break;

		case 30:
			npcState[N] = 31;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcHitI[N * 4 + 3] = (16 * 0x200) >> 9;
			npcX[N] -= 16 * 0x200;
			npcY[N] += 8 * 0x200;
			sfx(29);
			// Fallthrough
		case 31:
			if (++npcWait[N] == 64)
			{
				npcState[N] = 32;
				npcWait[N] = 0;
			}

			break;

		case 32:
			if (++npcWait[N] > 20)
			{
				npcState[N] = 33;
				npcAniNo[N] = 1;
				npcHitI[N * 4 + 3] = (8 * 0x200) >> 9;
			}

			break;

		case 33:
			if (((npcCol[N] & 8) != 0))
			{
				npcState[N] = 34;
				npcWait[N] = 0;
				npcAniNo[N] = 0;
			}

			break;
	}

	npcVy[N] += 0x40;
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;

	if (npcState[N] == 31)
	{
		;

		if (npcWait[N] / 2 % 2 != 0)
			;
	}
    }

    // 117 Curly
    private void act117(int N) {


	switch (npcState[N])
	{
		case 0:
			if (npcDir[N] == 4)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			npcVx[N] = 0;
			npcVy[N] += 0x40;
			break;

		case 3:
			if (false)
			{
				// There used to be an if-statement here that didn't do anything, but the compiler optimised it out.
				// We only know this was here because empty if-statements affect the assembly's register usage.
				// Since there's no code, we have no idea what the original condition actually was.
			}

			if (false)
			{
				// Another empty if-statement
			}

			npcState[N] = 4;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			// Fallthrough
		case 4:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			npcVy[N] += 0x40;

			if (npcDir[N] == 0)
				npcVx[N] = -0x200;
			else
				npcVx[N] = 0x200;

			break;

		case 5:
			npcState[N] = 6;
			npcAniNo[N] = 5;
			destroyFx(npcX[N], npcY[N], (npcViewI[N * 4 + 2] << 9), 8);
			break;

		case 6:
			if (false)
			{
				// There used to be an if-statement here that didn't do anything, but the compiler optimised it out.
				// We only know this was here because empty if-statements affect the assembly's register usage.
				// Since there's no code, we have no idea what the original condition actually was.
			}

			npcAniNo[N] = 5;
			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;

			if (npcX[N] > px)
				npcDir[N] = 0;
			else
				npcDir[N] = 2;

			// Fallthrough
		case 11:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			if (px < npcX[N] + (20 * 0x200) && px > npcX[N] - (20 * 0x200))
				npcState[N] = 0;

			break;

		case 20:
			npcVx[N] = 0;
			npcAniNo[N] = 6;
			break;

		case 21:
			npcVx[N] = 0;
			npcAniNo[N] = 9;
			break;

		case 30:
			npcState[N] = 31;
			npcWait[N] = 0;
			npcVy[N] = -0x400;
			// Fallthrough
		case 31:
			npcAniNo[N] = 7;

			if (npcDir[N] == 0)
				npcVx[N] = 0x200;
			else
				npcVx[N] = -0x200;

			npcVy[N] += 0x40;

			if (npcWait[N]++ != 0 && ((npcCol[N] & 8) != 0))
				npcState[N] = 32;

			break;

		case 32:
			npcVy[N] += 0x40;
			npcAniNo[N] = 8;
			npcVx[N] = 0;
			break;

		case 70:
			npcState[N] = 71;
			npcWait[N] = 0;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			// Fallthrough
		case 71:
			if (npcDir[N] == 0)
				npcX[N] += 0x100;
			else
				npcX[N] -= 0x100;

			if (++npcAniWait[N] > 8)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 1;

			break;
	}

	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 124 Sunstone
    private void act124(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcX[N] += 8 * 0x200;
			npcY[N] += 8 * 0x200;
			// Fallthrough
		case 1:
			npcBits[N] &= ~8;
			npcAniNo[N] = 0;
			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 1;
			npcWait[N] = 0;
			npcBits[N] |= 8;
			// Fallthrough
		case 11:
			switch (npcDir[N])
			{
				case 0:
					npcX[N] -= 0x80;
					break;

				case 1:
					npcY[N] -= 0x80;
					break;

				case 2:
					npcX[N] += 0x80;
					break;

				case 3:
					npcY[N] += 0x80;
					break;
			}
			if (++npcWait[N] % 8 == 0)
				sfx(26);

			quake(20);
			break;
	}

	;
    }

    // 130 Puppy (sitting, wagging tail)
    private void act130(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcBits[N] |= 8192;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			if (npcX[N] - (64 * 0x200) < px && npcX[N] + (64 * 0x200) > px && npcY[N] - (32 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (++npcAniWait[N] > 3)
				{
					npcAniWait[N] = 0;
					++npcAniNo[N];
				}

				if (npcAniNo[N] > 3)
					npcAniNo[N] = 2;
			}

			if (npcX[N] - (96 * 0x200) < px && npcX[N] + (96 * 0x200) > px && npcY[N] - (32 * 0x200) < py && npcY[N] + (16 * 0x200) > py)
			{
				if (npcX[N] > px)
					npcDir[N] = 0;
				else
					npcDir[N] = 2;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	npcVy[N] += 0x40;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 133 Jenka
    private void act133(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 139 Doctor
    private void act139(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcVx[N] = 0;
			npcVy[N] = 0;
			npcY[N] -= 8 * 0x200;
			// Fallthrough
		case 1:
			if (((npcCol[N] & 8) != 0))
				npcAniNo[N] = 0;
			else
				npcAniNo[N] = 2;

			npcVy[N] += 0x40;
			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 1;
			npcAniWait[N] = 0;
			npcC1[N] = 0;
			// Fallthrough
		case 11:
			if (++npcAniWait[N] > 6)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
			{
				npcAniNo[N] = 0;
				++npcC1[N];
			}

			if (npcC1[N] > 8)
			{
				npcAniNo[N] = 0;
				npcState[N] = 1;
			}

			break;

		case 20:
			npcState[N] = 21;
			npcWait[N] = 0;
			npcAniNo[N] = 2;
			npcTy[N] = npcY[N] - (32 * 0x200);
			// Fallthrough
		case 21:
			if (npcY[N] < npcTy[N])
				npcVy[N] += 0x20;
			else
				npcVy[N] -= 0x20;

			if (npcVy[N] > 0x200)
				npcVy[N] = 0x200;
			if (npcVy[N] < -0x200)
				npcVy[N] = -0x200;

			break;

		case 30:
			npcState[N] = 31;
			npcVx[N] = 0;
			npcVy[N] = 0;
			npcWait[N] = 24 * 2;
			sfx(29);
			// Fallthrough
		case 31:
			--npcWait[N];
			npcAniNo[N] = 0;

			if (npcWait[N] == 0)
				npcAlive[N] = false;

			break;

		case 40:
			npcState[N] = 41;
			npcWait[N] = 0;
			npcVx[N] = 0;
			npcVy[N] = 0;
			sfx(29);
			// Fallthrough
		case 41:
			npcAniNo[N] = 2;

			if (++npcWait[N] < 64)
				break;

			npcState[N] = 20;
			break;
	}

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	if (npcDir[N] == 0)
		;
	else
		;

	if (npcState[N] == 31 || npcState[N] == 41)
	{
		;

		if (npcWait[N] / 2 % 2 != 0)
			;
	}
    }

    // 150 Quote
    private void act150(int N) {
	int i;



	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;

			if (npcDir[N] > 10)
			{
				npcX[N] = px;
				npcY[N] = py;
				npcDir[N] -= 10;
			}
			break;

		case 2:
			npcAniNo[N] = 1;
			break;

		case 10:
			npcState[N] = 11;

			for (i = 0; i < 4; ++i)
				setNpc(4, npcX[N], npcY[N], rnd(-0x155, 0x155), rnd(-0x600, 0), 0, -1);

			sfx(71);
			// Fallthrough
		case 11:
			npcAniNo[N] = 2;
			break;

		case 20:
			npcState[N] = 21;
			npcWait[N] = 64;
			sfx(29);
			// Fallthrough
		case 21:
			if (--npcWait[N] == 0)
				npcAlive[N] = false;

			break;

		case 50:
			npcState[N] = 51;
			npcAniNo[N] = 3;
			npcAniWait[N] = 0;
			// Fallthrough
		case 51:
			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 6)
				npcAniNo[N] = 3;

			if (npcDir[N] == 0)
				npcX[N] -= 1 * 0x200;
			else
				npcX[N] += 1 * 0x200;

			break;

		case 60:
			npcState[N] = 61;
			npcAniNo[N] = 7;
			npcTx[N] = npcX[N];
			npcTy[N] = npcY[N];
			// Fallthrough
		case 61:
			npcTy[N] += 0x100;
			npcX[N] = npcTx[N] + (rnd(-1, 1) * 0x200);
			npcY[N] = npcTy[N] + (rnd(-1, 1) * 0x200);
			break;

		case 70:
			npcState[N] = 71;
			npcWait[N] = 0;
			npcAniNo[N] = 3;
			npcAniWait[N] = 0;
			// Fallthrough
		case 71:
			if (npcDir[N] == 0)
				npcX[N] += 0x100;
			else
				npcX[N] -= 0x100;

			if (++npcAniWait[N] > 8)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 6)
				npcAniNo[N] = 3;

			break;

		case 80:
			npcAniNo[N] = 8;
			break;

		case 99:
		case 100:
			npcState[N] = 101;
			npcAniNo[N] = 3;
			npcAniWait[N] = 0;
			// Fallthrough
		case 101:
			npcVy[N] += 0x40;

			if (npcVy[N] > 0x5FF)
				npcVy[N] = 0x5FF;

			if (((npcCol[N] & 8) != 0))
			{
				npcVy[N] = 0;
				npcState[N] = 102;
			}

			npcY[N] += npcVy[N];
			break;

		case 102:
			if (++npcAniWait[N] > 8)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 6)
				npcAniNo[N] = 3;

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;

	if (npcState[N] == 21)
	{
		;

		if (npcWait[N] / 2 % 2 != 0)
			;
	}

	// Use a different sprite if the player is wearing the Mimiga Mask
	if (false)
	{
		;
		;
	}
    }

    // 151 Blue robot (standing)
    private void act151(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough

		case 1:
			if (rnd(0, 100) == 0)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 16)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 165 Curly (collapsed)
    private void act165(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcY[N] += 10 * 0x200;
			// Fallthrough
		case 1:
			if (npcDir[N] == 2 && px > npcX[N] - (32 * 0x200) && px < npcX[N] + (32 * 0x200) && py > npcY[N] - (16 * 0x200) && py < npcY[N] + (16 * 0x200))
				npcAniNo[N] = 1;
			else
				npcAniNo[N] = 0;

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 167 Professor Booster (falling)
    private void act167(int N) {
	int i;


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 1;
			break;

		case 10:
			npcAniNo[N] = 0;

			npcVy[N] += 0x40;
			if (npcVy[N] > 0x5FF)
				npcVy[N] = 0x5FF;

			npcY[N] += npcVy[N];
			break;

		case 20:
			npcState[N] = 21;
			npcWait[N] = 0;
			npcAniNo[N] = 0;
			sfx(29);
			// Fallthrough
		case 21:
			if (++npcAniNo[N] > 2)
				npcAniNo[N] = 1;

			if (++npcWait[N] > 100)
			{
				for (i = 0; i < 4; ++i)
					setNpc(4, npcX[N] + (rnd(-12, 12) * 0x200), npcY[N] + (rnd(-12, 12) * 0x200), rnd(-341, 341), rnd(-0x600, 0), 0, -1);

				npcAlive[N] = false;
			}

			break;
	}

	;
    }

    // 168 Boulder
    private void act168(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			break;

		case 10:
			npcState[N] = 11;
			npcWait[N] = 0;
			npcTx[N] = npcX[N];
			// Fallthrough
		case 11:
			++npcWait[N];
			npcX[N] = npcTx[N];

			if (npcWait[N] / 3 % 2 != 0)
				npcX[N] += 1 * 0x200;

			break;

		case 20:
			npcState[N] = 21;
			npcWait[N] = 0;
			npcVy[N] = -0x400;
			npcVx[N] = 0x100;
			sfx(25);
			// Fallthrough
		case 21:
			npcVy[N] += 0x10;

			npcX[N] += npcVx[N];
			npcY[N] += npcVy[N];

			if ((npcWait[N] != 0) && ((npcCol[N] & 8) != 0))
			{
				sfx(35);
				quake(40);
				npcState[N] = 0;
			}

			if (npcWait[N] == 0)
				++npcWait[N];

			break;
	}

	;
    }

    // 190 Broken robot
    private void act190(int N) {

	int i;

	switch (npcState[N])
	{
		case 0:
			npcAniNo[N] = 0;
			break;

		case 10:
			sfx(72);

			for (i = 0; i < 8; ++i)
				setNpc(4, npcX[N], npcY[N] + (rnd(-8, 8) * 0x200), rnd(-8, -2) * 0x200, rnd(-3, 3) * 0x200, 0, -1);

			npcAlive[N] = false;
			break;

		case 20:
			if (++npcAniWait[N] > 10)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			break;
	}

	;
    }

    // 217 Itoh
    private void act217(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 10:
			npcAniNo[N] = 2;
			npcVx[N] = 0;
			break;

		case 20:
			npcState[N] = 21;
			npcAniNo[N] = 2;
			npcVx[N] += 0x200;
			npcVy[N] -= 0x400;
			break;

		case 21:
			if (((npcCol[N] & 8) != 0))
			{
				npcAniNo[N] = 3;
				npcState[N] = 30;
				npcWait[N] = 0;
				npcVx[N] = 0;
				npcTx[N] = npcX[N];
			}

			break;

		case 30:
			npcAniNo[N] = 3;

			if (++npcWait[N] / 2 % 2 != 0)
				npcX[N] = npcTx[N] + (1 * 0x200);
			else
				npcX[N] = npcTx[N];

			break;

		case 40:
			npcState[N] = 41;
			npcVy[N] = -0x200;
			npcAniNo[N] = 2;
			// Fallthrough
		case 41:
			if (((npcCol[N] & 8) != 0))
			{
				npcState[N] = 42;
				npcAniNo[N] = 4;
			}

			break;

		case 42:
			npcVx[N] = 0;
			npcAniNo[N] = 4;
			break;

		case 50:
			npcState[N] = 51;
			npcWait[N] = 0;
			// Fallthrough
		case 51:
			if (++npcWait[N] > 32)
				npcState[N] = 42;

			npcVx[N] = 0x200;

			if (++npcAniWait[N] > 3)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 7)
				npcAniNo[N] = 4;

			break;
	}

	npcVy[N] += 0x40;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	;
    }

    // 223 Momorin
    private void act223(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 160) == 1)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 12)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 3:
			npcAniNo[N] = 2;
			break;
	}

	if (npcState[N] < 2 && py < npcY[N] + (16 * 0x200) && py > npcY[N] - (16 * 0x200))
	{
		if (px < npcX[N])
			npcDir[N] = 0;
		else
			npcDir[N] = 2;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 226 Kanpachi
    private void act226(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcVx[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 60) == 1)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 2;
			npcAniWait[N] = 0;
			// Fallthrough
		case 11:
			npcVx[N] = 0x200;

			if (++npcAniWait[N] > 4)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 5)
				npcAniNo[N] = 2;

			++npcWait[N];

			break;

		case 20:
			npcVx[N] = 0;
			npcAniNo[N] = 6;
			break;
	}

	npcVy[N] += 0x20;
	if (npcVy[N] > 0x5FF)
		npcVy[N] = 0x5FF;

	npcX[N] += npcVx[N];
	npcY[N] += npcVy[N];

	;
    }

    // 254 Helicopter
    private void act254(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			setNpc(255, npcX[N] + (18 * 0x200), npcY[N] - (57 * 0x200), 0, 0, 0, N);
			setNpc(255, npcX[N] - (32 * 0x200), npcY[N] - (52 * 0x200), 0, 0, 2, N);
			break;

		case 20:
			npcWait[N] = 0;
			npcC1[N] = 60;
			npcState[N] = 21;
			break;

		case 30:
			npcState[N] = 21;
			setNpc(223, npcX[N] - (11 * 0x200), npcY[N] - (14 * 0x200), 0, 0, 0, -1);
			break;

		case 40:
			npcState[N] = 21;
			setNpc(223, npcX[N] - (9 * 0x200), npcY[N] - (14 * 0x200), 0, 0, 0, -1);
			setNpc(40, npcX[N] - (22 * 0x200), npcY[N] - (14 * 0x200), 0, 0, 0, -1);
			setNpc(93, npcX[N] - (35 * 0x200), npcY[N] - (14 * 0x200), 0, 0, 0, -1);
			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 259 Curly (carried and unconcious)
    private void act259(int N) {

	switch (npcState[N])
	{
		case 0:
			npcBits[N] &= ~8192;
			npcState[N] = 1;
			// Fallthrough
		case 1:
			if ( dir == 0)
				npcDir[N] = 0;
			else
				npcDir[N] = 2;

			npcY[N] = py - (4 * 0x200);

			if (npcDir[N] == 0)
			{
				npcX[N] = px + (3 * 0x200);
				;
			}
			else
			{
				npcX[N] = px - (3 * 0x200);
				;
			}

			if (false)
				;

			break;

		case 10:
			npcState[N] = 11;
			npcVx[N] = 0x40;
			npcVy[N] = -0x20;

			;
			break;

		case 11:
			if (npcY[N] < (64 * 0x200))
				npcVy[N] = 0x20;

			npcX[N] += npcVx[N];
			npcY[N] += npcVy[N];
			break;

		case 20:
			vanishFx(N);
			destroyFxUp(npcX[N], npcY[N], 0x2000, 0x40);
			break;
	}
    }

    // 260 Shovel Brigade (caged)
    private void act260(int N) {


	switch (npcState[N])
	{
		case 0:
			npcX[N] += 0x200;
			npcY[N] -= 0x400;
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			// Fallthrough
		case 1:
			if (rnd(0, 160) == 1)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 12)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;

		case 10:
			npcState[N] = 11;
			npcAniNo[N] = 2;
			setNpc(87, npcX[N], npcY[N] - (16 * 0x200), 0, 0, 0, -1);

			break;
	}

	if (px < npcX[N])
		npcDir[N] = 0;
	else
		npcDir[N] = 2;

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 304 Gaudi in hospital
    private void act304(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcY[N] += 10 * 0x200;
			// Fallthrough
		case 1:
			npcAniNo[N] = 0;
			break;

		case 10:
			npcAniNo[N] = 1;
			break;

		case 20:
			npcState[N] = 21;
			npcAniNo[N] = 2;
			// Fallthrough
		case 21:
			if (++npcAniWait[N] > 10)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 3)
				npcAniNo[N] = 2;

			break;
	}

	;
    }

    // 306 Balrog (nurse)
    private void act306(int N) {


	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = 0;
			npcAniWait[N] = 0;
			npcY[N] += 4 * 0x200;
			// Fallthrough
		case 1:
			if (rnd(0, 120) == 10)
			{
				npcState[N] = 2;
				npcWait[N] = 0;
				npcAniNo[N] = 1;
			}

			break;

		case 2:
			if (++npcWait[N] > 8)
			{
				npcState[N] = 1;
				npcAniNo[N] = 0;
			}

			break;
	}

	if (npcDir[N] == 0)
		;
	else
		;
    }

    // 351 Statue (shootable)
    private void act351(int N) {

	switch (npcState[N])
	{
		case 0:
			npcState[N] = 1;
			npcAniNo[N] = npcDir[N] / 10;
			npcX[N] += 8 * 0x200;
			npcY[N] += 12 * 0x200;
			break;

		case 10:
			if (getFlag(npcFlag[N]))
			{
				npcState[N] = 20;
			}
			else
			{
				npcState[N] = 11;
				npcBits[N] |= 32;
			}
			// Fallthrough
		case 11:
			if (npcHp[N] <= 900)
			{
				setNpc(351, npcX[N] - (8 * 0x200), npcY[N] - (12 * 0x200), 0, 0, (npcAniNo[N] + 4) * 10, -1);
				;
			}

			break;

		case 20:
			npcAniNo[N] += 4;
			npcState[N] = 1;
			break;
	}

	;
    }

    // 357 Puppy ghost
    private void act357(int N) {

	switch (npcState[N])
	{
		case 0:
			;
			++npcWait[N];
			break;

		case 10:
			npcWait[N] = 0;
			npcState[N] = 11;
			sfx(29);
			// Fallthrough
		case 11:
			++npcWait[N];
			;

			if (npcWait[N] / 2 % 2 != 0)
				;

			if (npcWait[N] > 50)
				npcAlive[N] = false;

			break;
	}

	if (npcWait[N] % 8 == 1)
		caret(npcX[N] + (rnd(-8, 8) * 0x200), npcY[N] + 0x1000, 13, 1);
    }

    // 358 Misery (stood in the wind during the credits)
    private void act358(int N) {

	switch (npcState[N])
	{
		case 0:
			if (++npcAniWait[N] > 6)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 1)
				npcAniNo[N] = 0;

			break;

		case 10:
			if (++npcAniWait[N] > 6)
			{
				npcAniWait[N] = 0;
				++npcAniNo[N];
			}

			if (npcAniNo[N] > 4)
				npcAniNo[N] = 3;

			break;
	}

	;
    }

    private boolean isPorted(int t) {
        switch (t) {
            case 4:
            case 62:
            case 92:
            case 12:
            case 18:
            case 22:
            case 23:
            case 38:
            case 40:
            case 41:
            case 42:
            case 55:
            case 60:
            case 61:
            case 63:
            case 68:
            case 74:
            case 79:
            case 82:
            case 83:
            case 88:
            case 93:
            case 107:
            case 113:
            case 117:
            case 124:
            case 130:
            case 133:
            case 139:
            case 150:
            case 151:
            case 165:
            case 167:
            case 168:
            case 190:
            case 217:
            case 223:
            case 226:
            case 254:
            case 259:
            case 260:
            case 304:
            case 306:
            case 351:
            case 357:
            case 358:
                return true;
            default: return false;
        }
    }

    private boolean actNpcPort(int i, int t) {
        switch (t) {
            case 4: act004(i); return true;
            case 62: act062(i); return true;
            case 92: act092(i); return true;
            case 12: act012(i); return true;
            case 18: act018(i); return true;
            case 22: act022(i); return true;
            case 23: act023(i); return true;
            case 38: act038(i); return true;
            case 40: act040(i); return true;
            case 41: act041(i); return true;
            case 42: act042(i); return true;
            case 55: act055(i); return true;
            case 60: act060(i); return true;
            case 61: act061(i); return true;
            case 63: act063(i); return true;
            case 68: act068(i); return true;
            case 74: act074(i); return true;
            case 79: act079(i); return true;
            case 82: act082(i); return true;
            case 83: act083(i); return true;
            case 88: act088(i); return true;
            case 93: act093(i); return true;
            case 107: act107(i); return true;
            case 113: act113(i); return true;
            case 117: act117(i); return true;
            case 124: act124(i); return true;
            case 130: act130(i); return true;
            case 133: act133(i); return true;
            case 139: act139(i); return true;
            case 150: act150(i); return true;
            case 151: act151(i); return true;
            case 165: act165(i); return true;
            case 167: act167(i); return true;
            case 168: act168(i); return true;
            case 190: act190(i); return true;
            case 217: act217(i); return true;
            case 223: act223(i); return true;
            case 226: act226(i); return true;
            case 254: act254(i); return true;
            case 259: act259(i); return true;
            case 260: act260(i); return true;
            case 304: act304(i); return true;
            case 306: act306(i); return true;
            case 351: act351(i); return true;
            case 357: act357(i); return true;
            case 358: act358(i); return true;
            default: return false;
        }
    }

}
