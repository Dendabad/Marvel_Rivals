@echo off
rem Builds the HCIM Guide Bot and installs it. Double click this file.
rem Microbot can stay open: the bot reloads itself a few seconds after this finishes.
rem Only when the small loader plugin itself has changed does Microbot need closing first.
cd /d "%~dp0"
title HCIM Guide Bot build

rem Unzipping a new download over this folder never deletes files the project removed,
rem and a stale one breaks the build. These are every file ever removed.
for %%R in ("microbot-plugin\src\main\kotlin\com\hcimbot\microbot\HcimGuideBotPlugin.kt" "microbot-plugin\src\main\kotlin\com\hcimbot\microbot\FileRouteStore.kt" "src\main\kotlin\com\hcimbot\route\Routes.kt" "src\main\kotlin\com\hcimbot\sdk\BotClientActions.kt" "src\test\kotlin\com\hcimbot\RouteTest.kt") do if exist %%R (
    del %%R
    echo Removed an old file the project no longer has: %%~R
)

set "MICROBOT_JAR="
for %%F in ("%USERPROFILE%\.microbot\microbot-*.jar") do set "MICROBOT_JAR=%%~fF"
if not defined MICROBOT_JAR (
    echo Could not find the Microbot jar in %USERPROFILE%\.microbot
    echo Run the Microbot Launcher once so it downloads Microbot, then try again.
    if not defined HCIM_NOPAUSE pause
    exit /b 1
)
echo Building against %MICROBOT_JAR%
echo.

call gradlew.bat :microbot-plugin:pluginJar -PmicrobotJar="%MICROBOT_JAR%"
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Send a screenshot of the first red lines that start with e:
    if not defined HCIM_NOPAUSE pause
    exit /b 1
)

set "LIBS=microbot-plugin\build\libs"
set "PLUGINS=%USERPROFILE%\.runelite\microbot-plugins"
set "HOME_DIR=%USERPROFILE%\.runelite\hcim-guide-bot"
if not exist "%PLUGINS%" mkdir "%PLUGINS%"
if not exist "%HOME_DIR%" mkdir "%HOME_DIR%"

rem The bot itself. Copied under another name, then renamed, so it is never read half written.
copy /y "%LIBS%\hcim-guide-bot-core.jar" "%HOME_DIR%\hcim-guide-bot-core.jar.new" >nul
if errorlevel 1 (
    echo Could not copy the bot to %HOME_DIR%
    if not defined HCIM_NOPAUSE pause
    exit /b 1
)
move /y "%HOME_DIR%\hcim-guide-bot-core.jar.new" "%HOME_DIR%\hcim-guide-bot-core.jar" >nul
echo The bot is installed. If Microbot is running, it reloads itself in a few seconds.

rem The loader plugin. Only replaced when it changed, since Microbot keeps it open.
set "LOADER_CHANGED=1"
if exist "%PLUGINS%\HcimGuideBotPlugin.jar" (
    fc /b "%LIBS%\HcimGuideBotPlugin.jar" "%PLUGINS%\HcimGuideBotPlugin.jar" >nul 2>&1 && set "LOADER_CHANGED="
)
if defined LOADER_CHANGED (
    copy /y "%LIBS%\HcimGuideBotPlugin.jar" "%PLUGINS%\HcimGuideBotPlugin.jar" >nul 2>&1
    if errorlevel 1 (
        echo.
        echo The loader plugin changed too, and Microbot has the old one open.
        echo Close Microbot, then run this again. This is only needed when the loader changes.
        if not defined HCIM_NOPAUSE pause
        exit /b 1
    )
    echo The loader plugin was updated. Start Microbot, or restart it if it was open.
) else (
    rem Microbot deletes unknown plugin jars once they are 3 days old, so keep its date fresh.
    pushd "%PLUGINS%"
    copy /b HcimGuideBotPlugin.jar +,, >nul 2>&1
    popd
    echo The loader plugin is unchanged, so Microbot can stay open.
)

echo.
echo These are the jars in %PLUGINS%. Only HcimGuideBotPlugin.jar should be the bot:
dir /b "%PLUGINS%\*.jar"
echo.
if not defined HCIM_NOPAUSE pause
