@echo off
cd /d "%~dp0"
echo Compiling First Choice...
if not exist out mkdir out
javac -cp "lib\*" -sourcepath src -d out src\com\firstchoice\busreservation\web\WebApp.java
if errorlevel 1 (
  echo.
  echo Compile failed. Make sure the JDK is installed: run  javac -version
  pause
  exit /b 1
)
REM Local convenience only: a known admin password for the FIRST start on your own computer.
REM When you host the site, set your own ADMIN_PASSWORD (see README) and never use this one.
if "%ADMIN_PASSWORD%"=="" set ADMIN_PASSWORD=Admin@12345
echo.
echo ==============================================================
echo  Website:  http://localhost:8080
echo  Admin login (local only):  admin@firstchoice.local  /  %ADMIN_PASSWORD%
echo  Your data is saved in the "data" folder next to this file.
echo ==============================================================
echo.
start "" cmd /c "timeout /t 4 /nobreak >nul & start http://localhost:8080"
java -cp "out;lib\*" com.firstchoice.busreservation.web.WebApp
pause
