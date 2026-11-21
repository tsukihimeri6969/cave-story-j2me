import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

public class TextBoxRenderer {
    private final Image sharedBuffer;
    private final Graphics sharedG;
    private final Font sysFont;

    private Image imgFace;
    private Image imgArms;
    private Image imgItem;
    private Image imgStageImage;

    private boolean dirty = true;
    private boolean visible = false;
    private boolean waitingNod = false;
    private boolean waitingYn = false;
    private int ynChoice = 0;
    private int faceId = 0;

    private String[] lines = {"", "", ""};
    private int curLine = 0;

    public TextBoxRenderer() {
        sharedBuffer = Image.createImage(244, 152);
        sharedG = sharedBuffer.getGraphics();
        sysFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        try { imgArms = Image.createImage("/ArmsImage.png"); } catch (Exception e) {}
        if (imgArms == null) {
            try { imgArms = Image.createImage("/Arms.png"); } catch (Exception e) {}
        }
        try { imgItem = Image.createImage("/ItemImage.png"); } catch (Exception e) {}
        try { imgStageImage = Image.createImage("/StageImage.png"); } catch (Exception e) {}
    }

    public Image getSharedBuffer() {
        return sharedBuffer;
    }

    public Graphics getSharedGraphics() {
        return sharedG;
    }

    public void setVisible(boolean v) {
        this.visible = v;
        if (!v) {
            setFace(0);
        }
        this.dirty = true;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void setFace(int id) {
        if (this.faceId == id) return;
        this.faceId = id;
        if (id > 0) {
            if (imgFace == null) {
                try { imgFace = Image.createImage("/Face.png"); } catch (Exception e) {}
            }
        } else if (imgFace != null) {
            imgFace = null;
            System.gc();
        }
        this.dirty = true;
    }

    public void setWaitingNod(boolean w) {
        this.waitingNod = w;
        this.dirty = true;
    }

    public void setWaitingYn(boolean w, int choice) {
        this.waitingYn = w;
        this.ynChoice = choice;
        this.dirty = true;
    }

    public void markCampDirty() {
        this.dirty = true;
    }

    public void clear() {
        lines[0] = "";
        lines[1] = "";
        lines[2] = "";
        curLine = 0;
        dirty = true;
    }

    public void appendChar(char c) {
        if (c == '\r') return;
        if (c == '\n') {
            if (curLine < 2) {
                curLine++;
            } else {
                lines[0] = lines[1];
                lines[1] = lines[2];
                lines[2] = "";
            }
        } else {
            lines[curLine] = lines[curLine] + c;
        }
        dirty = true;
    }

    private void blitRotIcon(Graphics g, Image rotSheet, int srcX, int srcY, int w, int h, int dstX, int dstY) {
        if (rotSheet == null) return;
        int origH = rotSheet.getWidth();
        int rotSrcX = origH - srcY - h;
        int rotSrcY = srcX;
        if (rotSrcX >= 0 && rotSrcY >= 0 && rotSrcX + h <= origH && rotSrcY + w <= rotSheet.getHeight()) {
            g.drawRegion(rotSheet, rotSrcX, rotSrcY, h, w, Sprite.TRANS_ROT270, dstX, dstY, Graphics.TOP | Graphics.LEFT);
        }
    }

    public void paintCampMenu(Graphics g, int section, int cursorIdx,
                              int armCount, int[] armsCode, int[] armsLevel, int[] armsAmmo, int[] armsMaxAmmo,
                              int itemCount, int[] items) {
        if (dirty) {
            sharedG.setColor(0x000028);
            sharedG.fillRect(0, 0, 244, 152);
            sharedG.setColor(0xFFFFFF);
            sharedG.drawRect(1, 1, 241, 149);
            sharedG.setFont(sysFont);

            sharedG.setColor(0x88CCFF);
            sharedG.drawString("ぶき", 8, 4, Graphics.TOP | Graphics.LEFT);

            for (int i = 0; i < armCount && i < 6; i++) {
                int wx = 10 + i * 38;
                int wy = 20;
                if (section == 0 && cursorIdx == i) {
                    sharedG.setColor(0xFFFF00);
                    sharedG.drawRect(wx - 2, wy - 2, 34, 30);
                }
                blitRotIcon(sharedG, imgArms, armsCode[i] << 4, 0, 16, 16, wx + 6, wy);
                sharedG.setColor(0xFFFFFF);
                sharedG.drawString("Lv" + armsLevel[i], wx + 2, wy + 16, Graphics.TOP | Graphics.LEFT);
            }

            sharedG.setColor(0x88CCFF);
            sharedG.drawString("もちもの", 8, 56, Graphics.TOP | Graphics.LEFT);

            for (int i = 0; i < itemCount && i < 18; i++) {
                int col = i % 6;
                int row = i / 6;
                int ix = 10 + col * 38;
                int iy = 72 + row * 19;
                if (section == 1 && cursorIdx == i) {
                    sharedG.setColor(0xFFFF00);
                    sharedG.drawRect(ix - 2, iy - 2, 34, 20);
                }
                int code = items[i];
                int cols = (imgItem != null && imgItem.getHeight() >= 32) ? (imgItem.getHeight() / 32) : 8;
                if (cols < 1) cols = 8;
                int sx = (code % cols) * 32;
                int sy = (code / cols) * 16;
                blitRotIcon(sharedG, imgItem, sx, sy, 32, 16, ix, iy);
            }
            // description of the highlighted weapon / item (drawn here: the normal message box shares this buffer)
            sharedG.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));
            sharedG.setColor(0xFFFFFF);
            sharedG.drawString(lines[0], 8, 128, Graphics.TOP | Graphics.LEFT);
            sharedG.drawString(lines[1], 8, 138, Graphics.TOP | Graphics.LEFT);
            dirty = false;
        }

        g.drawRegion(sharedBuffer, 0, 0, 244, 152, Sprite.TRANS_ROT90, 70, 38, Graphics.TOP | Graphics.LEFT);
    }

    public void paintTeleporterMenu(Graphics g, int cursorIdx, int permitCount, int[] permitIndex) {
        if (dirty) {
            sharedG.setColor(0x000028);
            sharedG.fillRect(0, 0, 244, 64);
            sharedG.setColor(0xFFFFFF);
            sharedG.drawRect(1, 1, 241, 61);
            sharedG.setFont(sysFont);

            sharedG.setColor(0x88CCFF);
            sharedG.drawString("てんそうさき", 8, 6, Graphics.TOP | Graphics.LEFT);

            for (int i = 0; i < permitCount && i < 6; i++) {
                int sx = 10 + i * 38;
                int sy = 28;
                int stgIdx = permitIndex[i];
                if (imgStageImage != null) {
                    blitRotIcon(sharedG, imgStageImage, stgIdx * 32, 0, 32, 16, sx, sy);
                } else {
                    sharedG.setColor(0x204080);
                    sharedG.fillRect(sx, sy, 32, 16);
                    sharedG.setColor(0xFFFFFF);
                    sharedG.drawString("#" + stgIdx, sx + 6, sy + 2, Graphics.TOP | Graphics.LEFT);
                }
                if (cursorIdx == i) {
                    sharedG.setColor(0xFFFF00);
                    sharedG.drawRect(sx - 2, sy - 2, 35, 19);
                }
            }
            dirty = false;
        }

        g.drawRegion(sharedBuffer, 0, 0, 244, 64, Sprite.TRANS_ROT90, 80, 38, Graphics.TOP | Graphics.LEFT);
    }

    public void paint(Graphics g) {
        if (!visible) return;

        if (visible) {
            if (dirty) {
                sharedG.setColor(0x000028);
                sharedG.fillRect(0, 0, 244, 56);
                sharedG.setColor(0xFFFFFF);
                sharedG.drawRect(1, 1, 241, 53);
                sharedG.setFont(sysFont);

                int textX = 8;
                if (faceId > 0 && imgFace != null) {
                    int cols = imgFace.getHeight() / 48;
                    if (cols < 1) cols = 6;
                    int fx = (faceId % cols) * 48;
                    int fy = (faceId / cols) * 48;
                    blitRotIcon(sharedG, imgFace, fx, fy, 48, 48, 5, 4);
                    textX = 58;
                }

                sharedG.drawString(lines[0], textX, 5, Graphics.TOP | Graphics.LEFT);
                sharedG.drawString(lines[1], textX, 21, Graphics.TOP | Graphics.LEFT);
                sharedG.drawString(lines[2], textX, 37, Graphics.TOP | Graphics.LEFT);

                if (waitingNod) {
                    sharedG.fillRect(231, 44, 6, 6);
                }
                dirty = false;
            }

            g.drawRegion(sharedBuffer, 0, 0, 244, 56,
                         Sprite.TRANS_ROT90, 6, 38,
                         Graphics.TOP | Graphics.LEFT);
        }

    }
}