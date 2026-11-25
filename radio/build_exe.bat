@echo off
rem Builds radio\dist\AWC-Radio.exe (single file, no console window). It uses the shared modules in ..\extractor.
rem Copy ffplay.exe next to the exe afterwards (needed for playback; ffmpeg.exe is used for Android packs if present).
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller pillow
python -m PyInstaller --noconfirm --onefile --windowed --name AWC-Radio --add-data "%~dp0..\extractor\logos;logos" --paths "%~dp0." --paths "%~dp0..\extractor" awc_radio.py
echo.
echo Done: radio\dist\AWC-Radio.exe
rem The Android app is built separately: python android\build_apk.py (needs the Android SDK and a JDK)
