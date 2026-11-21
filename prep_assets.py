"""
prep_assets.py  - Cave Story -> Nokia 6300 asset packer

Run from the project folder that contains  raw_data/  (the Cave Story "data"
folder, plus Doukutsu.exe).  Output goes to  res/

What changed compared with v1 (see CHANGES at the bottom of the file):
  * stage table is read from stage 0 (v1 started at stage 1, shifting every level by one)
  * npctbl.bin now keeps the NPC flag word and the full hit / view rectangles
  * stages.bin now also carries the background type + background image name
  * background images (bk*.png) are no longer colour-keyed on black
  * much better Organya -> MIDI conversion (loop length, volume, pan, note cut-off,
    instrument choice derived from the real WAVE100 waveforms, correct fallback table)
"""
import io
import math
import os
import shutil
import stat
import struct
import sys
from PIL import Image

OUT_DIR = "res"
NUM_STAGES = 95
STAGE_REC = 200


# ----------------------------------------------------------------------------
# small helpers
# ----------------------------------------------------------------------------
def ensure_writable(path):
    if os.path.exists(path):
        try:
            os.chmod(path, stat.S_IWRITE)
        except Exception:
            pass


def safe_copy(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    ensure_writable(dst)
    shutil.copyfile(src, dst)


def find_raw_dir():
    for c in ["raw_data", os.path.join("raw_data", "data"), "data", os.path.join("..", "data")]:
        if os.path.exists(os.path.join(c, "MyChar.pbm")) or os.path.exists(os.path.join(c, "MyChar.bmp")):
            return c
    return None


def find_subdir(parent, name):
    """case-insensitive sub directory lookup"""
    if not os.path.isdir(parent):
        return None
    for d in os.listdir(parent):
        if d.lower() == name.lower() and os.path.isdir(os.path.join(parent, d)):
            return os.path.join(parent, d)
    return None


def find_exe(raw_dir):
    for c in [os.path.join(raw_dir, "..", "Doukutsu.exe"),
              os.path.join(raw_dir, "Doukutsu.exe"),
              "Doukutsu.exe",
              os.path.join("raw_data", "Doukutsu.exe")]:
        if os.path.exists(c):
            return c
    return None


# ----------------------------------------------------------------------------
# images
# ----------------------------------------------------------------------------
# The engine only ever reads these rows (original, un-rotated pixel rows from the top) of a few
# big sheets, so the rest is cropped away to save heap on the phone (a 320x240 sheet costs ~300 KB
# once decoded)
CROP_ROWS = {
    "arms": 16,      # HUD weapon icon = first 16 px row
    "caret": 64,     # engine uses the row at y=48..63
    "bullet": 48,    # engine uses the row at y=32..47
}


# ----------------------------------------------------------------------------
# weapon bullet sprites: one small atlas per weapon (B<code>.png) so the phone only decodes the
# sprites of weapons that are actually fired.  bulrects.bin: for every weapon code u8 code, u8 count,
# then per sprite 6 x u16 (srcX, srcY, w, h, atlasX, atlasY) in original (unrotated) coordinates.
# The sprite rectangles are the ones the original ActBullet_* functions use.
# ----------------------------------------------------------------------------
BULLET_FAMILIES = {1: [[120, 64, 136, 80], [120, 80, 136, 96], [136, 64, 152, 80], [192, 16, 208, 32], [208, 16, 224, 32], [224, 16, 240, 32]], 2: [[128, 32, 144, 48], [128, 48, 144, 64], [144, 32, 160, 48], [144, 48, 160, 64], [160, 32, 176, 48], [176, 32, 192, 48]], 3: [[128, 0, 144, 16], [128, 16, 144, 32], [144, 0, 160, 16], [144, 16, 160, 32], [160, 0, 176, 16], [160, 16, 176, 32], [176, 0, 192, 16], [176, 16, 192, 32], [192, 16, 208, 32], [208, 16, 224, 32], [224, 16, 240, 32]], 4: [[64, 0, 80, 16], [64, 16, 80, 32], [64, 32, 80, 48], [80, 0, 96, 16], [80, 16, 96, 32], [80, 32, 96, 48], [96, 0, 112, 16], [96, 16, 112, 32], [96, 32, 112, 48], [112, 0, 128, 16], [112, 16, 128, 32], [112, 32, 128, 48]], 5: [[0, 0, 16, 16], [0, 16, 16, 32], [0, 32, 16, 48], [16, 0, 32, 16], [16, 16, 32, 32], [16, 32, 32, 48], [32, 0, 48, 16], [32, 16, 48, 32], [32, 32, 48, 48], [48, 0, 64, 16], [48, 16, 64, 32], [48, 32, 64, 48]], 7: [[192, 0, 200, 8], [192, 8, 200, 16], [200, 0, 208, 8], [200, 8, 208, 16], [208, 0, 216, 8], [208, 8, 216, 16], [216, 0, 224, 8], [216, 8, 224, 16], [224, 0, 232, 8], [224, 8, 232, 16], [232, 0, 240, 8], [232, 8, 240, 16], [240, 16, 248, 24], [240, 24, 248, 32], [248, 16, 256, 24], [248, 24, 256, 32]], 9: [[0, 48, 16, 64], [0, 64, 24, 88], [0, 88, 24, 112], [16, 48, 32, 64], [24, 64, 48, 88], [24, 88, 48, 112], [32, 48, 48, 64], [48, 48, 64, 64], [48, 64, 72, 88], [48, 88, 72, 112], [64, 48, 80, 64], [72, 64, 96, 88], [72, 88, 96, 112], [80, 48, 96, 64], [96, 48, 112, 64], [96, 64, 120, 88], [96, 88, 120, 112], [112, 48, 128, 64], [160, 48, 184, 72], [160, 72, 184, 96], [184, 48, 208, 72], [184, 72, 208, 96], [208, 48, 232, 72], [208, 72, 232, 96], [232, 48, 256, 72], [232, 72, 256, 96], [272, 0, 296, 24], [272, 24, 296, 48], [272, 48, 296, 72], [296, 0, 320, 24], [296, 24, 320, 48], [296, 48, 320, 72]], 10: [[120, 96, 136, 112], [136, 96, 152, 112], [152, 96, 168, 112], [168, 96, 184, 112], [184, 96, 200, 112], [200, 96, 216, 112], [216, 96, 232, 112], [232, 96, 248, 112]], 12: [[0, 112, 32, 128], [0, 128, 32, 144], [0, 144, 32, 160], [0, 160, 32, 176], [32, 112, 48, 144], [32, 144, 48, 176], [48, 112, 64, 144], [48, 144, 64, 176], [64, 112, 96, 128], [64, 128, 96, 144], [64, 144, 96, 160], [64, 160, 96, 176], [96, 112, 112, 144], [96, 144, 112, 176], [112, 112, 128, 144], [112, 144, 128, 176], [128, 112, 160, 128], [128, 128, 160, 144], [160, 112, 176, 144], [176, 112, 192, 144], [192, 112, 224, 128], [192, 128, 224, 144], [224, 112, 240, 144], [240, 112, 256, 144]], 13: [[128, 32, 144, 48], [128, 48, 144, 64], [144, 32, 160, 48], [144, 48, 160, 64], [160, 32, 176, 48], [176, 32, 192, 48], [192, 32, 200, 40], [192, 40, 200, 48], [200, 32, 208, 40], [200, 40, 208, 48], [208, 32, 216, 40], [208, 40, 216, 48], [216, 32, 224, 40], [216, 40, 224, 48], [224, 32, 232, 40], [224, 40, 232, 48], [232, 32, 240, 40], [232, 40, 240, 48], [240, 32, 248, 40], [248, 32, 256, 40], [256, 32, 264, 40]]}



# ---------------------------------------------------------------------------------------------
# PixTone sound effects.  Doukutsu.exe contains no samples: every effect is synthesised from a table
# of parameters (identical to the table inside the exe).  This is a port of the original synthesiser
# ---------------------------------------------------------------------------------------------
PIXTONE_PARAMS = [(5000, 5, 10.0, 32, 0, 4, 4.0, 32, 0, 0, 0.0, 63, 0, 63, 6, 63, 45, 8, 119, 46), (1000, 0, 4.0, 32, 0, 3, 1.0, 63, 0, 0, 0.0, 63, 0, 63, 64, 63, 128, 63, 255, 63), (10000, 0, 30.0, 32, 0, 3, 1.0, 32, 0, 0, 0.0, 63, 0, 0, 19, 44, 111, 13, 198, 9), (10000, 5, 2.0, 31, 0, 3, 1.0, 57, 219, 0, 2.0, 32, 0, 0, 19, 44, 111, 13, 198, 9), (4000, 5, 0.4, 32, 0, 3, 1.0, 53, 0, 0, 0.0, 63, 0, 12, 19, 63, 111, 21, 198, 18), (1000, 1, 12.0, 32, 0, 2, 1.0, 63, 0, 0, 0.0, 63, 0, 63, 64, 63, 128, 63, 255, 0), (1000, 5, 1.0, 32, 0, 3, 1.0, 63, 0, 0, 0.0, 63, 0, 0, 28, 63, 53, 31, 210, 31), (1000, 1, 5.0, 32, 0, 3, 1.0, 63, 0, 0, 0.0, 0, 0, 63, 64, 63, 128, 31, 255, 0), (3000, 1, 17.0, 34, 0, 3, 2.0, 40, 0, 4, 1.0, 31, 0, 63, 64, 63, 225, 63, 255, 0), (6000, 1, 930.0, 22, 0, 0, 0.7, 53, 0, 0, 7.0, 32, 0, 63, 64, 63, 202, 63, 255, 0), (6000, 1, 918.0, 23, 0, 0, 0.7, 53, 0, 0, 7.0, 32, 0, 63, 64, 63, 202, 63, 255, 0), (10000, 2, 200.0, 32, 0, 0, 1.0, 51, 0, 1, 20.0, 31, 0, 63, 64, 63, 89, 30, 208, 28), (10000, 5, 23.0, 16, 0, 0, 1.0, 58, 0, 1, 17.0, 32, 0, 63, 64, 63, 96, 51, 202, 31), (20000, 2, 100.0, 21, 0, 0, 1.0, 46, 0, 1, 40.0, 32, 0, 63, 64, 63, 128, 63, 162, 0), (20000, 5, 5.0, 21, 0, 0, 1.0, 51, 0, 1, 40.0, 32, 0, 63, 64, 63, 128, 63, 162, 28), (10000, 5, 20.0, 23, 0, 3, 0.7, 26, 235, 5, 1.0, 9, 0, 63, 38, 55, 87, 32, 98, 29), (2000, 1, 20.0, 32, 0, 3, 2.0, 63, 0, 4, 1.0, 29, 0, 63, 64, 63, 128, 63, 255, 63), (15000, 5, 10.0, 32, 0, 3, 0.7, 26, 235, 0, 0.0, 63, 0, 63, 38, 55, 187, 15, 255, 0), (4000, 1, 20.0, 32, 0, 3, 2.0, 63, 0, 4, 1.0, 29, 0, 63, 64, 63, 128, 63, 255, 63), (22000, 5, 6.0, 32, 0, 3, 0.7, 26, 246, 0, 0.0, 63, 0, 63, 38, 55, 187, 15, 255, 0), (8000, 0, 20.0, 32, 0, 3, 2.0, 63, 0, 4, 1.0, 29, 0, 63, 64, 63, 128, 63, 255, 63), (10000, 4, 400.0, 13, 0, 0, 0.8, 63, 0, 4, 8.0, 31, 0, 63, 64, 63, 191, 32, 255, 0), (10000, 4, 800.0, 14, 0, 0, 0.8, 63, 0, 4, 8.0, 30, 125, 63, 64, 63, 128, 63, 166, 0), (5000, 2, 50.0, 39, 0, 3, 0.5, 40, 217, 1, 0.0, 32, 0, 63, 64, 63, 128, 34, 198, 32), (5000, 5, 10.0, 39, 0, 3, 0.5, 24, 217, 1, 4.0, 32, 0, 0, 4, 63, 128, 34, 198, 32), (40000, 5, 10.0, 32, 0, 3, 1.0, 32, 241, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (40000, 5, 20.0, 32, 0, 0, 0.0, 32, 0, 5, 0.1, 32, 0, 63, 64, 63, 128, 63, 255, 0), (30000, 2, 400.0, 32, 0, 3, 0.3, 60, 250, 0, 20.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (10000, 4, 400.0, 13, 0, 0, 0.8, 63, 0, 4, 8.0, 50, 0, 63, 64, 63, 191, 32, 255, 0), (10000, 4, 800.0, 5, 0, 0, 0.8, 63, 0, 4, 8.0, 63, 125, 63, 64, 63, 128, 63, 166, 0), (6000, 3, 123.0, 32, 0, 4, 1.0, 16, 222, 4, 4.0, 37, 0, 0, 6, 63, 104, 25, 255, 0), (4000, 5, 4.5, 32, 0, 3, 1.0, 46, 102, 3, 2.5, 47, 0, 63, 64, 63, 128, 63, 255, 0), (5000, 4, 40.0, 52, 0, 2, 1.0, 63, 0, 3, 10.0, 63, 0, 63, 47, 27, 47, 29, 255, 0), (3000, 0, 99.0, 32, 0, 2, 1.0, 55, 197, 5, 0.0, 0, 0, 63, 0, 63, 164, 28, 255, 0), (10000, 1, 601.0, 32, 0, 4, 0.5, 15, 235, 0, 10.0, 54, 0, 63, 0, 63, 0, 63, 255, 0), (8000, 5, 10.0, 15, 0, 4, 0.5, 16, 239, 4, 0.0, 50, 0, 63, 0, 63, 96, 17, 255, 0), (20000, 1, 832.0, 32, 0, 2, 1.0, 46, 0, 0, 27.0, 63, 0, 63, 0, 63, 140, 10, 255, 0), (20000, 1, 918.0, 32, 0, 2, 1.0, 46, 0, 0, 21.0, 63, 0, 63, 0, 63, 140, 10, 255, 0), (4000, 1, 54.0, 32, 0, 5, 0.1, 33, 0, 0, 0.0, 32, 0, 53, 57, 44, 128, 24, 255, 0), (10000, 1, 246.0, 23, 0, 4, 0.6, 22, 239, 4, 6.0, 63, 0, 0, 11, 63, 13, 63, 255, 0), (10000, 1, 294.0, 23, 0, 4, 0.6, 22, 247, 4, 6.0, 63, 140, 0, 15, 63, 17, 63, 255, 0), (22050, 0, 117.0, 63, 0, 5, 2.0, 18, 0, 5, 0.0, 0, 0, 63, 0, 63, 64, 19, 255, 0), (5000, 0, 28.0, 32, 0, 3, 3.0, 27, 0, 5, 0.0, 0, 0, 63, 0, 63, 0, 63, 255, 0), (10000, 0, 322.0, 32, 0, 2, 8.0, 37, 0, 2, 0.0, 0, 0, 0, 13, 63, 106, 11, 255, 0), (10000, 5, 7.3, 32, 0, 5, 0.2, 29, 0, 0, 0.0, 32, 0, 63, 91, 63, 149, 25, 255, 0), (1000, 0, 6.0, 32, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 63), (20000, 2, 186.0, 32, 0, 0, 4.0, 13, 98, 3, 4.0, 5, 0, 63, 64, 28, 255, 0, 255, 0), (20000, 2, 285.0, 19, 0, 3, 4.0, 21, 0, 3, 4.0, 33, 130, 63, 64, 63, 255, 0, 255, 0), (10000, 0, 970.0, 32, 0, 2, 1.0, 35, 195, 0, 31.0, 31, 0, 63, 64, 63, 128, 63, 255, 0), (20000, 5, 6.0, 32, 0, 3, 1.0, 54, 239, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 35), (40000, 5, 4.0, 32, 0, 3, 0.0, 32, 230, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (40000, 1, 238.0, 32, 0, 3, 1.0, 14, 0, 4, 30.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (3000, 2, 62.0, 32, 0, 2, 3.0, 63, 0, 3, 3.0, 14, 0, 63, 0, 63, 210, 32, 255, 0), (5000, 2, 58.0, 32, 0, 2, 3.0, 63, 0, 2, 3.0, 32, 0, 63, 0, 63, 49, 27, 255, 0), (3000, 0, 13.0, 24, 0, 3, 2.0, 40, 0, 4, 1.0, 31, 0, 63, 64, 63, 225, 63, 255, 0), (3000, 5, 6.0, 32, 0, 5, 1.0, 32, 0, 3, 0.0, 0, 0, 0, 0, 63, 45, 23, 255, 0), (20000, 1, 477.0, 40, 0, 5, 93.0, 39, 0, 4, 17.0, 19, 0, 0, 64, 63, 128, 63, 255, 0), (6000, 5, 11.0, 32, 0, 5, 1.0, 32, 0, 3, 3.0, 32, 0, 63, 0, 63, 0, 63, 255, 0), (6000, 1, 329.0, 20, 0, 2, 2.0, 47, 77, 3, 3.0, 63, 0, 63, 64, 63, 128, 63, 255, 0), (8000, 4, 2000.0, 32, 0, 2, 1.0, 0, 0, 0, 0.0, 32, 0, 43, 21, 7, 255, 0, 255, 0), (5000, 1, 231.0, 32, 0, 4, 1.0, 32, 65, 3, 2.0, 32, 0, 63, 64, 63, 128, 63, 255, 63), (3000, 0, 107.0, 32, 0, 4, 1.0, 15, 0, 0, 0.0, 17, 0, 63, 64, 63, 128, 63, 255, 63), (20000, 5, 4.0, 32, 0, 5, 1.0, 32, 170, 5, 0.0, 32, 0, 63, 38, 22, 255, 0, 255, 0), (5000, 1, 16.0, 32, 0, 3, 1.0, 32, 238, 0, 0.0, 0, 0, 63, 64, 63, 128, 63, 255, 63), (20000, 5, 4.0, 32, 0, 0, 0.7, 61, 43, 3, 1.0, 22, 224, 63, 64, 63, 204, 46, 255, 0), (5000, 0, 880.0, 19, 0, 0, 0.0, 32, 0, 0, 8.0, 32, 0, 0, 11, 63, 34, 25, 255, 0), (2000, 5, 3.0, 16, 0, 3, 2.0, 12, 0, 3, 1.0, 37, 0, 0, 51, 63, 132, 24, 255, 0), (22050, 1, 400.0, 32, 0, 0, 20.0, 10, 0, 3, 10.0, 8, 0, 0, 6, 63, 60, 21, 255, 0), (2000, 5, 2.0, 32, 0, 2, 3.0, 54, 0, 0, 0.0, 32, 0, 0, 17, 63, 98, 22, 255, 0), (8000, 1, 814.0, 32, 0, 2, 11.0, 32, 0, 3, 16.0, 32, 0, 63, 23, 63, 74, 12, 255, 0), (10000, 5, 21.0, 21, 0, 0, 5.0, 32, 178, 0, 3.0, 33, 181, 63, 38, 63, 104, 20, 255, 0), (6000, 5, 1.0, 28, 0, 3, 6.0, 56, 0, 0, 8.0, 32, 0, 63, 57, 63, 98, 20, 255, 0), (4000, 5, 6.0, 32, 0, 3, 2.0, 32, 0, 3, 2.0, 32, 0, 63, 26, 30, 66, 29, 255, 0), (22050, 5, 711.0, 32, 0, 5, 7.0, 32, 0, 0, 0.0, 32, 0, 0, 13, 0, 17, 63, 255, 0), (2000, 5, 2.0, 32, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 63), (62050, 5, 40.0, 32, 0, 5, 1.0, 32, 0, 5, 0.0, 0, 0, 0, 17, 63, 36, 63, 255, 0), (8000, 0, 77.0, 32, 0, 3, 3.0, 56, 189, 0, 0.0, 17, 0, 0, 38, 63, 140, 28, 255, 0), (8000, 5, 8.0, 32, 0, 3, 3.0, 54, 189, 3, 3.0, 32, 0, 63, 38, 42, 140, 21, 255, 0), (9050, 5, 9.0, 26, 0, 0, 1.0, 32, 209, 0, 0.0, 32, 0, 63, 64, 63, 132, 63, 255, 0), (9050, 2, 43.0, 32, 0, 3, 1.0, 47, 172, 0, 0.0, 32, 0, 63, 64, 63, 198, 63, 255, 0), (22050, 1, 754.0, 32, 0, 2, 0.5, 14, 126, 4, 18.0, 17, 0, 0, 64, 63, 128, 63, 255, 0), (22050, 0, 597.0, 12, 0, 2, 0.5, 14, 126, 4, 18.0, 17, 0, 0, 64, 40, 128, 41, 255, 0), (6000, 2, 217.0, 32, 0, 0, 0.7, 16, 0, 3, 0.0, 32, 0, 0, 32, 63, 49, 15, 255, 0), (5000, 5, 1.0, 32, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 0, 64, 63, 81, 28, 255, 0), (10000, 5, 11.0, 32, 0, 2, 4.0, 32, 0, 0, 3.0, 32, 0, 0, 64, 63, 128, 30, 255, 0), (10000, 5, 1.0, 32, 0, 4, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 32, 128, 33, 255, 0), (10000, 2, 168.0, 32, 0, 0, 0.5, 29, 173, 0, 0.0, 32, 0, 0, 13, 63, 68, 35, 255, 0), (10000, 2, 50.0, 32, 0, 0, 0.5, 29, 173, 2, 100.0, 32, 0, 0, 13, 63, 68, 35, 255, 0), (4000, 5, 11.0, 25, 0, 3, 3.0, 32, 0, 0, 0.0, 32, 0, 0, 9, 63, 128, 14, 255, 0), (4000, 5, 3.0, 27, 0, 3, 3.0, 32, 0, 0, 0.0, 32, 0, 0, 9, 63, 128, 14, 255, 0), (3000, 4, 9.0, 20, 0, 3, 3.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (3000, 4, 18.0, 20, 0, 3, 3.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 0), (3000, 5, 4.0, 32, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 0, 64, 63, 128, 63, 255, 0), (12000, 5, 2.0, 32, 0, 3, 1.7, 53, 0, 0, 0.0, 32, 0, 63, 64, 63, 162, 25, 255, 0), (12000, 3, 77.0, 32, 0, 3, 1.0, 61, 200, 0, 19.0, 22, 0, 63, 64, 63, 172, 25, 255, 0), (5000, 0, 16.0, 63, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 166, 35, 255, 0), (1000, 5, 1.0, 16, 0, 0, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 91, 28, 255, 0), (5000, 0, 20.0, 30, 0, 3, 1.0, 44, 0, 0, 0.0, 32, 0, 63, 64, 63, 111, 19, 255, 0), (10000, 5, 14.0, 41, 0, 5, 3.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 18, 91, 12, 255, 0), (1000, 5, 48.0, 30, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 166, 27, 255, 0), (10000, 5, 48.0, 30, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 43, 166, 41, 255, 7), (4000, 5, 35.0, 30, 0, 3, 35.0, 32, 0, 0, 0.0, 32, 0, 63, 53, 21, 166, 13, 255, 0), (10000, 1, 63.0, 32, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 39, 91, 20, 255, 0), (22050, 5, 52.0, 22, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 3, 23, 23, 57, 10, 255, 0), (22050, 5, 80.0, 22, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 23, 23, 57, 10, 255, 0), (44100, 5, 54.0, 12, 0, 5, 1.0, 32, 0, 5, 1.0, 27, 0, 40, 64, 63, 128, 38, 255, 63), (44100, 5, 119.0, 9, 0, 5, 2.0, 32, 0, 0, 0.0, 32, 0, 61, 64, 39, 128, 60, 255, 28), (4000, 5, 11.0, 32, 0, 5, 1.0, 17, 197, 0, 0.0, 32, 0, 51, 100, 63, 100, 0, 255, 0), (4000, 5, 21.0, 32, 0, 5, 1.0, 17, 197, 0, 0.0, 32, 0, 0, 119, 0, 117, 63, 255, 0), (5000, 2, 143.0, 25, 0, 3, 0.5, 40, 217, 1, 0.0, 32, 0, 63, 64, 63, 128, 34, 198, 32), (5000, 5, 10.0, 23, 0, 3, 0.5, 24, 217, 1, 4.0, 32, 0, 0, 4, 63, 128, 34, 198, 32), (4000, 5, 6.0, 32, 0, 3, 2.0, 32, 0, 3, 2.0, 32, 0, 63, 26, 30, 66, 29, 255, 0), (4000, 0, 150.0, 32, 0, 0, 0.0, 32, 0, 3, 2.0, 32, 0, 63, 26, 30, 66, 29, 255, 0), (40050, 4, 100.0, 32, 0, 3, 8.0, 22, 0, 3, 8.0, 8, 0, 63, 28, 44, 81, 23, 255, 0), (40050, 4, 150.0, 32, 0, 3, 8.0, 22, 0, 3, 8.0, 8, 0, 63, 28, 44, 81, 23, 255, 0), (40050, 4, 294.0, 32, 0, 3, 8.0, 22, 0, 3, 8.0, 8, 0, 63, 28, 44, 81, 23, 255, 0), (5000, 3, 621.0, 32, 0, 2, 2.0, 22, 0, 3, 6.0, 32, 0, 0, 4, 63, 77, 30, 255, 0), (30000, 5, 28.0, 44, 0, 3, 1.0, 45, 0, 0, 0.0, 32, 0, 63, 64, 63, 208, 63, 255, 0), (30000, 2, 101.0, 44, 0, 2, 0.5, 63, 118, 0, 0.0, 32, 0, 63, 64, 63, 176, 63, 255, 0), (30000, 2, 86.0, 44, 0, 2, 0.5, 63, 118, 0, 0.0, 32, 0, 63, 64, 63, 176, 63, 255, 0), (2000, 5, 1.0, 32, 0, 0, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 63), (2000, 3, 99.0, 12, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 63, 64, 63, 128, 63, 255, 63), (6000, 3, 388.0, 22, 0, 0, 0.7, 53, 0, 0, 7.0, 32, 0, 25, 64, 63, 202, 63, 255, 0), (6000, 1, 918.0, 23, 0, 0, 0.7, 53, 0, 0, 7.0, 32, 0, 63, 64, 63, 202, 63, 255, 0), (10000, 5, 118.0, 19, 0, 3, 1.0, 63, 0, 0, 3.0, 32, 0, 0, 19, 63, 66, 41, 255, 0), (10000, 5, 6.0, 24, 0, 3, 1.0, 32, 208, 2, 8.0, 12, 0, 63, 64, 63, 128, 38, 255, 0), (400, 1, 20.0, 12, 0, 0, 0.0, 32, 0, 0, 0.0, 32, 0, 0, 43, 63, 193, 63, 255, 0), (400, 1, 30.0, 12, 0, 0, 0.0, 32, 0, 0, 0.0, 32, 0, 0, 43, 63, 193, 63, 255, 0), (400, 1, 40.0, 12, 0, 0, 0.0, 32, 0, 0, 0.0, 32, 0, 0, 43, 63, 193, 63, 255, 0), (8000, 3, 800.0, 24, 0, 0, 8.0, 4, 0, 0, 0.0, 32, 0, 0, 13, 63, 138, 63, 255, 0), (8000, 5, 53.0, 12, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 0, 0, 63, 68, 63, 255, 0), (8000, 3, 400.0, 24, 0, 0, 8.0, 4, 0, 0, 0.0, 32, 0, 0, 13, 63, 138, 63, 255, 0), (8000, 5, 53.0, 12, 0, 5, 1.0, 32, 0, 0, 0.0, 32, 0, 0, 0, 63, 68, 63, 255, 0), (8000, 3, 200.0, 32, 0, 0, 8.0, 4, 0, 0, 0.0, 32, 0, 0, 13, 63, 138, 63, 255, 0), (8000, 5, 25.0, 17, 0, 3, 1.0, 32, 0, 0, 0.0, 32, 0, 0, 0, 63, 68, 63, 255, 0), (8000, 4, 800.0, 32, 0, 4, 2.0, 21, 0, 4, 8.0, 32, 0, 0, 106, 63, 130, 27, 255, 0), (3000, 4, 31.0, 10, 0, 3, 2.0, 40, 0, 4, 1.0, 31, 0, 0, 9, 63, 94, 63, 255, 0), (5000, 2, 168.0, 32, 0, 0, 0.5, 29, 173, 0, 0.0, 32, 0, 0, 13, 63, 68, 35, 255, 0), (1000, 0, 20.0, 0, 0, 0, 0.0, 0, 0, 0, 0.0, 0, 0, 0, 64, 0, 128, 0, 255, 0)]
PIXTONE_OBJECTS = {32: (0, 2), 33: (2, 2), 34: (4, 2), 15: (6, 1), 24: (7, 1), 23: (8, 1), 50: (9, 2), 51: (11, 2), 1: (33, 1), 2: (38, 1), 29: (56, 1), 43: (61, 1), 44: (62, 3), 45: (65, 1), 46: (66, 1), 47: (68, 1), 35: (49, 3), 39: (52, 3), 52: (13, 2), 53: (28, 2), 70: (15, 2), 71: (17, 2), 72: (19, 2), 5: (30, 1), 11: (32, 1), 4: (35, 1), 25: (46, 2), 27: (48, 1), 28: (54, 2), 14: (39, 1), 16: (23, 2), 17: (25, 3), 18: (34, 1), 20: (36, 2), 22: (31, 1), 26: (41, 2), 21: (43, 1), 12: (44, 2), 38: (57, 2), 31: (59, 1), 42: (60, 1), 48: (69, 1), 49: (70, 2), 100: (72, 1), 101: (73, 3), 54: (76, 2), 102: (78, 2), 103: (80, 2), 104: (116, 1), 105: (82, 1), 106: (83, 2), 107: (85, 1), 30: (86, 1), 108: (87, 1), 109: (88, 1), 110: (89, 1), 111: (90, 1), 112: (91, 1), 113: (92, 1), 114: (93, 2), 150: (95, 2), 151: (97, 2), 152: (99, 1), 153: (100, 1), 154: (101, 2), 155: (111, 2), 56: (103, 2), 40: (105, 2), 41: (105, 2), 37: (107, 2), 57: (109, 2), 115: (113, 3), 116: (117, 3), 58: (120, 2), 55: (122, 2), 117: (124, 2), 59: (126, 1), 60: (127, 1), 61: (128, 1), 62: (129, 2), 63: (131, 2), 64: (133, 2), 65: (135, 1), 3: (136, 1), 6: (137, 1), 7: (138, 1)}

def _tdiv(a, b):
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q

def _pixtone_waves():
    import math
    w = [[0] * 256 for _ in range(6)]
    def s8(v):
        v &= 0xFF
        return v - 256 if v > 127 else v
    for i in range(256):
        w[0][i] = int(math.sin(i * 6.283184 / 256.0) * 64.0)
    a = 0
    for i in range(0, 0x40): w[1][i] = s8(_tdiv(a * 0x40, 0x40)); a += 1
    a = 0
    for i in range(0x40, 0xC0): w[1][i] = s8(0x40 - _tdiv(a * 0x40, 0x40)); a += 1
    a = 0
    for i in range(0xC0, 0x100): w[1][i] = s8(_tdiv(a * 0x40, 0x40) - 0x40); a += 1
    for i in range(256): w[2][i] = (i // 2) - 0x40
    for i in range(256): w[3][i] = 0x40 - (i // 2)
    for i in range(0x80): w[4][i] = 0x40
    for i in range(0x80, 0x100): w[4][i] = -0x40
    nxt = 0
    for i in range(256):
        nxt = (nxt * 214013 + 2531011) & 0xFFFFFFFF
        r = (nxt >> 16) & 0x7FFF
        w[5][i] = _tdiv(s8(r & 0xFF), 2)
    return w

_WAVES = None

def _pixtone_make(p):
    global _WAVES
    if _WAVES is None:
        _WAVES = _pixtone_waves()
    (size, mm, mnum, mtop, moff, pm, pnum, ptop, poff, vm, vnum, vtop, voff,
     initial, ax, ay, bx, by, cx, cy) = p
    env = [0] * 256
    def s8(v):
        v = int(v) & 0xFF
        return v - 256 if v > 127 else v
    i = 0
    e = float(initial)
    while i < ax:
        env[i] = s8(e); e = ((float(ay) - initial) / ax) + e; i += 1
    e = float(ay)
    while i < bx:
        env[i] = s8(e); e = ((float(by) - ay) / float(bx - ax)) + e; i += 1
    e = float(by)
    while i < cx:
        env[i] = s8(e); e = (float(cy) - by) / float(cx - bx) + e; i += 1
    e = float(cy)
    while i < 0x100:
        env[i] = s8(e); e = e - (cy / float(0x100 - cx)); i += 1
    dpitch, dmain, dvol = float(poff), float(moff), float(voff)
    d1 = 0.0 if mnum == 0.0 else 256.0 / (size / mnum)
    d2 = 0.0 if pnum == 0.0 else 256.0 / (size / pnum)
    d3 = 0.0 if vnum == 0.0 else 256.0 / (size / vnum)
    wm, wp, wv = _WAVES[mm], _WAVES[pm], _WAVES[vm]
    out = bytearray(size)
    for i in range(size):
        a = int(dmain) % 0x100
        b = int(dpitch) % 0x100
        c = int(dvol) % 0x100
        d = int(float(i * 0x100) / size)
        v = _tdiv(wm[a] * mtop, 64)
        v = _tdiv(v * (_tdiv(wv[c] * vtop, 64) + 64), 64)
        v = _tdiv(v * env[d], 64) + 128
        out[i] = v & 0xFF
        if wp[b] < 0: dmain += d1 - d1 * 0.5 * -wp[b] * ptop / 64.0 / 64.0
        else: dmain += d1 + d1 * 2.0 * wp[b] * ptop / 64.0 / 64.0
        dpitch += d2
        dvol += d3
    return out

def pixtone_samples(sid):
    start, cnt = PIXTONE_OBJECTS[sid]
    n = max(PIXTONE_PARAMS[start + k][0] for k in range(cnt))
    mixed = bytearray([0x80]) * n
    for k in range(cnt):
        tmp = bytearray([0x80]) * n
        part = _pixtone_make(PIXTONE_PARAMS[start + k])
        tmp[:len(part)] = part
        for j in range(PIXTONE_PARAMS[start + k][0]):
            s = tmp[j] + mixed[j] - 0x100
            if s < -0x7F: mixed[j] = 0
            elif s > 0x7F: mixed[j] = 0xFF
            else: mixed[j] = mixed[j] + tmp[j] - 0x80
    return bytes(mixed)

def pixtone_wav(sid, rate=11025):
    """8-bit mono WAV.  The original is 22050 Hz; pairs of samples are averaged to halve the jar size and
    the memory a phone needs (rate=22050 keeps full quality)."""
    s = pixtone_samples(sid)
    if rate == 11025:
        s = bytes((s[i] + s[i + 1]) // 2 for i in range(0, len(s) - 1, 2))
    n = len(s)
    hdr = b"RIFF" + struct.pack("<I", 36 + n) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate, 1, 8) + b"data" + struct.pack("<I", n)
    return hdr + s

def build_sfx(out_dir):
    done = 0
    for sid in sorted(PIXTONE_OBJECTS):
        path = os.path.join(out_dir, "x%d.wav" % sid)
        ensure_writable(path)
        with open(path, "wb") as f:
            f.write(pixtone_wav(sid))
        done += 1
    print("Packed %d sound effects (PixTone synthesis, x<N>.wav)" % done)


def build_bullet_atlases(raw_dir, out_dir):
    src = None
    for root, _, files in os.walk(raw_dir):
        for fn in files:
            if fn.lower() == "bullet.pbm":
                src = os.path.join(root, fn)
    if not src:
        print("[WARN] Bullet.pbm not found - weapon shots will be drawn as plain shapes")
        return
    with open(src, "rb") as f:
        data = bytearray(f.read())
    if data[:3] == b"PBM":
        data[0:3] = b"BM\x00"
    sheet = Image.open(io.BytesIO(data)).convert("RGBA")
    px = sheet.load()
    for y in range(sheet.height):
        for x in range(sheet.width):
            if px[x, y][:3] == (0, 0, 0):
                px[x, y] = (0, 0, 0, 0)
    index = bytearray()
    index += struct.pack(">B", len(BULLET_FAMILIES))
    for code in sorted(BULLET_FAMILIES):
        rects = BULLET_FAMILIES[code]
        # shelf packing, widest rows 128 px
        rects_sorted = sorted(rects, key=lambda r: (-(r[3] - r[1]), r[0]))
        x = y = rowh = 0
        placed = []
        W = 128
        for r in rects_sorted:
            w, h = r[2] - r[0], r[3] - r[1]
            if x + w > W:
                x = 0
                y += rowh
                rowh = 0
            placed.append((r, x, y))
            x += w
            rowh = max(rowh, h)
        H = y + rowh
        atlas = Image.new("RGBA", (W, max(H, 1)), (0, 0, 0, 0))
        for r, ax, ay in placed:
            atlas.paste(sheet.crop((r[0], r[1], r[2], r[3])), (ax, ay))
        rotated = atlas.transpose(Image.ROTATE_270)
        q = rotated.quantize(colors=32, method=Image.Quantize.FASTOCTREE)
        path = os.path.join(out_dir, "B%d.png" % code)
        ensure_writable(path)
        q.save(path)
        index += struct.pack(">BB", code, len(placed))
        for r, ax, ay in placed:
            index += struct.pack(">6H", r[0], r[1], r[2] - r[0], r[3] - r[1], ax, ay)
        index_h = H
    path = os.path.join(out_dir, "bulrects.bin")
    ensure_writable(path)
    with open(path, "wb") as f:
        f.write(index)
    print("Wrote weapon bullet atlases (B*.png) and bulrects.bin")


def convert_pbm_file(src, out_path, colors=64):
    with open(src, "rb") as f:
        data = bytearray(f.read())
    if data[:3] == b"PBM":
        data[0:3] = b"BM\x00"

    img = Image.open(io.BytesIO(data)).convert("RGBA")

    # Cave Story uses pure black as the transparency colour key for sprite sheets and
    # tile sets.  Background images (bk*.pbm) are drawn opaque, so keep their blacks.
    is_background = os.path.basename(src).lower().startswith("bk")
    if not is_background:
        px = img.load()
        for y in range(img.height):
            for x in range(img.width):
                if px[x, y][:3] == (0, 0, 0):
                    px[x, y] = (0, 0, 0, 0)

    rows = CROP_ROWS.get(os.path.splitext(os.path.basename(src))[0].lower())
    if rows and img.height > rows:
        img = img.crop((0, 0, img.width, rows))

    # the whole engine draws the world rotated by 90 degrees (portrait screen)
    rotated = img.transpose(Image.ROTATE_270)
    quantized = rotated.quantize(colors=colors, method=Image.Quantize.FASTOCTREE)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    ensure_writable(out_path)
    quantized.save(out_path)   # plain 8-bit palette PNG: no 2/4-bit depths, safest for phone decoders


# ----------------------------------------------------------------------------
# TSC
# ----------------------------------------------------------------------------
def convert_tsc_to_utf8(src_path, dst_path):
    if not os.path.exists(src_path):
        return
    data = None
    last_err = None
    for attempt in range(3):                       # Windows can refuse a read (locked / cloud-synced file)
        try:
            with open(src_path, "rb", buffering=0) as f:
                chunks = []
                while True:
                    c = f.read(65536)
                    if not c:
                        break
                    chunks.append(c)
            data = bytearray(b"".join(chunks))
            break
        except OSError as e:
            last_err = e
            try:
                import time
                time.sleep(0.3)
            except Exception:
                pass
    if data is None:
        print("[WARN] cannot read %s (%s) - skipped; copy the file somewhere local and retry" % (src_path, last_err))
        return False
    if not data:
        return
    key_idx = len(data) // 2
    key = data[key_idx]
    for i in range(len(data)):
        if i != key_idx:
            data[i] = (data[i] - key) & 0xFF
    decoded = data.decode("cp932", errors="replace")
    os.makedirs(os.path.dirname(dst_path), exist_ok=True)
    ensure_writable(dst_path)
    with open(dst_path, "w", encoding="utf-8", newline="\n") as out:
        out.write(decoded)


# ----------------------------------------------------------------------------
# Organya (.org) -> Standard MIDI File
# ----------------------------------------------------------------------------
def write_vlq(val):
    out = [val & 0x7F]
    val >>= 7
    while val > 0:
        out.append((val & 0x7F) | 0x80)
        val >>= 7
    out.reverse()
    return bytes(out)


# GM drum key for each Organya drum sample number (0..)
GM_DRUM_WAVE_MAP = {
    0: 36,   # bass drum
    1: 38,   # snare
    2: 42,   # closed hi-hat
    3: 46,   # open hi-hat
    4: 45,   # low tom
    5: 56,   # cowbell
    6: 49,   # crash
    7: 41,   # low floor tom
    8: 39,   # hand clap
    9: 37,   # side stick
    10: 35,  # bass drum 2
    11: 47,  # low-mid tom
}


def classify_waves(blob):
    """WAVE100 = 100 single-cycle waveforms of 256 signed bytes.
    Return one label per wave: 'sine', 'tri', 'square', 'saw' or 'noise'."""
    kinds = []
    if not blob or len(blob) < 256 * 100:
        return ["square"] * 100
    for w in range(100):
        s = [(b - 256 if b > 127 else b) for b in blob[w * 256:(w + 1) * 256]]
        mags = []
        for k in range(1, 17):
            re = im = 0.0
            for n, v in enumerate(s):
                a = 2.0 * math.pi * k * n / 256.0
                re += v * math.cos(a)
                im += v * math.sin(a)
            mags.append(re * re + im * im)
        e = sum(mags)
        if e < 1.0:
            kinds.append("sine")
            continue
        fund = mags[0] / e
        odd = sum(mags[0::2]) / e
        cent = sum((k + 1) * m for k, m in enumerate(mags)) / e
        if fund > 0.90:
            kinds.append("sine")
        elif odd > 0.90 and cent < 3.0:
            kinds.append("tri")
        elif odd > 0.85:
            kinds.append("square")
        elif cent > 6.5:
            kinds.append("noise")
        else:
            kinds.append("saw")
    return kinds


# GM program numbers (0-based): (mid/high register, bass register)
GM_BY_KIND = {
    "sine":   (79, 38),   # ocarina / synth bass 1
    "tri":    (73, 39),   # flute   / synth bass 2
    "square": (80, 38),   # square lead / synth bass 1
    "saw":    (81, 38),   # saw lead / synth bass 1
    "noise":  (80, 38),
}


def org_bytes_to_midi(org_data, out_mid_path, wave_kinds=None):
    if len(org_data) < 114 or org_data[:6] not in (b"Org-02", b"Org-03"):
        return False
    wait, steps_per_bar, steps_per_beat, start_lp, end_lp = struct.unpack_from("<HBBII", org_data, 6)
    if wait <= 0:
        wait = 125

    pos = 18
    insts = []
    for _ in range(16):
        freq, wave, pipi, nnotes = struct.unpack_from("<HBBH", org_data, pos)
        insts.append((freq, wave, pipi, nnotes))
        pos += 6

    TICKS_PER_STEP = 12                       # division 48 ppqn = 4 steps per quarter note
    mpqn = min(0xFFFFFF, wait * 1000 * 4)

    tracks = []
    for ch in range(16):
        freq, wave, pipi, nnotes = insts[ch]
        if nnotes == 0:
            continue
        xs = list(struct.unpack_from("<%dI" % nnotes, org_data, pos)); pos += 4 * nnotes
        ys = list(struct.unpack_from("<%dB" % nnotes, org_data, pos)); pos += nnotes
        ls = list(struct.unpack_from("<%dB" % nnotes, org_data, pos)); pos += nnotes
        vs = list(struct.unpack_from("<%dB" % nnotes, org_data, pos)); pos += nnotes
        ps = list(struct.unpack_from("<%dB" % nnotes, org_data, pos)); pos += nnotes
        tracks.append((ch, wave, xs, ys, ls, vs, ps))

    # song length in ticks: Organya jumps from end_x back to start_x.  A MIDI file loops
    # from its very beginning, so cut the file at end_x to keep the loop length right.
    last = 0
    for ch, wave, xs, ys, ls, vs, ps in tracks:
        for i in range(len(xs)):
            last = max(last, (xs[i] + max(1, ls[i])) * TICKS_PER_STEP)
    stop = end_lp * TICKS_PER_STEP if end_lp > 0 else last
    if stop <= 0:
        stop = last
    stop = max(stop, TICKS_PER_STEP)

    events = []                                # (tick, order, bytes)
    events.append((0, 0, bytes([0xFF, 0x51, 0x03, (mpqn >> 16) & 0xFF, (mpqn >> 8) & 0xFF, mpqn & 0xFF])))
    events.append((0, 0, bytes([0xB9, 7, 110])))      # drum channel volume

    for ch, wave, xs, ys, ls, vs, ps in tracks:
        drum = ch >= 8
        midi_ch = 9 if drum else ch
        n = len(xs)

        if not drum:
            keys = [ys[i] for i in range(n) if ys[i] != 0xFF]
            avg_key = (sum(keys) / len(keys)) if keys else 48
            kind = wave_kinds[wave % len(wave_kinds)] if wave_kinds else "square"
            hi, lo = GM_BY_KIND.get(kind, GM_BY_KIND["square"])
            prog = lo if avg_key < 30 else hi
            events.append((0, 0, bytes([0xC0 | midi_ch, prog])))
            events.append((0, 0, bytes([0xB0 | midi_ch, 7, 100])))     # default Organya volume 200/254
            events.append((0, 0, bytes([0xB0 | midi_ch, 10, 64])))

        onsets = [i for i in range(n) if ys[i] != 0xFF]
        next_onset = {}
        for a, b in zip(onsets, onsets[1:]):
            next_onset[a] = xs[b] * TICKS_PER_STEP

        cur_vol = 200
        for i in range(n):
            tick = xs[i] * TICKS_PER_STEP
            if tick >= stop:
                break
            if vs[i] != 0xFF:
                cur_vol = vs[i]
                if not drum:
                    cc = max(1, min(127, int(cur_vol * 127 / 254)))
                    events.append((tick, 1, bytes([0xB0 | midi_ch, 7, cc])))
            if ps[i] != 0xFF and not drum:
                pan = max(0, min(127, int(ps[i] * 127 / 12)))
                events.append((tick, 1, bytes([0xB0 | midi_ch, 10, pan])))
            if ys[i] == 0xFF:
                continue

            if drum:
                note = GM_DRUM_WAVE_MAP.get(wave, 36 + (wave % 12))
                vel = max(20, min(127, int(cur_vol * 127 / 254)))
                end = tick + TICKS_PER_STEP
            else:
                note = max(12, min(108, ys[i] + 12))
                vel = 100
                end = tick + max(1, ls[i]) * TICKS_PER_STEP
                if i in next_onset:
                    end = min(end, next_onset[i])
            end = min(end, stop)
            if end <= tick:
                end = tick + 1
            events.append((tick, 3, bytes([0x90 | midi_ch, note, vel])))
            events.append((end, 2, bytes([0x80 | midi_ch, note, 0])))

    events.sort(key=lambda e: (e[0], e[1]))
    track_bytes = bytearray()
    last_t = 0
    for t, _, msg in events:
        track_bytes.extend(write_vlq(max(0, t - last_t)))
        track_bytes.extend(msg)
        last_t = t
    track_bytes.extend(write_vlq(max(0, stop - last_t)))
    track_bytes.extend(b"\xFF\x2F\x00")

    os.makedirs(os.path.dirname(out_mid_path) or ".", exist_ok=True)
    ensure_writable(out_mid_path)
    with open(out_mid_path, "wb") as f:
        f.write(b"MThd" + struct.pack(">IHHH", 6, 0, 1, 48))
        f.write(b"MTrk" + struct.pack(">I", len(track_bytes)))
        f.write(track_bytes)
    return True


# ----------------------------------------------------------------------------
# Doukutsu.exe: stage table, music table, embedded ORG + WAVE100 resources
# ----------------------------------------------------------------------------
def _cstr(raw):
    return raw.split(b"\x00")[0].decode("ascii", errors="ignore")


def extract_npc_rects(exe):
    """Sprite rectangles of every NPC type, read out of the ActNpcNNN functions in Doukutsu.exe.

    The original keeps them in code: each ActNpc function builds a local RECT array with
    'mov dword [ebp+disp], imm32' stores.  We scan the function (from its entry in the function
    pointer table to the next function) for runs of consecutive stores and group them in 4-dword RECTs.
    Output (npcrects.bin): per type u8 nLeft, u8 nRight, then (nLeft+nRight) rects of 4 x u16 (l,t,r,b).
    nRight == 0 means the type has no separate right-facing frames.
    """
    import bisect
    BASE = 0x400000
    N = 371

    def ptr_ok(v):
        return 0x401000 <= v < BASE + len(exe)

    table = 0x98548
    def table_valid(o):
        if o < 0 or o + 4 * N > len(exe):
            return False
        v = struct.unpack_from("<%dI" % N, exe, o)
        return sum(1 for x in v if ptr_ok(x)) >= 360
    if not table_valid(table):
        table = -1
        for o in range(0x90000, min(len(exe) - 4 * N, 0xB0000), 4):
            if table_valid(o):
                table = o
                break
    if table < 0:
        print("[WARN] NPC function table not found in exe - NPC sprites will be missing")
        return None
    ptrs = list(struct.unpack_from("<%dI" % N, exe, table))
    srt = sorted(set(p for p in ptrs if ptr_ok(p)))

    def stores(va):
        a = va - BASE
        i = bisect.bisect_right(srt, va)
        b = (srt[i] if i < len(srt) else va + 0x4000) - BASE
        code = exe[a:b]
        res = []
        k = 0
        while k < len(code) - 7:
            if code[k] == 0xC7 and code[k + 1] == 0x45:
                res.append((struct.unpack_from("<b", code, k + 2)[0], struct.unpack_from("<i", code, k + 3)[0]))
                k += 7
            elif code[k] == 0xC7 and code[k + 1] == 0x85 and k + 10 <= len(code):
                res.append((struct.unpack_from("<i", code, k + 2)[0], struct.unpack_from("<i", code, k + 6)[0]))
                k += 10
            else:
                k += 1
        return res

    def rect_runs(st):
        runs, cur = [], []
        for d, v in st:
            if cur and d == cur[-1][0] + 4:
                cur.append((d, v))
            else:
                if cur:
                    runs.append(cur)
                cur = [(d, v)]
        if cur:
            runs.append(cur)
        out = []
        for r in runs:
            vals = [v for _, v in r]
            rcs = []
            for q in range(len(vals) // 4):
                l, t, rr, b = vals[q * 4:q * 4 + 4]
                if l == t == rr == b == 0:
                    rcs.append((0, 0, 0, 0))                     # unused slot in the original array
                elif 0 <= l <= rr <= 1024 and 0 <= t <= b <= 1024 and rr - l <= 320 and b - t <= 320:
                    rcs.append((l, t, rr, b) if (rr > l and b > t) else (0, 0, 0, 0))
                else:
                    rcs = []
                    break
            if rcs:
                out.append(rcs)
        return out

    def is_pair(a, b):
        """b looks like the right-facing copy of a: same x / size, constant y offset (a few quirks allowed)"""
        if len(a) != len(b) or not a:
            return False
        dy = b[0][1] - a[0][1]
        if dy <= 0:
            return False
        ok = tot = 0
        for x, y in zip(a, b):
            if x == (0, 0, 0, 0) and y == (0, 0, 0, 0):
                continue
            tot += 1
            if x[0] == y[0] and x[2] == y[2] and y[1] - x[1] == dy and (x[3] - x[1]) == (y[3] - y[1]):
                ok += 1
        return tot > 0 and ok * 10 >= tot * 7

    out = bytearray()
    have = 0
    for t in range(361):
        va = ptrs[t]
        left, right = [], []
        if ptr_ok(va):
            runs = rect_runs(stores(va))
            if len(runs) >= 2 and is_pair(runs[0], runs[1]):
                left, right = runs[0], runs[1]
            elif runs:
                flat = [r for run in runs for r in run]
                half = len(flat) // 2
                if len(flat) % 2 == 0 and half and is_pair(flat[:half], flat[half:]):
                    left, right = flat[:half], flat[half:]
                else:
                    left = flat
        # tiny functions that compute their rectangle from the animation number (no RECT table)
        if not left and t == 76:
            left = [(0, 0, 16, 16)]
        # types whose rectangles the exe scan could not recover (taken from the original ActNpc code)
        if not left and t in (149, 157):
            left = [(16, 0, 48, 32)]                      # moving blocks
        if not left and t in (153, 154, 155):             # Gaudi: shared 'Kit' tables
            left = [(0, 0, 24, 24), (24, 0, 48, 24), (48, 0, 72, 24), (0, 0, 24, 24), (72, 0, 96, 24), (0, 0, 24, 24), (96, 0, 120, 24), (120, 0, 144, 24), (144, 0, 168, 24), (168, 0, 192, 24), (192, 0, 216, 24), (216, 0, 240, 24), (240, 0, 264, 24), (264, 0, 288, 24), (0, 48, 24, 72), (24, 48, 48, 72), (48, 48, 72, 72), (72, 48, 96, 72), (288, 0, 312, 24), (24, 48, 48, 72), (96, 48, 120, 72)]
            right = [(0, 24, 24, 48), (24, 24, 48, 48), (48, 24, 72, 48), (0, 24, 24, 48), (72, 24, 96, 48), (0, 24, 24, 48), (96, 24, 120, 48), (120, 24, 144, 48), (144, 24, 168, 48), (168, 24, 192, 48), (192, 24, 216, 48), (216, 24, 240, 48), (240, 24, 264, 48), (264, 24, 288, 48), (0, 72, 24, 96), (24, 72, 48, 96), (48, 72, 72, 96), (72, 72, 96, 96), (288, 24, 312, 48), (24, 72, 48, 96), (96, 72, 120, 96)]
        if len(left) > 40:
            left = left[:40]
        if len(right) > 40:
            right = right[:40]
        if left:
            have += 1
        out += struct.pack("<BB", len(left), len(right))
        for rc in left + right:
            out += struct.pack("<4H", *rc)
    print("Packed npcrects.bin (%d of 361 NPC types have sprite rectangles, %d bytes)" % (have, len(out)))
    return bytes(out)


def extract_stage_table(exe):
    """Return a list of 95 dicts, index == stage number used by <TRA.

    Layout of one 200 byte record (verified against Doukutsu.exe, table at 0x937B0):
        0   char tileset[32]     e.g. "Cave"  -> PrtCave.pbm (tiles) / Cave.pxa (tile attributes)
        32  char map[32]         e.g. "Start" -> Start.pxm / .pxe / .tsc
        64  int  bkType
        68  char back[32]        background bitmap
        100 char npc1[32]        NPC sheet 1  -> Npc<name>.pbm
        132 char npc2[32]        NPC sheet 2 / boss sheet
        164 u8   bossNo
        165 char name[35]        (Japanese) map name
    """
    marker = b"Pens1\x00"
    idx = exe.find(marker)
    while idx != -1:
        # "Pens1" is the map-name field of stage *1*, so stage 0 starts 232 bytes earlier
        base = idx - 32 - STAGE_REC
        if base >= 0 and base + NUM_STAGES * STAGE_REC <= len(exe):
            def rec(n):
                r = exe[base + n * STAGE_REC: base + (n + 1) * STAGE_REC]
                return {
                    "tileset": _cstr(r[0:32]),
                    "map": _cstr(r[32:64]),
                    "bk_type": struct.unpack_from("<i", r, 64)[0],
                    "bk_name": _cstr(r[68:100]),
                    "npc1": _cstr(r[100:132]),
                    "npc2": _cstr(r[132:164]),
                    "boss": r[164],
                }
            if rec(0)["map"] == "0" and rec(1)["map"] == "Pens1" and rec(13)["map"] == "Start":
                stages = [rec(n) for n in range(NUM_STAGES)]
                ok = all(s["map"] and all(32 <= ord(c) < 127 for c in s["map"] + s["tileset"]) for s in stages)
                if ok:
                    return stages
        idx = exe.find(marker, idx + 1)
    return []


def parse_pe_resources(exe):
    """Return ({name: bytes} for ORG resources, WAVE100 bytes or None)."""
    org_by_name = {}
    wave100 = None
    if len(exe) < 0x100 or exe[:2] != b"MZ":
        return org_by_name, wave100
    pe_off = struct.unpack_from("<I", exe, 0x3C)[0]
    if exe[pe_off:pe_off + 4] != b"PE\x00\x00":
        return org_by_name, wave100
    num_sec = struct.unpack_from("<H", exe, pe_off + 6)[0]
    opt_size = struct.unpack_from("<H", exe, pe_off + 20)[0]
    sec_off = pe_off + 24 + opt_size
    sections = []
    rsrc_raw = None
    for i in range(num_sec):
        s_off = sec_off + i * 40
        s_name = exe[s_off:s_off + 8].rstrip(b"\x00")
        vsize, vaddr, raw_size, raw_ptr = struct.unpack_from("<IIII", exe, s_off + 8)
        sections.append((s_name, vaddr, max(vsize, raw_size), raw_ptr))
        if s_name == b".rsrc":
            rsrc_raw = raw_ptr

    def rva_to_off(rva):
        for _, vaddr, span, raw_ptr in sections:
            if vaddr <= rva < vaddr + span:
                return raw_ptr + (rva - vaddr)
        return None

    if rsrc_raw is None:
        return org_by_name, wave100

    def parse_dir(dir_off):
        n_named, n_id = struct.unpack_from("<HH", exe, dir_off + 12)
        out = []
        for j in range(n_named + n_id):
            e_off = dir_off + 16 + j * 8
            name_id, data_off = struct.unpack_from("<II", exe, e_off)
            if name_id & 0x80000000:
                s_off = rsrc_raw + (name_id & 0x7FFFFFFF)
                s_len = struct.unpack_from("<H", exe, s_off)[0]
                key = exe[s_off + 2: s_off + 2 + s_len * 2].decode("utf-16le", errors="ignore")
            else:
                key = name_id
            is_dir = (data_off & 0x80000000) != 0
            out.append((key, is_dir, rsrc_raw + (data_off & 0x7FFFFFFF)))
        return out

    for type_key, is_dir, type_target in parse_dir(rsrc_raw):
        if not is_dir or str(type_key).upper() not in ("ORG", "WAVE"):
            continue
        for res_name, sub_is_dir, sub_target in parse_dir(type_target):
            if not sub_is_dir:
                continue
            lang_entries = parse_dir(sub_target)
            if not lang_entries or lang_entries[0][1]:
                continue
            data_rva, data_size = struct.unpack_from("<II", exe, lang_entries[0][2])
            f_off = rva_to_off(data_rva)
            if f_off is None:
                continue
            blob = exe[f_off: f_off + data_size]
            if blob[:6] in (b"Org-02", b"Org-03"):
                org_by_name[str(res_name).upper()] = blob
            elif str(res_name).upper() == "WAVE100":
                wave100 = blob
    return org_by_name, wave100


def extract_music_table(exe):
    """gMusicTable: 42 pointers to the ORG names, starts with XXXX, WANPAKU, ANZEN, GAMEOVER."""
    if len(exe) < 0x100 or exe[:2] != b"MZ":
        return []
    pe_off = struct.unpack_from("<I", exe, 0x3C)[0]
    num_sec = struct.unpack_from("<H", exe, pe_off + 6)[0]
    opt_size = struct.unpack_from("<H", exe, pe_off + 20)[0]
    image_base = struct.unpack_from("<I", exe, pe_off + 52)[0]
    sec_off = pe_off + 24 + opt_size
    sections = []
    for i in range(num_sec):
        s_off = sec_off + i * 40
        vsize, vaddr, raw_size, raw_ptr = struct.unpack_from("<IIII", exe, s_off + 8)
        sections.append((vaddr, max(vsize, raw_size), raw_ptr))

    def va_to_off(va):
        if va < image_base:
            return None
        rva = va - image_base
        for vaddr, span, raw_ptr in sections:
            if vaddr <= rva < vaddr + span:
                return raw_ptr + (rva - vaddr)
        return None

    def read_cstr(off):
        if off is None or off < 0 or off >= len(exe):
            return None
        end = exe.find(b"\x00", off, off + 32)
        return None if end == -1 else exe[off:end].decode("ascii", errors="ignore")

    for vaddr, span, raw_ptr in sections:
        end_scan = min(len(exe) - 42 * 4, raw_ptr + span)
        for off in range(raw_ptr, end_scan, 4):
            p0, p1, p2, p3 = struct.unpack_from("<IIII", exe, off)
            if (read_cstr(va_to_off(p0)) == "XXXX" and read_cstr(va_to_off(p1)) == "WANPAKU" and
                    read_cstr(va_to_off(p2)) == "ANZEN" and read_cstr(va_to_off(p3)) == "GAMEOVER"):
                table = []
                for k in range(42):
                    pk = struct.unpack_from("<I", exe, off + k * 4)[0]
                    nm = read_cstr(va_to_off(pk))
                    table.append(nm.upper() if nm else "XXXX")
                return table
    return []


# Verified against the exe (index == <CMU number)
FALLBACK_MUSIC_TABLE = [
    "XXXX", "WANPAKU", "ANZEN", "GAMEOVER", "GRAVITY", "WEED", "MDOWN2", "FIREEYE",
    "VIVI", "MURA", "FANFALE1", "GINSUKE", "CEMETERY", "PLANT", "KODOU", "FANFALE3",
    "FANFALE2", "DR", "ESCAPE", "JENKA", "MAZE", "ACCESS", "IRONH", "GRAND",
    "CURLY", "OSIDE", "REQUIEM", "WANPAK2", "QUIET", "LASTCAVE", "BALCONY", "LASTBTL",
    "LASTBT3", "ENDING", "ZONBIE", "BDOWN", "HELL", "JENKA2", "MARINE", "BALLOS",
    "TOROKO", "WHITE",
]


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------
def main():
    raw_dir = find_raw_dir()
    if not raw_dir:
        print("[ERROR] Could not find Cave Story data folder in raw_data\\")
        sys.exit(1)
    print("Using data folder:", raw_dir)

    os.makedirs(OUT_DIR, exist_ok=True)

    # 1. images ---------------------------------------------------------------------------
    pbm_count = 0
    for root, _, files in os.walk(raw_dir):
        for fn in files:
            if fn.lower().endswith((".pbm", ".bmp")):
                convert_pbm_file(os.path.join(root, fn),
                                 os.path.join(OUT_DIR, os.path.splitext(fn)[0] + ".png"), colors=64)
                pbm_count += 1
    print("Converted %d spritesheets/tilesets to 64-colour rotated PNGs." % pbm_count)

    # 2. stage binaries ---------------------------------------------------------------------
    stage_dir = find_subdir(raw_dir, "Stage")
    if not stage_dir:
        print("[ERROR] no Stage folder inside", raw_dir)
        sys.exit(1)
    for fn in os.listdir(stage_dir):
        if fn.lower().endswith((".pxm", ".pxe", ".pxa")):
            base, ext = os.path.splitext(fn)
            safe_copy(os.path.join(stage_dir, fn), os.path.join(OUT_DIR, base + ext.lower()))

    # 3. TSC --------------------------------------------------------------------------------
    tsc_count = 0
    for root, _, files in os.walk(raw_dir):
        for fn in files:
            if fn.lower().endswith(".tsc"):
                if convert_tsc_to_utf8(os.path.join(root, fn),
                                       os.path.join(OUT_DIR, os.path.splitext(fn)[0] + ".tsc")) is not False:
                    tsc_count += 1
    print("Decrypted %d TSC scripts to UTF-8." % tsc_count)

    # 4. npc.tbl -> npctbl.bin ----------------------------------------------------------------
    #    u16 count, then per NPC (15 bytes):
    #      u16 bits, u8 surf, u8 damage, u16 life, u8 exp,
    #      u8 hit  front/top/back/bottom,   u8 view front/top/back/bottom   (pixels, from the entity centre)
    npc_tbl_path = os.path.join(raw_dir, "npc.tbl")
    if os.path.exists(npc_tbl_path):
        with open(npc_tbl_path, "rb") as f:
            tbl = f.read()
        n = len(tbl) // 24
        bits = struct.unpack_from("<%dH" % n, tbl, 0)
        life = struct.unpack_from("<%dH" % n, tbl, n * 2)
        surf = struct.unpack_from("<%dB" % n, tbl, n * 4)
        exp = struct.unpack_from("<%dI" % n, tbl, n * 8)
        dmg = struct.unpack_from("<%dI" % n, tbl, n * 12)
        hit = tbl[n * 16: n * 20]
        view = tbl[n * 20: n * 24]
        out = bytearray(struct.pack("<H", n))
        for i in range(n):
            out += struct.pack("<HBBHB", bits[i], surf[i], min(255, dmg[i]), life[i], min(255, exp[i]))
            out += hit[i * 4: i * 4 + 4]
            out += view[i * 4: i * 4 + 4]
        ensure_writable(os.path.join(OUT_DIR, "npctbl.bin"))
        with open(os.path.join(OUT_DIR, "npctbl.bin"), "wb") as f:
            f.write(out)
        print("Packed npctbl.bin (%d NPC definitions)" % n)

        # 4a. npcvoice.bin: u16 count, then per NPC  u8 hit_voice, u8 destroy_voice  (PixTone sound ids)
        voice = bytearray(struct.pack("<H", n))
        for i in range(n):
            voice.append(tbl[n * 5 + i])
            voice.append(tbl[n * 6 + i])
        ensure_writable(os.path.join(OUT_DIR, "npcvoice.bin"))
        with open(os.path.join(OUT_DIR, "npcvoice.bin"), "wb") as f:
            f.write(voice)
        print("Packed npcvoice.bin (hit / destroy sounds)")
    else:
        print("[WARN] npc.tbl not found - enemies will not work")

    # 4b. HUD strip: crop the bits of TextBox the in-game HUD needs (digits, Lv, exp bar, HP case, bars)
    #     so the phone does not have to keep the whole 244x144 sheet in memory.
    tb_path = os.path.join(OUT_DIR, "TextBox.png")
    if os.path.exists(tb_path):
        try:
            tb = Image.open(tb_path).convert("RGBA").transpose(Image.ROTATE_90)   # back to original orientation
            hud = tb.crop((0, 24, 244, 104)).transpose(Image.ROTATE_270)         # 244x80 strip, stored rotated like the others
            ensure_writable(os.path.join(OUT_DIR, "Hud.png"))
            hud.save(os.path.join(OUT_DIR, "Hud.png"))
            print("Wrote Hud.png")
        except Exception as e:
            print("[WARN] could not build Hud.png:", e)

    # 4c. weapon bullet sprite atlases
    try:
        build_bullet_atlases(raw_dir, OUT_DIR)
    except Exception as e:
        print("[WARN] could not build bullet atlases:", e)

    # 4d. sound effects (x<N>.wav)
    try:
        build_sfx(OUT_DIR)
    except Exception as e:
        print("[WARN] could not build sound effects:", e)

    # 5. exe: stage table, music, waves ---------------------------------------------------------
    exe_path = find_exe(raw_dir)
    exe = b""
    if exe_path:
        with open(exe_path, "rb") as f:
            exe = f.read()
        print("Using", exe_path)
    else:
        print("[WARN] Doukutsu.exe not found - put it next to raw_data\\ (stage table + music need it)")

    if exe:
        rects = extract_npc_rects(exe)
        if rects:
            ensure_writable(os.path.join(OUT_DIR, "npcrects.bin"))
            with open(os.path.join(OUT_DIR, "npcrects.bin"), "wb") as f:
                f.write(rects)

    stages = extract_stage_table(exe) if exe else []
    if stages:
        out = bytearray(struct.pack("<H", len(stages)))
        for s in stages:
            ts = "Prt" + s["tileset"]
            n1 = "Npc" + s["npc1"]
            n2 = "Npc" + s["npc2"]
            bk = s["bk_name"]
            # map | tileset png | tile attribute (pxa) | npc sheet 1 | npc sheet 2 | bkType | bk image
            line = ("%s|%s|%s|%s|%s|%d|%s\n" % (s["map"], ts, s["tileset"], n1, n2, s["bk_type"], bk)).encode("ascii")
            out += struct.pack("<B", len(line)) + line
        ensure_writable(os.path.join(OUT_DIR, "stages.bin"))
        with open(os.path.join(OUT_DIR, "stages.bin"), "wb") as f:
            f.write(out)
        print("Packed stages.bin (%d stages; stage 13 = %s)" % (len(stages), stages[13]["map"]))
        # Windows file names are case-insensitive, jar resources are not.  The stage table mixes
        # spellings ("BkGreen"/"bkGreen"), so make sure a file with the exact spelling exists.
        existing = {f.lower(): f for f in os.listdir(OUT_DIR)}
        for s in stages:
            wanted = [s["map"] + ".pxm", s["map"] + ".pxe", s["map"] + ".tsc",
                      "Prt" + s["tileset"] + ".png", s["tileset"] + ".pxa",
                      "Npc" + s["npc1"] + ".png", "Npc" + s["npc2"] + ".png", s["bk_name"] + ".png"]
            for w in wanted:
                if w.lower() in existing and not os.path.exists(os.path.join(OUT_DIR, w)):
                    shutil.copyfile(os.path.join(OUT_DIR, existing[w.lower()]), os.path.join(OUT_DIR, w))
        missing = []
        for i, s in enumerate(stages):
            for need in (s["map"] + ".pxm", s["map"] + ".pxe", s["tileset"] + ".pxa"):
                if s["map"] != "0" and not os.path.exists(os.path.join(OUT_DIR, need)):
                    missing.append((i, need))
        if missing:
            print("[WARN] %d stage files referenced by the stage table are missing in res\\, e.g. %s"
                  % (len(missing), missing[:4]))
    else:
        print("[ERROR] could not locate the stage table in the exe - stages.bin NOT written")

    # 6. music -----------------------------------------------------------------------------------
    org_by_name, wave100 = parse_pe_resources(exe) if exe else ({}, None)
    music_table = extract_music_table(exe) if exe else []
    if len(music_table) != 42:
        music_table = list(FALLBACK_MUSIC_TABLE)
    wave_kinds = classify_waves(wave100)

    for old in os.listdir(OUT_DIR):
        if old.startswith("m") and old.endswith(".mid") and len(old) == 7:
            p = os.path.join(OUT_DIR, old)
            ensure_writable(p)
            os.remove(p)

    org_dir = find_subdir(raw_dir, "Org")
    org_files = {}
    if org_dir:
        for fn in os.listdir(org_dir):
            if fn.lower().endswith(".org"):
                org_files[os.path.splitext(fn)[0].upper()] = os.path.join(org_dir, fn)

    mid_count = 0
    for cmu_id, res_name in enumerate(music_table):
        if cmu_id == 0 or res_name == "XXXX":
            continue
        out_mid = os.path.join(OUT_DIR, "m%02d.mid" % cmu_id)
        data = None
        if res_name in org_files:
            with open(org_files[res_name], "rb") as f:
                data = f.read()
        elif res_name in org_by_name:
            data = org_by_name[res_name]
        if data and org_bytes_to_midi(data, out_mid, wave_kinds):
            mid_count += 1
        else:
            print("[WARN] no ORG data for track %d (%s)" % (cmu_id, res_name))
    print("Converted %d Organya tracks to MIDI in res\\ (m01.mid ... m41.mid, numbers = <CMU values)" % mid_count)


if __name__ == "__main__":
    main()

# CHANGES vs v1
# -------------
# * extract_stage_table(): v1 took the address of the "Pens1" string minus 32 as the start of the table.
#   "Pens1" is the map field of stage 1, not stage 0, so every stage index was off by one
#   (stage 13 "Start" became stage 14 "Barr", which is full of enemies).
# * npctbl.bin dropped the per-NPC flag word and used hit/view *sizes* only; now keeps the rectangles.
# * org_bytes_to_midi(): loop point text event removed (MIDP ignores it), file now ends exactly at the
#   Organya loop end, per-note volume/pan handled, previous note is cut when the next one starts,
#   instruments chosen by analysing the real WAVE100 waveforms instead of wave % 30.
