@echo off
cd /d "%~dp0"
for /f "usebackq tokens=1,* delims==" %%a in (".env") do set %%a=%%b
for /f %%i in ('powershell -command "Get-Date -Format yyyy-MM-dd_HH-mm-ss"') do set FECHA=%%i
set DESTINO=.\resguardos_tpi\%FECHA%
mkdir "%DESTINO%"
mongodump --uri="%MONGO_URI%" --db="%DB_NAME%" --out="%DESTINO%"
if errorlevel 1 (
    echo ERROR: el backup fallo. Revise el mensaje de arriba.
    rmdir "%DESTINO%" 2>nul
    pause
    exit /b 1
)
echo Backup completado en %DESTINO%
pause
