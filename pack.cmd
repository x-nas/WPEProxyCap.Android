@echo off
rem One-click release APK for WPC Android : runs tools\pack\Pack.ps1 (bypassing execution policy)
rem Extra arguments are passed through, e.g.  pack.cmd -SkipWeb -SkipTests
rem   -SkipWeb    do not rebuild / re-sync the shared front end from ..\WPEProxyCap.Web
rem   -SkipTests  do not run the JVM unit tests before building
rem NOTE: never put angle brackets, pipes or ampersands in echo text below - cmd treats them as redirection
setlocal
cd /d "%~dp0"
title WPC Android - pack
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\pack\Pack.ps1" %*
if errorlevel 1 (
    echo.
    echo ************  PACK FAILED  -  see the red messages above  ************
    echo.
    pause
    exit /b 1
)
echo.
echo ************  PACK OK  -  output: dist\WPC Android vX.Y.apk  ************
echo.
if "%WPC_PACK_NOOPEN%"=="" if exist "%~dp0dist" start "" "%~dp0dist"
pause
