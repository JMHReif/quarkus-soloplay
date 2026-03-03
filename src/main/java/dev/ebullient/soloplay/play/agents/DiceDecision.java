package dev.ebullient.soloplay.play.agents;

import dev.langchain4j.model.output.structured.Description;

public record DiceDecision(
        @Description("true if a dice roll is needed for this action, false otherwise") boolean rollNeeded,

        @Description("\"skill_check\", \"attack\", \"saving_throw\", \"ability_check\". "
                + "null if rollNeeded is false.") String type,

        @Description("\"persuasion\", \"stealth\", etc. null for attacks/saves or if rollNeeded is false.") String skill,

        @Description("\"strength\", \"dexterity\", etc. null if rollNeeded is false.") String ability,

        @Description("Difficulty class. null if contested, attack roll, or rollNeeded is false.") Integer dc,

        @Description("Who/what this roll is for or against. null if rollNeeded is false.") String target,

        @Description("Brief explanation of the roll for the player. null if rollNeeded is false.") String context) {
}
