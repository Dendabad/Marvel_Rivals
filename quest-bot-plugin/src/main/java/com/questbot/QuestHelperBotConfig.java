package com.questbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/** Settings shown in Microbot's plugin panel. Java, so RuneLite's config proxy sees plain default methods. */
@ConfigGroup(QuestHelperBotConfig.GROUP)
public interface QuestHelperBotConfig extends Config
{
	String GROUP = "questhelperbotsettings";
	String PAUSED = "paused";

	@ConfigItem(
		keyName = PAUSED,
		name = "Pause",
		description = "Tick to pause the bot at once, untick to carry on.",
		position = -1
	)
	default boolean paused()
	{
		return false;
	}

	@ConfigItem(
		keyName = "quest",
		name = "Quest",
		description = "The quest to do, as Quest Helper names it (Witch's House). Blank does whichever quest Quest Helper has open.",
		position = 0
	)
	default String quest()
	{
		return "";
	}

	@ConfigItem(
		keyName = "buyItems",
		name = "Buy items at the Grand Exchange",
		description = "Before the quest, buy what Quest Helper lists for it. Not for ironmen, who cannot trade.",
		position = 1
	)
	default boolean buyItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeRecommended",
		name = "Buy recommended items too",
		description = "Buy the items Quest Helper only recommends, as well as the ones it needs.",
		position = 2
	)
	default boolean includeRecommended()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowCombat",
		name = "Allow combat",
		description = "Let the bot fight when the quest says to. A boss far above your Hitpoints level is still left to you.",
		position = 3
	)
	default boolean allowCombat()
	{
		return false;
	}
}
