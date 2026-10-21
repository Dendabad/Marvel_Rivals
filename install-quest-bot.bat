@echo off
rem Builds the Quest Helper Bot, a standalone Microbot plugin, and installs it. Double click this file.
rem Close Microbot first: it keeps the plugin jar open while it runs.
cd /d "%~dp0"
title Quest Helper Bot build

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

call gradlew.bat :quest-bot-plugin:pluginJar -PmicrobotJar="%MICROBOT_JAR%"
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Send a screenshot of the first red lines that start with e:
    if not defined HCIM_NOPAUSE pause
    exit /b 1
)

set "PLUGINS=%USERPROFILE%\.runelite\microbot-plugins"
if not exist "%PLUGINS%" mkdir "%PLUGINS%"
copy /y "quest-bot-plugin\build\libs\QuestHelperBotPlugin.jar" "%PLUGINS%\QuestHelperBotPlugin.jar" >nul
if errorlevel 1 (
    echo.
    echo Could not copy the plugin to %PLUGINS%
    echo Close Microbot, then run this again.
    if not defined HCIM_NOPAUSE pause
    exit /b 1
)
echo.
echo Installed. Start Microbot, find "Quest Helper Bot" in its plugin list, set the quest, and turn it on.
echo The Quest Helper plugin must be on as well.
if not defined HCIM_NOPAUSE pause
