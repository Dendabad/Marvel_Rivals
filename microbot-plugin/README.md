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

Keep only one copy of the bot in that folder. Microbot loads every jar there, so an old one (like `hcim-guide-bot-0.1.0.jar` or `HcimGuideBotPlugin - Copy.jar`) runs alongside the new one and keeps playing when you pause. The bot stops any other copy it finds and tells you which jar to delete.

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
| Pause | off | Tick to pause the bot at once, untick to carry on. |
| Pause hotkey | none | A key that pauses and unpauses, e.g. F12. Click the box, then press the key. |
| Allow combat | off | Leave it off at first. Fighting steps pause for you. |
| Pause on tips | on | Stops at the guide's tips and warnings. |
| Food item ids | empty | Item ids to eat, best first, e.g. `315` for shrimps. Empty means it never eats and only stops. |
| Eat at hitpoints % | 60 | |
| Stop at hitpoints % | 40 | Stops the bot outright. |
| Pause when attacked | on | Pauses if a monster targets you during a step that is not a fight. |
| Record route | off | Records a safe route you walk, see below. |
| Route name | draynor-fence | The name to record under. |
| Start at step | 0 | Type a step number to retest from there, see below. |
| guide.json path | empty | Only if the B0aty plugin is not installed. |

Log in, then switch the plugin on. It starts at the first step not yet ticked in the B0aty guide.

## While it runs

**On the game screen**, a box in the top left corner shows what the bot is doing: whether it is running or paused, the step it is on, the action in progress (like `Doing: Walk to (3218, 3238, 0)`), and why it paused, if it has.

**In the sidebar**, click the green **H** icon for the bot's panel. It shows the same information, with three buttons:

- **Pause / Resume** stops the bot at once, even in the middle of a walk, and starts it again. When you resume, it plans the current step afresh from wherever you are.
- **Skip this step** ticks the current step as done and moves on.
- **Retry this step** starts the current step's actions again, for example after you have fixed whatever made it pause.

You can also pause with the **Pause** checkbox in the settings, or with your **pause hotkey**. All of them do the same thing.

When the bot pauses by itself, a message explains why. Do the step yourself and tick it in the B0aty guide's sidebar, or use **Skip** or **Retry**.

It stops for good below the hitpoint limit. A watchdog checks hitpoints three times a second, even in the middle of a long walk, and can stop the walk or eat.

## Retesting a step

The on screen box and the sidebar show the step's number, like `Step 5 of 2925: Head North-West...`. The same numbers are used in [docs/first-100-steps.md](../docs/first-100-steps.md).

1. In the bot's settings, type the number into **Start at step** and press **Enter**.
2. The bot forgets its own ticks from that step on and starts there. The setting goes back to 0 by itself.
3. The step you start at always runs, even if it is ticked in the B0aty guide. If later steps are ticked there, a message lists them. Untick them in the B0aty sidebar if you want those redone too, since the bot only clears its own ticks.

Some steps finish themselves as soon as the game shows they are done, for example a diary task already completed, a quest already past that point, or items already in your inventory. Those cannot be repeated on the same account. Retest them on a fresh account, or drop or bank the items first.

## Recording a safe route

Some steps say to walk a particular way to stay safe, such as step 20, "Head West towards Draynor hugging the fence to avoid the Jail Guards". The walker always takes the shortest path, so for these the bot follows a route you recorded instead. You walk it once, and the bot walks it the same way every time after that.

To record step 20's route:

1. Get to where step 20 starts (the end of step 19). The easiest way is to let the bot run until it pauses on step 20.
2. In the bot's settings, check **Route name** says `draynor-fence`, then turn on **Record route**.
3. Walk the safe way yourself, hugging the fence, all the way to Draynor. While recording, the bot does nothing else and the status line counts the tiles.
4. Turn **Record route** off. A message shows where the route was saved.

The bot carries on with step 20 by itself, following the new route. Routes are saved as plain text in `C:\Users\<you>\.runelite\hcim-guide-bot\routes\`, one file per route, like `draynor-fence.txt`. Send me a file if you want a route built into the bot for good.

The bot only follows a route from close to its start (within 15 tiles), so it never wanders across the danger to reach it. If you record a route again under the same name, the new one replaces the old.

## If something goes wrong

- **The plugin is not in the list:** check that the jar is in `microbot-plugins` with its exact name, and that Microbot is not in safe mode.
- **"install and enable the B0aty HCIM Guide v3 plugin":** turn on the B0aty plugin, or set the guide.json path.
- **It pauses on almost everything:** that is expected for steps it cannot do safely. [docs/first-100-steps.md](../docs/first-100-steps.md) lists what it does at each of the first 100 steps.
- **Anything else:** RuneLite's log (`.runelite/logs/client.log`) has lines from `HcimGuideBotScript`. Send me those and I can fix it.
