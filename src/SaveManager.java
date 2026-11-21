import javax.microedition.rms.RecordStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

public class SaveManager {
    private static final String STORE_NAME = "CS6300_SAVE";

    public static boolean hasSave() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE_NAME, false);
            return rs.getNumRecords() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public static boolean saveGame(int stageId, int px, int py, int dir, int hp, int maxHp,
                                   int armCount, int selectedArm, int[] armsCode, int[] armsLevel,
                                   int[] armsExp, int[] armsAmmo, int[] armsMaxAmmo,
                                   int itemCount, int[] items,
                                   int permitCount, int[] permitIndex, int[] permitEvent,
                                   boolean[] flags) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(0x43533634); // "CS64"
            dos.writeInt(stageId);
            dos.writeInt(px);
            dos.writeInt(py);
            dos.writeInt(dir);
            dos.writeInt(hp);
            dos.writeInt(maxHp);
            dos.writeInt(armCount);
            dos.writeInt(selectedArm);
            for (int i = 0; i < 8; i++) {
                dos.writeInt(i < armCount ? armsCode[i] : 0);
                dos.writeInt(i < armCount ? armsLevel[i] : 1);
                dos.writeInt(i < armCount ? armsExp[i] : 0);
                dos.writeInt(i < armCount ? armsAmmo[i] : 0);
                dos.writeInt(i < armCount ? armsMaxAmmo[i] : 0);
            }
            dos.writeInt(itemCount);
            for (int i = 0; i < 32; i++) {
                dos.writeInt(i < itemCount ? items[i] : 0);
            }
            dos.writeInt(permitCount);
            for (int i = 0; i < 8; i++) {
                dos.writeInt(i < permitCount ? permitIndex[i] : 0);
                dos.writeInt(i < permitCount ? permitEvent[i] : 0);
            }
            byte[] packedFlags = new byte[1000];
            for (int i = 0; i < 8000; i++) {
                if (flags[i]) packedFlags[i >> 3] |= (1 << (i & 7));
            }
            dos.write(packedFlags);
            dos.flush();

            byte[] raw = bos.toByteArray();
            rs = RecordStore.openRecordStore(STORE_NAME, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(raw, 0, raw.length);
            } else {
                rs.setRecord(1, raw, 0, raw.length);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public static int[] loadGame(int[] armsCode, int[] armsLevel, int[] armsExp,
                                 int[] armsAmmo, int[] armsMaxAmmo, int[] items,
                                 int[] permitIndex, int[] permitEvent, boolean[] flags) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE_NAME, false);
            if (rs.getNumRecords() == 0) return null;
            byte[] raw = rs.getRecord(1);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw));
            int magic = dis.readInt();
            if (magic != 0x43533633 && magic != 0x43533634) return null;

            int[] header = new int[8];
            for (int i = 0; i < 8; i++) header[i] = dis.readInt();

            for (int i = 0; i < 8; i++) {
                armsCode[i] = dis.readInt();
                armsLevel[i] = dis.readInt();
                armsExp[i] = dis.readInt();
                armsAmmo[i] = dis.readInt();
                armsMaxAmmo[i] = dis.readInt();
            }
            int itemCount = dis.readInt();
            for (int i = 0; i < 32; i++) {
                items[i] = dis.readInt();
            }
            int permitCount = 0;
            if (magic == 0x43533634) {
                permitCount = dis.readInt();
                for (int i = 0; i < 8; i++) {
                    permitIndex[i] = dis.readInt();
                    permitEvent[i] = dis.readInt();
                }
            }
            byte[] packedFlags = new byte[1000];
            dis.readFully(packedFlags);
            for (int i = 0; i < 8000; i++) {
                flags[i] = ((packedFlags[i >> 3] >> (i & 7)) & 1) != 0;
            }
            // Returns {stageId, px, py, dir, hp, maxHp, armCount, selectedArm, itemCount, permitCount}
            int[] out = new int[10];
            for (int i = 0; i < 8; i++) out[i] = header[i];
            out[8] = itemCount;
            out[9] = permitCount;
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }
}