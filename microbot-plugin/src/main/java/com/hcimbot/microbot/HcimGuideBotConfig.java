package com.hcimbot.microbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/** Settings shown in Microbot's plugin panel. Java, so RuneLite's config proxy sees plain default methods. */
@ConfigGroup(HcimGuideBotConfig.GROUP)
public interface HcimGuideBotConfig extends Config
{
	String GROUP = "hcimguidebotsettings";

	@ConfigItem(
		keyName = "allowCombat",
		name = "Allow combat",
		description = "Let the bot fight when a step says to. Off means combat steps pause for you.",
		position = 0
	)
	default boolean allowCombat()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pauseOnTips",
		name = "Pause on tips",
		description = "Stop at the guide's tips and warnings so you can read them.",
		position = 1
	)
	default boolean pauseOnTips()
	{
		return true;
	}

	@ConfigItem(
		keyName = "foodIds",
		name = "Food item ids",
		description = "Comma separated item ids to eat, best first. Empty means never eat, only stop.",
		position = 2
	)
	default String foodIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "eatAtPercent",
		name = "Eat at hitpoints %",
		description = "Eat when hitpoints fall to this share of the maximum.",
		position = 3
	)
	default int eatAtPercent()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "stopAtPercent",
		name = "Stop at hitpoints %",
		description = "Stop the bot outright at this share of the maximum.",
		position = 4
	)
	default int stopAtPercent()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "pauseWhenAttacked",
		name = "Pause when attacked",
		description = "Pause if a monster targets you during a step that is not a fight.",
		position = 5
	)
	default boolean pauseWhenAttacked()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideJsonPath",
		name = "guide.json path",
		description = "Only needed if the B0aty HCIM Guide v3 plugin is not installed: the path to its guide.json.",
		position = 6
	)
	default String guideJsonPath()
	{
		return "";
	}
}
