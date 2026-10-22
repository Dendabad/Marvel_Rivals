package com.chinbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/** Settings shown in Microbot's plugin panel. Java, so RuneLite's config proxy sees plain default methods. */
@ConfigGroup(BlackChinBotConfig.GROUP)
public interface BlackChinBotConfig extends Config
{
	String GROUP = "blackchinbotsettings";
	String PAUSED = "paused";

	@ConfigItem(
		keyName = PAUSED,
		name = "Pause",
		description = "Tick to pause at once, untick to carry on.",
		position = -1
	)
	default boolean paused()
	{
		return false;
	}

	@ConfigItem(
		keyName = "traps",
		name = "Box traps to keep set",
		description = "How many box traps to have out at once. 5 at Hunter 73, 6 at Hunter 80, and one more in the Wilderness.",
		position = 0
	)
	default int traps()
	{
		return 6;
	}

	@ConfigItem(
		keyName = "radius",
		name = "Trap radius",
		description = "How far from where you started the traps are laid, in tiles.",
		position = 1
	)
	default int radius()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "hopOnPlayers",
		name = "Hop worlds when a player is near",
		description = "Hop to another world as soon as another player comes within the range below. Strongly advised in the Wilderness.",
		position = 2
	)
	default boolean hopOnPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "playerRange",
		name = "Player range",
		description = "How close, in tiles, another player has to be to make the bot hop.",
		position = 3
	)
	default int playerRange()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "minHealthPercent",
		name = "Stop under this health %",
		description = "Stop the bot, and say so, when your health falls under this share of your maximum.",
		position = 4
	)
	default int minHealthPercent()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "bankAt",
		name = "Bank at this many chinchompas",
		description = "When the bag holds this many black chinchompas, the bot takes its traps in, walks to the bank, banks them and walks back.",
		position = 5
	)
	default int bankAt()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "bankX",
		name = "Bank tile X",
		description = "Where to walk to bank. The default is Edgeville bank. The bot opens the nearest bank from there.",
		position = 6
	)
	default int bankX()
	{
		return 3094;
	}

	@ConfigItem(
		keyName = "bankY",
		name = "Bank tile Y",
		description = "Where to walk to bank. The default is Edgeville bank.",
		position = 7
	)
	default int bankY()
	{
		return 3491;
	}
}
