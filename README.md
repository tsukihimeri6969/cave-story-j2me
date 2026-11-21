## What this is

A port of the Japanese version of Cave Story (Doukutsu Monogatari) to J2ME phones with a 240x320 screen. It was written for the Nokia 6300 but should run on any MIDP 2.0 / CLDC 1.1 phone with a similar screen, and on emulators.

The game runs on a custom Java engine. Levels, graphics, music and enemy data are all read from your own copy of the game, so this repository contains no Cave Story assets.

The music is converted from Organya to MIDI. Sound effects are generated from the same PixTone parameters the original game uses. Many enemies use AI translated from the open source CSE2 engine.

## Requirements

You need to bring your own copy of Cave Story (the Japanese freeware release, `Doukutsu.exe` plus its `data` folder). This project was developed and tested against version 1.0.0.6. Other versions may work, but I have not checked.

### Software

| What | Version | Notes |
| --- | --- | --- |
| Windows | 10 or newer | The build script is a batch file |
| JDK | 8 (tested with 1.8.0_202) | Newer JDKs cannot target the old class format that J2ME needs |
| Sun Java Wireless Toolkit (WTK) | 2.5.2 | Provides `preverify.exe` and the CLDC/MIDP libraries |
| Python | 3.6 or newer | Only used to pack the game data |
| Pillow | any recent version | `pip install pillow` |

The packer script only uses Pillow and the Python standard library, so there is nothing else to install.

The WTK libraries used by the build are `cldcapi11.jar`, `midpapi20.jar`, `jsr75.jar` and `mmapi.jar`. They all come with WTK 2.5.2.

## Project layout

```
cave-story-j2me/
  build.bat          build script
  manifest.mf        jar manifest (UTF-8, holds the title and icon)
  app.jad            jad template (UTF-8)
  src/               Java sources
  tools/
    prep_assets.py   packs the game data into res/
  raw_data/          your Cave Story "data" folder (not included)
  Doukutsu.exe       from your copy of the game (not included)
  res/               generated, plus icon.png
```

Put `Doukutsu.exe` in the project folder next to `raw_data`. The packer reads the stage table, the music and the sprite rectangles straight out of the exe.

## Building

1. Install JDK 8 and WTK 2.5.2, and install Pillow with `pip install pillow`.
2. Open `build.bat` and check the two paths at the top:
   ```
   set "WTK_HOME=C:\WTK2.5.2_01"
   set "JDK8_HOME=C:\Program Files\Java\jdk1.8.0_202"
   ```
   Change them if you installed things somewhere else. The script also looks for a JDK 8 in the usual Program Files folders if the path above is wrong.
3. Copy your Cave Story `data` folder into the project as `raw_data`, and put `Doukutsu.exe` next to it.
4. Make sure `res/icon.png` exists. The repository ships one.
5. Run `build.bat`.

On the first run the script notices that the sound effects are missing from `res/` and runs `tools/prep_assets.py` for you. This takes a little while because it converts every sprite sheet, tileset and song. After that it compiles the sources, preverifies the classes, and writes the jar and jad.

When it finishes you will find these in the `build` folder:

```
build/cs6300.jar
build/cs6300.jad
```

If you change the game data or the packer, delete `res/x17.wav` (or run `py -3 tools\prep_assets.py` yourself) to force the resources to be rebuilt.

### Running the packer by hand

```
python tools\prep_assets.py
```

Run it from the project folder, the one that contains `raw_data`. The output goes to `res/`.

## Installing

Copy `cs6300.jar` and `cs6300.jad` to the phone over Bluetooth, a cable or a memory card, then open the jar to install it. The jar is about 1 MB.


## Controls

The d-pad is rotated to match the sideways screen.

| Key | Action |
| --- | --- |
| d-pad up / down | move left / right |
| d-pad right | look up |
| d-pad left | look down, inspect |
| 5 or the centre key | jump |
| 7 or 9 | shoot |
| 0 | inspect or talk |
| 1 or 3 | next weapon |
| # or left soft key | inventory menu |
| * | sound effects on / off |

## Status

most boss fights still use simplified behaviour so a proper port of the boss code (Ballos, Ohm, the Core, the Press and the rest) is on the agenda

## Credits

Cave Story was made by Daisuke "Pixel" Amaya. This is an unofficial fan port and is not affiliated with him or Studio Pixel.

Ported by TsuuGW.

Enemy behaviour and the PixTone synthesiser are based on the open source CSE2 engine by Clownacy and contributors. Their code was used as a reference and translated to Java.

Please support the official releases of Cave Story.
