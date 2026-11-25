@echo off
rem Builds extractor\dist\AWC-Tool.exe (single file, no console window).
rem Copy ffmpeg.exe and ffplay.exe next to the exe afterwards (needed for playback and FLAC/WAV/MP3).
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller pillow
python -m PyInstaller --noconfirm --onefile --windowed --name AWC-Tool --add-data "%~dp0logos;logos" --paths "%~dp0." --paths "%~dp0..\radio" awc_tool.py
echo.
echo Done: extractor\dist\AWC-Tool.exe
