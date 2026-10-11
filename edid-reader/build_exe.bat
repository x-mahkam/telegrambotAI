@echo off
REM MonitorCheck EDID o'quvchini bitta .exe faylga yig'ish (Windows).
REM Talab: Python 3.10+ (python.org), PATH'da bo'lsin.
REM Natija: dist\MonitorCheck-EDID.exe
cd /d "%~dp0"
python -m venv .venv || goto :err
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt pyinstaller || goto :err
python -m pytest -q 2>nul
pyinstaller --onefile --console --name MonitorCheck-EDID ^
  --collect-submodules qrcode ^
  --exclude-module tkinter --exclude-module numpy --exclude-module PIL ^
  run_edid_reader.py || goto :err
echo.
echo Tayyor: %~dp0dist\MonitorCheck-EDID.exe
pause
exit /b 0
:err
echo Xatolik yuz berdi.
pause
exit /b 1
