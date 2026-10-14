# First 100 steps

What the bot does at each of the first 100 steps of the B0aty HCIM Guide V3. Every step was reviewed by hand, and `FirstHundredTest` checks the bot still plans each one this way.

**Runs** means the bot does the step on its own. **Skips** means there is nothing to do in the game (a heading or a reminder). **Pauses** means it stops and waits for you to do the step and tick it in the plugin.

With **Allow combat** on: 91 run, 9 skip, 0 pause (step 80 still pauses for its two puzzles). With it off, the two fights (#19 and #27) pause too.

| # | Step | Bot | What happens |
| --- | --- | --- | --- |
| 1 | Starting Out | Skips | Section heading. |
| 2 | All Settings -> Disable level-up | Runs | Setting: disable-level-up. |
| 3 | Talk to Father Aereck (3,1) [Restless Ghost] | Runs | Follows Quest Helper for therestlessghost. Picks the chat options Quest Helper lists, then moves on once the quest leaves Father Aereck. |
| 4 | Pickpocket a man/woman [Lumbridge Easy Diary] | Runs | Pickpocket Man. Pickpockets until the diary task ticks. Failed pickpockets hit for 1 or 2, so the hitpoint checks matter here. |
| 5 | Head North-West to Magic combat tutor & drop Air & Mind Runes and claim more. | Runs | The tutor only gives runes to players who have none, so it drops them first, picks the chat option about runes, then picks the dropped runes back up. |
| 6 | Sell Bronze Dagger, Sword, Wooden Shield & Shortbow to General Store. Buy a Spade Chisel & hammer | Runs | Sells the four starter items to the Lumbridge general store, then buys the spade, chisel and hammer. |
| 7 | Head East & Start X Marks the Spot on Veos (2,1) & complete the first step (Install Quest Helper plug in on Runelite) | Runs | Follows Quest Helper for xmarksthespot. |
| 8 | Check playtime on Hans [Lumbridge Easy Diary] | Runs | Uses Hans's right click Age option. If that does not tick the diary task, asks him in chat how long you have played. |
| 9 | Go upstairs and Talk to Duke Horacio (1,1) [Rune Mysteries] | Runs | Follows Quest Helper for runemysteries. Quest Helper gives no id for the staircase, so the bot walks up to the Duke and the walker climbs the stairs. |
| 10 | Go upstairs again and Collect 4x logs on the top floor [Tree Gnome Village] | Runs | Walks up to the top floor of the castle, then takes the 4 logs, waiting for them to respawn if needed. |
| 11 | Bank at Lumbridge castle top floor and deposit all | Runs | Walk to (3188, 3220, 1), then Bank, deposit all. Only walks if you are more than 30 tiles from Lumbridge, then opens the nearest bank. |
| 12 | Keep the Shrimps in your bank for later [Family Crest Quest] | Skips | A reminder to keep an item, nothing to do. |
| 13 | Move Coins into first bank slot & Runes to the top slots. Click the lock to Always set Placeholders. Set Bank pin for Count Check randoms or delete the pin from Tutorial Island. | Skips | Bank tidying the bot does not need. Bank layout and the bank PIN are optional tidying, so the bot skips them. Never let Microbot store your PIN. |
| 14 | Withdraw: Coins, Air Runes, Mind Runes, Bread, Spade, Tinderbox, Air Talisman, Treasure Scroll (8 Inventory slots) | Runs | Bank, withdraw Coins, Air Runes, Mind Runes, Bread, Spade, Tinderbox, Air Talisman, Treasure Scroll. Coins and runes: takes all. Everything else: takes one. For the Treasure Scroll it takes whichever X Marks the Spot item you are on (scroll, orb or casket). If the quest was never started, it goes back to step 7 for the scroll first, then carries on here. |
| 15 | Go down south staircase twice (Right-Click 'Bottom-floor') | Runs | Climbs down the south staircase until it reaches the ground floor. |
| 16 | Collect the Empty Jug, Bowl & Knife in Lumbridge kitchen | Runs | Take Empty Jug, Bowl, Knife in Lumbridge kitchen. |
| 17 | Fill the jug & bowl on the sink in the kitchen [Monk's Friend] | Runs | Use item 1935 on Sink, then Use item 1923 on Sink. |
| 18 | Complete 2nd Step of X Marks The Spot | Runs | Follows Quest Helper for xmarksthespot. guide.json leaves the digs out, so the bot uses the dig spots from Quest Helper's own source: it walks onto the spot and digs with the spade. |
| 19 | Kill a giant rat with Wind Strike and collect the Bones + Raw Rat Meat [Druidic Ritual] | Runs | Runs only with Allow combat on. Casts Wind Strike on a giant rat and picks up the bones and meat. With no rat in sight, it looks just behind (west of) Lumbridge castle first, then at the nearest spots where giant rats spawn from Microbot's NPC data. |
| 20 | Head West towards Draynor hugging the fence to avoid the Jail Guards | Skips | Only a direction to set off in. Step 21 walks straight to Father Urhney in Lumbridge Swamp, so the bot does not go to Draynor. |
| 21 | Talk to Father Urhney in the lumbridge swamp for Ghost Speak Amulet. (2,1) Wield this. | Runs | Talk-to Father Urhney, then Equip Ghostspeak amulet. |
| 22 | Take Leather Gloves on the table inside his house & wield | Runs | Take Leather Gloves on the table, then Equip Leather Gloves on the table. |
| 23 | Head to the Wizards Tower | Skips | The guide's spot is the north end of the bridge, so step 24 does the walk, right into the tower. |
| 24 | Take Leather Boots off the table & wield | Runs | Walks into the Wizards' Tower, takes the Leather boots and wears them. |
| 25 | Collect 3x Logs next to the stairs [Tree Gnome Village] | Runs | Take Logs. |
| 26 | Head to the basement & Talk to Sedridor to continue Rune Mysteries (1,2,1) | Runs | Follows Quest Helper for runemysteries. |
| 27 | Kill a Chicken. Take Everything (Make sure you get a feather, else hop worlds and repeat)[Client of Kourend + Druidic Ritual] | Runs | Runs only with Allow combat on. Kills chickens until one drops a feather, and picks up everything. |
| 28 | Collect 3x Logs [Tree Gnome Village] | Runs | Take Logs. |
| 29 | Head north to Draynor & talk to Friendly Forester and obtain Forestry kit (right click shop and buy 1) | Runs | Buy Forestry kit. |
| 30 | Bank at Draynor and Deposit all | Runs | Walk to (3103, 3265, 0), then Bank, deposit all. |
| 31 | Withdraw: Coins, Spade, Feather & Mysterious Orb (4 Inventory Slots) | Runs | Bank, withdraw Coins, Spade, Feather, Mysterious Orb. |
| 32 | Collect Cheese from Aggie [Witch's House] | Runs | Walk to (3086, 3259, 0), then Take Cheese. |
| 33 | Complete 3rd Step of X Marks the Spot | Runs | Follows Quest Helper for xmarksthespot. Same as #18. |
| 34 | Talk to Morgan and start Vampyre Slayer (1) | Runs | Follows Quest Helper for vampyreslayer. |
| 35 | Go up the stairs and collect 4x Garlic from the cupboard | Runs | Climbs to Morgan's upper floor, opens the cupboard and searches it until there are 4 garlic. |
| 36 | You can get multiple Garlic by spamclicking the cupboard [Fishing Contest] | Skips | Tip covered by the garlic step. |
| 37 | Train Draynor Agility to 5 Agility [Lumbridge Easy Diary] | Runs | Runs laps of the Draynor rooftop course (obstacles from Quest Helper's guide) until Agility is 5 and the diary task is done, picking up marks of grace and walking back to the start after a fall. |
| 38 | Bank at Draynor and deposit the Garlic & Cheese | Runs | Deposit [1550, 1985]. |
| 39 | Talk to Fortunato & buy 5 Jugs of wine. Hop worlds and buy 10 in total. | Runs | Buys 5, hops to another world, and buys 5 more. |
| 40 | Check the Notice board next to Fortunato to Start A Porcine of Interest | Runs | Follows Quest Helper for aporcineofinterest. guide.json has no id for the notice board, so the bot looks it up by its RuneLite name and reads it. The step is done once the quest has started: the rest of the quest (including Sourhog, level 37) is not the bot's to do here. |
| 41 | Complete 4th Step of X Marks the Spot | Runs | Follows Quest Helper for xmarksthespot. Same as #18. |
| 42 | Head West to Port Sarim and talk to Veos to complete X Marks The Spot. | Runs | Follows Quest Helper for xmarksthespot. Digs in the pig pen first if that dig is not done yet. |
| 43 | Destroy the lamp. You can collect this later as you can't bank it. | Runs | Destroys the X Marks the Spot lamp and confirms. |
| 44 | Travel to Port Piscarilius via Veos | Runs | Talks to Veos in Port Sarim (Microbot cannot click his Travel option) and says yes to the trip, then lands at Port Piscarilius dock. |
| 45 | Talk to Veos (4,1) [Client of Kourend] | Runs | Follows Quest Helper for clientofkourend. |
| 46 | Take the Minecart to Shayzien East (option B) | Runs | Travel to Shayzien East. |
| 47 | Open Minimap & follow the path South-West to Shayzien | Runs | Walk to (1519, 3592, 0). |
| 48 | Take the Beer | Runs | Take Beer. |
| 49 | Use your Feather on the Enchanted scroll in your inventory | Runs | Use item 314 on 21259. |
| 50 | Head west to the General store, Talk to Jennifer_(Shayzien) for Client of Kourend (3,2) | Runs | Talk-to Jennifer (Shayzien). |
| 51 | Take the Tinderbox | Runs | Take Tinderbox. |
| 52 | Take Wizard Hat & Orange Dye [Shadow of the Storm, Goblin Diplomacy] from house to the west (Shayzien Styles) | Runs | Take Wizard Hat, Orange Dye. |
| 53 | Trade Robyn and buy a Chef's Hat  [Cook's guild] | Runs | Buy Chef's Hat. |
| 54 | Home teleport to Lumbridge | Runs | Cast TELEPORT_HOME_STANDARD. Casts Home Teleport and waits for it to finish. |
| 55 | Talk to Bob and buy a Steel Axe | Runs | Buy Steel Axe. |
| 56 | Search the Coffin, south-east in the graveyard. Talk to Restless Ghost (1) | Runs | Wears the Ghostspeak amulet, opens and searches the coffin, then talks to the ghost. |
| 57 | Talk to Count Check and be teleported to Stronghold of Security (3,1) | Runs | Talk-to Count Check. |
| 58 | Complete 3 Floors of Stronghold of Security. (Answer the questions or spam click doors) | Runs | Walks to each floor's reward and claims it (Gift of Peace, Grain of Plenty, Box of Health: 10,000 coins). Microbot's walker answers the security doors. Drinks a Jug of wine on any damage, and does not pause when attacked. |
| 59 | On Floor 3, Spider max is 7 so if you take 3 or more damage, drink a wine before going through the door to avoid being stacked. Take the east doors. | Skips | Stronghold advice. |
| 60 | Catablepon Max is 9 so drink on any damage. | Skips | Stronghold advice. |
| 61 | Once you have 10,000GP from Floor 3, leave via the ropes | Runs | Walks back to the surface, climbing the vines, ladders and chain, drinking wine on any damage. |
| 62 | Collect 3x Beer inside the Longhall | Runs | Take Beer. |
| 63 | Collect a Cooked Meat [Mournings End Pt 2] | Runs | Take Cooked Meat. |
| 64 | Head east and Talk to Gertude (1) [Gertrudes Cat] | Runs | Follows Quest Helper for gertrudescat. |
| 65 | Collect a Doogle Leaf south of Gertrude | Runs | Walks just south of Gertrude's house and picks up the Doogle leaves lying there. |
| 66 | Bank at Varrock West Bank and deposit all | Runs | Walk to (3185, 3440, 0), then Bank, deposit all. |
| 67 | Withdraw: Coins, Bones, Beer (3 Inventory Spaces) | Runs | Bank, withdraw Coins, Bones, Beer. |
| 68 | RIGHT CLICK Use the bones on the Stray Dog whenever you find him | Runs | Takes a bone from the bank if there is none in the inventory, walks a round of Varrock until the Stray Dog is in sight, and gives it to him. Pauses if he cannot be found after two rounds. |
| 69 | Head East and buy an Air Staff from Zaff. Equip it. | Runs | Buy Air Staff, then Equip Air Staff. Buys the staff from Zaff, then wields it. |
| 70 | Head South & Talk to Aris and start Demon Slayer (1,2,3,4). | Runs | Follows Quest Helper for demonslayer. |
| 71 | Head South & Trade Thessalia [Varrock Easy Diary] | Runs | Trade Thessalia. Opens the shop until the diary task ticks, then closes it. |
| 72 | Buy Pink Skirt, Brown Apron & Silk. Wear Brown Apron & Pink Skirt in honour of Faux | Runs | Buys all three, then wears the apron and skirt. |
| 73 | Head South to the Sword Shop and go up the ladder. Loot the lit candle. | Runs | Walks up to the room above the Sword Shop (Microbot's walker climbs the ladder) and takes the lit candle. |
| 74 | Head East and talk to Dr Harlow (1,2 talk again)[Vampyre Slayer] | Runs | Takes the Garlic out of the bank first if you are not carrying it (it was banked at step 38), then follows Quest Helper for vampyreslayer. If the bank has none, Quest Helper sends the bot to Morgan's cupboard for it. |
| 75 | Head North | Runs | Walk to (3211, 3425, 0). |
| 76 | Talk to Romeo (3,1) [Romeo & Juliet] | Runs | Follows Quest Helper for romeojuliet. |
| 77 | Buy a Newspaper from Benny(1,1) | Runs | Talk-to Benny. |
| 78 | Talk to Shilop to continue Gertrudes Cat (2,2) | Runs | Follows Quest Helper for gertrudescat. |
| 79 | Talk to Noah (1,3) [Children of the Sun] | Runs | Talk-to Noah. Quest Helper starts this quest with Alina, but the guide says Noah, so the bot follows the guide. |
| 80 | Complete Children of the Sun | Runs | Follows Quest Helper for childrenofthesun to the end of the quest. Talks and the door run on their own, but it pauses for you to follow the guard without being seen and to mark the guards, since both are puzzles. |
| 81 | Go down North West 2x Stairs and talk to Sir Prysin in the SW room (3,1,2,2,1,1) [Demon Slayer] | Runs | Follows Quest Helper for demonslayer. |
| 82 | Head North and go up 2x Stairs and talk to Captain Rovin (3,1,2,2,3,1) [Demon Slayer] | Runs | Follows Quest Helper for demonslayer. |
| 83 | Return down the stairs and talk to Reldo and start Shield of Arrav (1,1). | Runs | Follows Quest Helper for shieldofarravphoenixgang. |
| 84 | Search for book and talk to Reldo again (1) | Runs | Searches the bookcase, reads the book, then talks to Reldo. |
| 85 | Head East and up the stairs in the Kitchen. | Runs | Climb-up Staircase. |
| 86 | Collect both buckets and fill one up downstairs. Use Bucket of Water on drain outside then refill it. | Runs | Takes both buckets upstairs, fills one at the kitchen sink, empties it into the drain, then refills it. |
| 87 | Take the Pie Dish [Knight's Sword] | Runs | Take Pie Dish. |
| 88 | Head to King Roald and start Priest in Peril (1,1,3) | Runs | Follows Quest Helper for priestinperil. |
| 89 | Talk to Marlo and start Daddy's Home (in a house the east of the castle) (2,3,3) | Runs | Follows Quest Helper for daddyshome. |
| 90 | Pick up the Saw | Runs | Take Saw. |
| 91 | Enter Varrock Sewer and take the North-West path to collect the Demon Slayer Key - Giant rat is aggressive, max hit is 1. | Runs | Walks to the Rusty Key in the Varrock Sewer (the walker climbs down the manhole) and takes it. Giant rats hit for 1 at most, so being attacked does not stop it. |
| 92 | Enter Varrock Museum and head downstairs and talk to Orlando Smith (1) and complete the Natural History Quiz for 9 slayer and hunter and 28 kudos. | Skips | The Natural History Quiz needs an answer table the bot does not have, and only gives 9 Slayer and Hunter XP and kudos, so it is left out. |
| 93 | Bank at Varrock East bank and Deposit all | Runs | Walk to (3254, 3420, 0), then Bank, deposit all. |
| 94 | Withdraw: Coins, Saw, Hammer, Knife, Bronze Axe, Research Package, Tinderbox (7 Inventory Slots) | Runs | Bank, withdraw Coins, Saw, Hammer, Knife, Bronze Axe, Research Package, Tinderbox. |
| 95 | Talk to Aubury to continue Rune Mysteries (1,2) | Runs | Follows Quest Helper for runemysteries. |
| 96 | Light both logs in the house right next to the rune shop. | Runs | Walks into Old Man Yarlo's house and lights both logs with the tinderbox, waiting for each to burn. |
| 97 | Talk to Old Man Yarlo, then destroy all the furniture in the house (can't destroy furniture until you speak to him) | Runs | Talks to Yarlo, then removes each piece of broken furniture until none is left. |
| 98 | Talk to Old Man Yarlo to continue Daddy's Home (2) | Runs | Follows Quest Helper for daddyshome. |
| 99 | Take 2x Logs & 2x ashes after cutscene | Runs | Take Logs, Ashes. |
| 100 | Search crates for 3x Waxwood log | Runs | Searches the crates until there are 3 waxwood logs. |

Guide text is from [Guide:B0aty HCIM Guide V3](https://oldschool.runescape.wiki/w/Guide:B0aty_HCIM_Guide_V3) by B0aty and the OSRS Wiki's contributors, CC BY-NC-SA 3.0.
