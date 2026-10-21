rootProject.name = "hcim-guide-bot"

// The Microbot plugin. Builds only when given a Microbot client jar, see microbot-plugin/README.md.
include("microbot-plugin")

// The standalone Quest Helper Bot plugin: follows Quest Helper for any quest and can buy its items at the Grand Exchange.
include("quest-bot-plugin")
