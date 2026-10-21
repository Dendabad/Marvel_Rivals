# Running the bot on Microbot

This folder is the Microbot plugin that runs the guide bot. Setup takes about 15 minutes the first time.

> **Use a throwaway account first.** Botting is against Jagex's rules and can get the account banned, and an untested bot on a hardcore account can cost the hardcore status. Never save your password or bank PIN in Microbot: it stores them with a key anyone can read.

## What you need

1. **Java 17 or newer.** For example [Temurin 17](https://adoptium.net/temurin/releases/?version=17). To check, run `java -version` in a terminal.
2. **The Microbot release jar,** `client-<version>-shaded.jar`, from the official releases page only: <https://github.com/chsami/microbot/releases>. Fake "Microbot" downloads are a common way accounts get stolen.
3. **This repository,** on branch `claude/osrs-script-creation-w5mkvb`.

## Easiest: the one-click updater

Download **`update-bot.bat`** once from the GitHub page (open the file, then the download button) and keep it on your desktop. Double click it whenever there is an update: it downloads the latest version into a clean folder (`.runelite\hcim-guide-bot\source`), builds it and installs it, so there is no ZIP to unzip and no old files can be left behind. Microbot can stay open.

## Quick way on Windows: double click

Double click **`build-and-install.bat`** in the project folder. It finds your Microbot jar, builds the bot, installs both of its files (see step 2), and lists the jars in `microbot-plugins` so you can spot a stray old copy. That does steps 1 and 2 below for you. If it says BUILD FAILED, send the first red lines that start with `e:`.

**Microbot can stay open.** The bot reloads itself a few seconds after the script finishes and carries on from the current step, with a short message saying which version it is now. Only when the small loader plugin itself changes (rarely) does the script ask you to close Microbot and run it again.

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

That makes two files in `microbot-plugin/build/libs/`:

- `HcimGuideBotPlugin.jar`: a small loader plugin. It rarely changes.
- `hcim-guide-bot-core.jar`: the bot itself. The loader watches this file and reloads the bot whenever it changes, without restarting the game.

## 2. Put them where they are loaded

Copy `HcimGuideBotPlugin.jar` into the `microbot-plugins` folder inside RuneLite's folder, and `hcim-guide-bot-core.jar` into the `hcim-guide-bot` folder next to it. Create the folders if they do not exist.

- Windows: `C:\Users\<you>\.runelite\microbot-plugins\` and `C:\Users\<you>\.runelite\hcim-guide-bot\`
- Mac or Linux: `~/.runelite/microbot-plugins/` and `~/.runelite/hcim-guide-bot/`

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
| Tick steps in B0aty guide | on | Ticks each step the bot finishes in the B0aty sidebar too. |
| Pause hotkey | none | A key that pauses and unpauses, e.g. F12. Click the box, then press the key. |
| Allow combat | off | Leave it off at first. Fighting steps pause for you. |
| Pause on tips | on | Stops at the guide's tips and warnings. |
| Food item ids | empty | Item ids to eat, best first, e.g. `315` for shrimps. Empty means it never eats and only stops. |
| Eat at hitpoints % | 60 | |
| Stop at hitpoints % | 40 | Stops the bot outright. |
| Pause when attacked | on | Pauses if a monster targets you during a step that is not a fight. |
| Start at step | 0 | Type a step number to retest from there, see below. |
| guide.json path | empty | Only if the B0aty plugin is not installed. |

Log in, then switch the plugin on. It starts at the first step not yet ticked in the B0aty guide.

## Bank PIN

If your account has a bank PIN, type it into the **Bank PIN** box in the bot's sidebar (the green H) and press **Set**. The bot then types it whenever the PIN screen shows. The PIN is held in memory only: it is never written to a file or to Microbot's settings, it is forgotten when you turn the plugin off or close the client, and **Forget PIN** removes it at once. It survives the bot reloading itself during an update.

If the bank refuses the PIN, the bot forgets it, pauses and tells you, rather than trying again, since a few wrong tries lock the bank for a while. Do not use Microbot's own saved PIN for this: it is "encrypted" with a key that is the same for everyone.

## While it runs

**On the game screen**, a box in the top left corner shows what the bot is doing: whether it is running or paused, the step it is on, the action in progress (like `Doing: Walk to (3218, 3238, 0)`), and why it paused, if it has.

**In the sidebar**, click the green **H** icon for the bot's panel. It shows the same information, with three buttons:

- **Pause / Resume** stops the bot at once, even in the middle of a walk, and starts it again. When you resume, it plans the current step afresh from wherever you are.
- **Skip this step** ticks the current step as done and moves on.
- **Retry this step** starts the current step's actions again, for example after you have fixed whatever made it pause.

You can also pause with the **Pause** checkbox in the settings, or with your **pause hotkey**. All of them do the same thing.

When the bot pauses by itself, a message explains why. Do the step yourself: the bot carries on as soon as the game shows it is done. If it cannot tell (a step with nothing to check), press **Skip** to mark it done, or **Retry** to have the bot try again.

Every step the bot finishes is ticked in the B0aty guide's sidebar too, as if you had clicked it, so the two always agree. Turn off **Tick steps in B0aty guide** in the settings to stop that.

It stops for good below the hitpoint limit. A watchdog checks hitpoints three times a second, even in the middle of a long walk, and can stop the walk or eat.

## Retesting a step

The on screen box and the sidebar show the step's number, like `Step 5 of 2925: Head North-West...`. The same numbers are used in [docs/first-100-steps.md](../docs/first-100-steps.md).

1. In the bot's settings, type the number into **Start at step** and press **Enter**.
2. The bot takes back its own ticks from that step on, in the B0aty guide too, and starts there. The setting goes back to 0 by itself.
3. The step you start at always runs, even if it is ticked in the B0aty guide. If later steps are ticked there, a message lists them. Untick them in the B0aty sidebar if you want those redone too, since the bot only clears its own ticks.

Some steps finish themselves as soon as the game shows they are done, for example a diary task already completed, a quest already past that point, or items already in your inventory. Those cannot be repeated on the same account. Retest them on a fresh account, or drop or bank the items first.

## If something goes wrong

- **The plugin is not in the list:** check that the jar is in `microbot-plugins` with its exact name, and that Microbot is not in safe mode.
- **"install and enable the B0aty HCIM Guide v3 plugin":** turn on the B0aty plugin, or set the guide.json path.
- **It pauses on almost everything:** that is expected for steps it cannot do safely. [docs/first-100-steps.md](../docs/first-100-steps.md) lists what it does at each of the first 100 steps.
- **Anything else:** RuneLite's log (`.runelite/logs/client.log`) has lines from `HcimGuideBotScript`. Send me those and I can fix it.

## Copy report

The sidebar's **Copy report** button copies what the bot sees to the clipboard as text: the step and why it stopped, the
Recent list, where the player is, the bag, the NPCs and objects nearby with the options each has, the text of the open
windows and chat, and what Quest Helper shows. Paste it into a message instead of sending screenshots.

## Automatic reports

If the Windows environment variable `HCIM_REPORT_TOKEN` is set, the bot uploads its report to the `bot-reports` branch of
this repo (`reports/latest.txt`) each time it pauses. Use a fine grained GitHub token for this one repo with only
Contents read and write. The token is read from the environment, never stored by the bot. The report holds position, bag,
nearby objects and chat text, so keep the repo private. Without the variable nothing is sent.

## AI help for stuck steps

If the environment variable `HCIM_AI_KEY` is set to an Anthropic API key, the bot asks a Claude model what to do whenever it
pauses, then does what it says and retries the step. The model can only choose from a fixed list of actions (walk, click an
NPC, object or item with a menu option, take an item, pick a chat option, continue, wait). Attacks, drops, destroying items and
the Wilderness are refused. It asks at most twice per step and 25 times per session. The key is read from the environment and
never stored. The report sent holds position, bag, nearby things and chat text. Without the variable nothing is sent.


## Buying quest items at the Grand Exchange

Tick "Buy quest items at the Grand Exchange" in the settings, and before a quest starts the bot reads the items Quest Helper lists for it,
needed and recommended. It takes what the bank holds, walks to the Grand Exchange in Varrock, puts in a buy offer at a generous price for
the rest, collects them and carries on. It is off by default and not for ironmen, who cannot trade. If Microbot's Grand Exchange helper
differs from what the bot expects, the bot pauses and the Recent list says which call it could not make.
