@echo off
rem HCIM Guide Bot updater. Keep this one file anywhere (the desktop is fine) and double click it.
rem It downloads the latest version of the bot from GitHub into a clean folder, then builds and
rem installs it with build-and-install.bat. Microbot can stay open: the bot reloads itself.
title HCIM Guide Bot update

set "URL=https://github.com/Dendabad/Marvel_Rivals/archive/refs/heads/claude/osrs-script-creation-w5mkvb.zip"
set "WORK=%USERPROFILE%\.runelite\hcim-guide-bot\source"
set "ZIP=%TEMP%\hcim-guide-bot-latest.zip"

echo Downloading the latest version from GitHub...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%ZIP%'"
if errorlevel 1 (
    echo.
    echo The download failed. Check your internet connection and try again.
    pause
    exit /b 1
)

rem A clean folder every time, so files the project removed can never be left behind.
if exist "%WORK%" rmdir /s /q "%WORK%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%WORK%' -Force"
if errorlevel 1 (
    echo.
    echo Could not unpack the download.
    pause
    exit /b 1
)
del "%ZIP%" >nul 2>&1

set "SRC="
for /d %%D in ("%WORK%\*") do set "SRC=%%~fD"
if not defined SRC (
    echo The download did not contain the project.
    pause
    exit /b 1
)

echo Downloaded. Building and installing...
echo.
call "%SRC%\build-and-install.bat"
