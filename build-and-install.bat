@echo off
rem Builds the HCIM Guide Bot and copies it into Microbot. Double click this file.
rem Close Microbot first, or Windows will not let the old jar be replaced.
cd /d "%~dp0"
title HCIM Guide Bot build

set "MICROBOT_JAR="
for %%F in ("%USERPROFILE%\.microbot\microbot-*.jar") do set "MICROBOT_JAR=%%~fF"
if not defined MICROBOT_JAR (
    echo Could not find the Microbot jar in %USERPROFILE%\.microbot
    echo Run the Microbot Launcher once so it downloads Microbot, then try again.
    pause
    exit /b 1
)
echo Building against %MICROBOT_JAR%
echo.

call gradlew.bat :microbot-plugin:pluginJar -PmicrobotJar="%MICROBOT_JAR%"
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Send a screenshot of the first red lines that start with e:
    pause
    exit /b 1
)

set "PLUGINS=%USERPROFILE%\.runelite\microbot-plugins"
if not exist "%PLUGINS%" mkdir "%PLUGINS%"
copy /y "microbot-plugin\build\libs\HcimGuideBotPlugin.jar" "%PLUGINS%\HcimGuideBotPlugin.jar" >nul
if errorlevel 1 (
    echo.
    echo Built, but could not copy the jar. Close Microbot and run this again.
    pause
    exit /b 1
)

echo.
echo Done. HcimGuideBotPlugin.jar is now in %PLUGINS%
echo These are the jars in that folder. Only HcimGuideBotPlugin.jar should be the bot:
dir /b "%PLUGINS%\*.jar"
echo.
echo Start Microbot and turn on HCIM Guide Bot.
pause
