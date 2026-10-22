# Black Chin Bot

A standalone Microbot plugin, separate from the HCIM Guide Bot and the Quest Helper Bot. It traps black chinchompas with box traps around
the place where you start it.

## Install

1. Close Microbot.
2. Double click `install-chin-bot.bat` in the project folder. It builds the plugin and copies `BlackChinBotPlugin.jar` to
   `%USERPROFILE%\.runelite\microbot-plugins`.
3. Start Microbot, walk to the hunting spot, and turn on **Black Chin Bot**.

## Use

Black chinchompas are caught with box traps at Hunter 73 (Eagles' Peak done), southeast of the Lava Maze in the Wilderness, around level 32 to 36.
Stand there with box traps in the bag, then turn the plugin on: the spot where you stand is the centre of the hunt. It walks back to that spot after each bank trip, but the first walk there is yours.

Each tick it:
1. checks a trap that has caught something ("Shaking box"), and resets one that failed,
2. lays more box traps, each on a free tile within the radius, until the number you set is out,
3. watches for danger: another player within the range makes it hop worlds, and your health falling under the set share stops it.

It stops and tells you when it is out of box traps.

## The bank trip

When the bag holds the number of black chinchompas you set (100 by default), the bot:
1. checks and dismantles every trap, so none is left out for the next player,
2. walks to the bank tile you set (Edgeville bank by default), accepting the Wilderness ditch warning if it shows,
3. banks the chinchompas, and takes out box traps if the bag holds fewer than the number you set,
4. walks back to the spot where you started it and hunts again.

It walks the way Microbot's walker finds, which on the way out passes through the Wilderness. Nothing is done about players on the walk
except the health stop, so keep an eye on it. If anything on the trip fails, such as no bank to open or a PIN screen, it pauses and says
what happened.

## Settings

| Setting | What it does |
| --- | --- |
| Pause | Tick to pause at once, untick to carry on. |
| Box traps to keep set | How many traps to have out. 5 at Hunter 73, 6 at 80, one more in the Wilderness. |
| Trap radius | How far from where you started traps are laid, in tiles. |
| Hop worlds when a player is near | Hops as soon as any other player is within the range. Advised in the Wilderness. |
| Player range | How close, in tiles, a player must be to make it hop. |
| Stop under this health % | Stops the bot when your health falls under this share of your maximum. |
| Bank at this many chinchompas | The count in the bag that starts the bank trip. |
| Bank tile X and Y | Where to walk to bank. The defaults are Edgeville bank. |

## Be careful

The Wilderness is player versus player: anyone can attack, and everything carried can be lost. Carry only the traps and what the hunt
needs. The bot hops and stops, but it cannot out-run a player or teleport you, and a hop takes a few seconds.

The trap names and options ("Box trap", "Shaking box", Check, Reset, Lay) are what the game shows. If a name or option differs in your game
version, the bot lays or checks nothing: tell me what the trap shows when you right click it and I will fix it.
