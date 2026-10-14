# Running the bot on Microbot

This folder is the Microbot plugin that runs the guide bot. Setup takes about 15 minutes the first time.

> **Use a throwaway account first.** Botting is against Jagex's rules and can get the account banned, and an untested bot on a hardcore account can cost the hardcore status. Never save your password or bank PIN in Microbot: it stores them with a key anyone can read.

## What you need

1. **Java 17 or newer.** For example [Temurin 17](https://adoptium.net/temurin/releases/?version=17). To check, run `java -version` in a terminal.
2. **The Microbot release jar,** `client-<version>-shaded.jar`, from the official releases page only: <https://github.com/chsami/microbot/releases>. Fake "Microbot" downloads are a common way accounts get stolen.
3. **This repository,** on branch `claude/osrs-script-creation-w5mkvb`.

## 1. Build the plugin

In a terminal, in this repository's folder, point the build at the Microbot jar you downloaded:

Windows:

```
gradlew.bat :microbot-plugin:pluginJar -PmicrobotJar=C:\path\to\client-2.6.32-shaded.jar
```

Mac or Linux:

```
./gradlew :microbot-plugin:pluginJar -PmicrobotJar=/path/to/client-2.6.32-shaded.jar
```

That makes `microbot-plugin/build/libs/HcimGuideBotPlugin.jar`.

## 2. Put it where Microbot loads plugins

Copy `HcimGuideBotPlugin.jar` into the `microbot-plugins` folder inside RuneLite's folder. Create the folder if it does not exist.

- Windows: `C:\Users\<you>\.runelite\microbot-plugins\`
- Mac or Linux: `~/.runelite/microbot-plugins/`

Keep the file name exactly `HcimGuideBotPlugin.jar`. Microbot names the plugin after the file.

## 3. Start Microbot

```
java -jar client-2.6.32-shaded.jar
```

For a Jagex account, log in once through the Jagex Launcher first. Microbot's [installation guide](https://github.com/chsami/Microbot/blob/main/docs/installation.md) covers that.

Do not start in safe mode, because safe mode skips sideloaded plugins.

## 4. Install the B0aty guide plugin

The bot follows the **B0aty HCIM Guide v3** plugin, so install it from RuneLite's Plugin Hub (the wrench icon, then Plugin Hub) and turn it on. The bot reads the guide and your ticks from it.

If the Plugin Hub is not available, put the plugin's `guide.json` somewhere on your computer and set **guide.json path** in the bot's settings instead.

## 5. Set up and start the bot

Find **HCIM Guide Bot** in Microbot's plugin list and open its settings:

| Setting | Default | What it does |
| --- | --- | --- |
| Allow combat | off | Leave it off at first. Fighting steps pause for you. |
| Pause on tips | on | Stops at the guide's tips and warnings. |
| Food item ids | empty | Item ids to eat, best first, e.g. `315` for shrimps. Empty means it never eats and only stops. |
| Eat at hitpoints % | 60 | |
| Stop at hitpoints % | 40 | Stops the bot outright. |
| Pause when attacked | on | Pauses if a monster targets you during a step that is not a fight. |
| guide.json path | empty | Only if the B0aty plugin is not installed. |

Log in, then switch the plugin on. It starts at the first step not yet ticked in the B0aty guide.

## While it runs

- Microbot's status line shows the current step, or why the bot paused.
- **When it pauses,** a message explains why. Do the step yourself, then **tick it in the B0aty guide's sidebar.** The bot carries on from the next step by itself.
- To retry the paused step from scratch, switch the plugin off and on again.
- To stop, switch the plugin off.
- It stops for good below the hitpoint limit. A watchdog checks hitpoints three times a second, even in the middle of a long walk, and can stop the walk or eat.

## If something goes wrong

- **The plugin is not in the list:** check that the jar is in `microbot-plugins` with its exact name, and that Microbot is not in safe mode.
- **"install and enable the B0aty HCIM Guide v3 plugin":** turn on the B0aty plugin, or set the guide.json path.
- **It pauses on almost everything:** that is expected for steps it cannot do safely. [docs/first-100-steps.md](../docs/first-100-steps.md) lists what it does at each of the first 100 steps.
- **Anything else:** RuneLite's log (`.runelite/logs/client.log`) has lines from `HcimGuideBotScript`. Send me those and I can fix it.
