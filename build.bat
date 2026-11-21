@echo off
setlocal enabledelayedexpansion

rem ---- paths: adjust if your install differs ---------------------------------
set "WTK_HOME=H:\WTK2.5.2"
set "JDK8_HOME=C:\Program Files\Java\jdk1.8.0_202"
rem -----------------------------------------------------------------------------

cd /d "%~dp0"

if not exist "%JDK8_HOME%\bin\javac.exe" (
    for /d %%D in ("C:\Program Files\Java\jdk1.8*" "C:\Program Files (x86)\Java\jdk1.8*") do set "JDK8_HOME=%%~fD"
)
if not exist "%JDK8_HOME%\bin\javac.exe" (
    echo [ERROR] Could not find JDK 8 javac.exe. Set JDK8_HOME in build.bat!
    exit /b 1
)
if not exist "%WTK_HOME%\bin\preverify.exe" (
    echo [ERROR] Could not find preverify.exe. Set WTK_HOME in build.bat!
    exit /b 1
)

set "PATH=%JDK8_HOME%\bin;%PATH%"
set "BOOTCP=%WTK_HOME%\lib\cldcapi11.jar;%WTK_HOME%\lib\midpapi20.jar;%WTK_HOME%\lib\jsr75.jar;%WTK_HOME%\lib\mmapi.jar"

rem ---- 1. resources: (re)pack from Doukutsu.exe + raw_data when they are missing/outdated ----
rem      x17.wav (sound effects) is new in this version, so an old res folder triggers a re-pack.
if not exist res mkdir res
if not exist res\x17.wav (
    if exist tools\prep_assets.py (
        echo Packing resources from Doukutsu.exe ...
        where py >nul 2>nul && (py -3 tools\prep_assets.py) || (python tools\prep_assets.py)
        if errorlevel 1 (
            echo [ERROR] prep_assets.py failed - see the messages above.
            exit /b 1
        )
    ) else (
        echo [ERROR] res\x17.wav is missing and tools\prep_assets.py was not found.
        exit /b 1
    )
)

rem ---- 2. compile every source file in src\ (new files such as Sfx.java are picked up automatically) ----
if exist build rmdir /s /q build
mkdir build\classes
mkdir build\preverified

if not exist src\*.java (
    echo [ERROR] No .java files found in "%CD%\src"
    echo         build.bat must sit in the project folder that contains the src folder.
    echo         Current folder: %CD%
    dir /b
    exit /b 1
)

rem the list of sources goes into an argument file (no delayed-expansion tricks, paths stay short)
if exist build\sources.txt del build\sources.txt
for %%F in (src\*.java) do echo src\%%~nxF>>build\sources.txt
echo Compiling:
type build\sources.txt

"%JDK8_HOME%\bin\javac.exe" -Xlint:-options -encoding UTF-8 -source 1.3 -target 1.3 -bootclasspath "%BOOTCP%" -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1

rem ---- 3. preverify ----
"%WTK_HOME%\bin\preverify.exe" -classpath "%BOOTCP%" -d build\preverified build\classes
if errorlevel 1 exit /b 1

rem ---- 4. jar + jad ----
rem manifest.mf / app.jad are UTF-8 files (they hold the Japanese title); cmd's echo would mangle them
if not exist manifest.mf ( echo [ERROR] manifest.mf is missing & exit /b 1 )
if not exist app.jad ( echo [ERROR] app.jad is missing & exit /b 1 )
if not exist res\icon.png ( echo [WARN] res\icon.png is missing - the app will have no icon )

"%JDK8_HOME%\bin\jar.exe" cfm build\cs6300.jar manifest.mf -C build\preverified . -C res .
if errorlevel 1 exit /b 1

for %%I in (build\cs6300.jar) do set JAR_SIZE=%%~zI

copy /y app.jad build\cs6300.jad >nul
echo MIDlet-Jar-Size: !JAR_SIZE!>>build\cs6300.jad

echo.
echo Built build\cs6300.jar (!JAR_SIZE! bytes) - Ready for Nokia 6300!
endlocal
