# HCIM Guide Bot

A Kotlin script that plays through [B0aty's HCIM Guide V3](https://oldschool.runescape.wiki/w/Guide:B0aty_HCIM_Guide_V3)
by following the **B0aty HCIM Guide v3** RuneLite plugin step by step.

> Botting is against Jagex's rules and can get the account banned. On a hardcore
> account a bot mistake can also cost the hardcore status. The bot is built to
> pause whenever it is unsure, but test it on a throwaway account first.

## How it follows the plugin

- **Same steps.** The bot reads the plugin's own `guide.json` from the classpath, so it always follows exactly the steps the plugin shows. The guide text is not copied into this repo.
- **Same "done" rules.** Completion is a port of the plugin's auto tick rules: diary bits, varbits and skill levels, quest progress (`questDoneAt`, `questCompletes`, stop conditions and so on), arriving within 10 tiles, and having the items.
- **Same progress.** The bot reads the plugin's ticks. Ticking a step in the plugin's sidebar moves the bot past it. The bot keeps its own ticks under a separate key, because the plugin overwrites outside changes to its save.
- **Quests** follow the Quest Helper data that ships in `guide.json`: the quest's progress variable picks the instruction, and its branch conditions pick the right branch. A guide step that is only part of a quest ("Talk to Father Aereck") does only that part, since the guide fits other steps in between.

## Safety rules

The bot **pauses** and shows the reason instead of guessing when a step:

- is a tip or note, or warns about something ("hugging the fence to avoid the Jail Guards")
- would fight, unless `allowCombat` is on (quest fights too)
- does not say which menu option to click, since a left click on a monster attacks it
- asks for several actions ("Kill a rat and collect the bones")
- depends on a condition or repeats ("if", "until", "optional", "roughly")
- deposits only some items ("Deposit all except Ice Gloves")
- needs a fairy ring code, sells items, or names an item it cannot identify
- goes into the Wilderness (quest steps), or reaches a part of a quest the guide data has no Quest Helper instructions for
- would pick up an item that is not in sight, rather than walking off to find one
- fails to make progress, or takes too long

It **stops** below 40% hitpoints, eats at 60% when food is set, and pauses if something attacks the player during a step that is not a fight.

Against the real guide, about 1,318 of its 2,925 steps run automatically. The first 100 steps are reviewed one by one in [docs/first-100-steps.md](docs/first-100-steps.md). The bot pauses on the rest so you can do them by hand, then tick them in the plugin to carry on.

## Layout

| Path | What it does |
| --- | --- |
| `guide/` | `guide.json` model and loader |
| `game/Game.kt` | `GameState` (reads) and `GameActions` (clicks), the only link to the game |
| `engine/GuideRunner.kt` | The step loop, called every game tick |
| `engine/StepCompletion.kt`, `engine/Conditions.kt` | The plugin's completion and condition rules |
| `engine/Safety.kt` | Settings and hardcore checks |
| `plan/StepPlanner.kt` | Turns a step into tasks, or into a pause |
| `plan/Tasks.kt`, `plan/QuestTask.kt` | Walk, bank, talk, shop, pick up, equip, travel, teleport and quest tasks |
| `progress/Progress.kt` | Progress shared with the plugin |
| `sdk/BotClientActions.kt` | **To fill in once the Bot Client SDK arrives** |

## Hooking it up to Bot Client

Once you have the SDK:

1. Fill in `sdk/BotClientActions.kt` with the SDK's calls.
2. Implement `GameState` on RuneLite's `Client`. It only reads the game, so it needs nothing from the SDK.
3. In the SDK's script entry point, load the guide with `GuideLoader().loadFromPlugin()`, build a `SharedProgressStore` on RuneLite's `ConfigManager`, create a `GuideRunner`, and call `onGameTick()` from the `GameTick` event.
4. Change Gson to `compileOnly` in `build.gradle.kts`, since RuneLite already ships it.

## Building and testing

```
./gradlew test
```

The tests run the bot against a fake game. To also check it against the plugin's real guide, pass its `guide.json`, which is in the plugin's source at `src/main/resources/com/b0atyguide/guide.json`:

```
./gradlew test -PguideJson=/path/to/guide.json
```

That also runs `FirstHundredTest`, which fails if the plan for any of the first 100 steps changes from the reviewed one. After reviewing a deliberate change, record the new plans with `-PwriteFirstHundred=true`.

## Credits

The guide is by B0aty, and the wiki page is by the OSRS Wiki's contributors (CC BY-NC-SA 3.0). The guide data and completion rules come from the [B0aty HCIM Guide v3 plugin](https://github.com/previns/b0aty-hcim-guide-v3) by Previn (BSD 2-Clause).
