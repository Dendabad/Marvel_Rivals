package com.hcimbot.microbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

/** Settings shown in Microbot's plugin panel. Java, so RuneLite's config proxy sees plain default methods. */
@ConfigGroup(HcimGuideBotConfig.GROUP)
public interface HcimGuideBotConfig extends Config
{
	String GROUP = "hcimguidebotsettings";
	String PAUSED = "paused";

	@ConfigItem(
		keyName = PAUSED,
		name = "Pause",
		description = "Tick to pause the bot at once, untick to carry on. The pause hotkey flips this too.",
		position = -2
	)
	default boolean paused()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pauseHotkey",
		name = "Pause hotkey",
		description = "A key that pauses and unpauses the bot, e.g. F12. Click the box, then press the key.",
		position = -1
	)
	default Keybind pauseHotkey()
	{
		return Keybind.NOT_SET;
	}

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
		keyName = "recordRoute",
		name = "Record route",
		description = "While on, the bot does nothing but record the tiles you walk. Turn it off to save the route.",
		position = 7
	)
	default boolean recordRoute()
	{
		return false;
	}

	@ConfigItem(
		keyName = "routeName",
		name = "Route name",
		description = "The name to record under. Step 20 (the walk past the Draynor Jail Guards) uses draynor-fence.",
		position = 8
	)
	default String routeName()
	{
		return "draynor-fence";
	}

	@ConfigItem(
		keyName = "tickB0atyGuide",
		name = "Tick steps in B0aty guide",
		description = "Tick each step the bot finishes in the B0aty guide's own sidebar, as if you had clicked it.",
		position = 10
	)
	default boolean tickB0atyGuide()
	{
		return true;
	}

	String START_AT_STEP = "startAtStep";

	@ConfigItem(
		keyName = START_AT_STEP,
		name = "Start at step",
		description = "To retest a step, type its number (shown in the status line) and press Enter. "
			+ "The bot forgets its own ticks from that step on and starts there, then this goes back to 0.",
		position = 9
	)
	default int startAtStep()
	{
		return 0;
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
