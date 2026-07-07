@echo off
echo ============================================
echo  NFC-Server  -  EXE bauen mit PyInstaller
echo ============================================
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo FEHLER: Python nicht gefunden. Bitte Python installieren.
    pause
    exit /b 1
)

echo [1/2] Installiere PyInstaller...
pip install pyinstaller --quiet

echo [2/2] Baue nfc_server.exe...
pyinstaller --onefile --windowed --name "NFC-Server" --clean nfc_server.py

echo.
echo Fertig! Die Datei liegt in:  dist\NFC-Server.exe
echo.
pause
