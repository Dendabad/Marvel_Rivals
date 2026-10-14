@echo off
rem HCIM Guide Bot watcher. Keep this window open while you play. Every minute it checks GitHub for a new version of the
rem bot, and when there is one it asks you before it updates (press Y), or updates by itself if started as:
rem     watch-bot.bat auto
rem The update is the same as update-bot.bat: it downloads, builds, and only on a good build replaces the installed bot,
rem which reloads itself while the game runs. Then press Retry or Resume in the bot's sidebar.
title HCIM Guide Bot watcher
setlocal enabledelayedexpansion

set "BRANCH=claude/osrs-script-creation-w5mkvb"
set "API=https://api.github.com/repos/Dendabad/Marvel_Rivals/commits/%BRANCH%"
set "KNOWN=%USERPROFILE%\.runelite\hcim-guide-bot\installed-commit.txt"
set "HERE=%~dp0"
set "MODE=ask"
if /i "%~1"=="auto" set "MODE=auto"
set "HCIM_NOPAUSE=1"

echo Watching %BRANCH% for new versions. Mode: %MODE%. Close this window to stop.
echo.

:loop
set "LATEST="
for /f "usebackq delims=" %%S in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (Invoke-RestMethod -UseBasicParsing -Uri '%API%' -Headers @{ 'User-Agent' = 'hcim-bot-watcher' }).sha } catch { '' }"`) do set "LATEST=%%S"
if not defined LATEST goto wait

set "INSTALLED="
if exist "%KNOWN%" set /p INSTALLED=<"%KNOWN%"
if /i "!LATEST!"=="!INSTALLED!" goto wait

rem The first run on a machine only records where things are, so it does not rebuild for nothing.
if not defined INSTALLED (
    >"%KNOWN%" echo !LATEST!
    echo Recorded the current version. Waiting for a newer one.
    goto wait
)

echo A new version is on GitHub: !LATEST:~0,7!
if /i "%MODE%"=="ask" (
    choice /c YN /t 120 /d N /m "Update now"
    if errorlevel 2 (
        echo Not updating. It will ask again for the next new version.
        >"%KNOWN%" echo !LATEST!
        goto wait
    )
)
echo Updating...
call "%HERE%update-bot.bat"
if errorlevel 1 (
    echo The update failed, so the installed bot was left as it was. Trying again at the next new version.
) else (
    echo Updated. Press Retry or Resume in the bot's sidebar.
)
>"%KNOWN%" echo !LATEST!
echo.

:wait
timeout /t 60 /nobreak >nul
goto loop
