# Quest Helper Bot

A standalone Microbot plugin, separate from the HCIM Guide Bot. It does a quest by following the Quest Helper plugin, and can buy the
items Quest Helper lists for the quest at the Grand Exchange first. It does not use the B0aty guide.

## Install

1. Close Microbot.
2. Double click `install-quest-bot.bat` in the project folder. It builds the plugin against the Microbot jar the launcher downloaded and
   copies `QuestHelperBotPlugin.jar` to `%USERPROFILE%\.runelite\microbot-plugins`.
3. Start Microbot. Turn on the **Quest Helper** plugin, then **Quest Helper Bot**.

## Settings

| Setting | What it does |
| --- | --- |
| Pause | Tick to pause at once, untick to carry on. The bot also ticks it when it meets something it cannot do. |
| Quest | The quest as Quest Helper names it, for example `Witch's House`. Blank does whichever quest Quest Helper has open. |
| Buy items at the Grand Exchange | Before the quest, takes what the bank holds of the quest's items and buys the rest. Not for ironmen. |
| Buy recommended items too | Includes the items Quest Helper only recommends. |
| Allow combat | Lets the bot fight when the quest says to. A boss far above your Hitpoints level is still left to you. |

## What it does

1. Reads the quest's item list from Quest Helper, needed and recommended.
2. With the Grand Exchange setting on, withdraws what the bank has, walks to the Grand Exchange in Varrock, buys the rest at a price well
   over the guide price so the offers fill at once, and collects them.
3. Follows each instruction Quest Helper shows: talks to the NPC it points at, clicks the object with the verb the instruction opens
   with (climb, search, open, push and so on), or walks to the tile. It closes a book left open on screen, and never enters the Wilderness.
4. Carries on until Quest Helper has nothing left to show, which is the quest done.

## What it pauses on

An instruction that needs an item used on something (Quest Helper does not say which item), a fight with combat off, a boss far above
your level, and an instruction with nothing to click. The box on the game screen shows why. Do that part by hand and untick Pause.

The Grand Exchange part calls Microbot's own helper by name, so if a call differs in your Microbot version the bot pauses and says
which one it could not make.
