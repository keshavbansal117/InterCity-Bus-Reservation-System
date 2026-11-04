@echo off
cd /d "%~dp0"
echo Compiling First Choice...
if not exist out mkdir out
javac -sourcepath src -d out src\com\firstchoice\busreservation\web\WebApp.java
if errorlevel 1 (
  echo.
  echo Compile failed. Make sure the JDK is installed: run  javac -version
  pause
  exit /b 1
)
echo.
echo Starting the website... it opens in your browser in a few seconds.
start "" cmd /c "timeout /t 3 /nobreak >nul & start http://localhost:8080"
java -cp out com.firstchoice.busreservation.web.WebApp
pause
