package dev.ebullient.soloplay.play;

import java.util.List;

import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.langchain4j.model.output.structured.Description;

public record GamePlayResponse(
        @Description("FIRST: Think step by step before narrating. "
                + "Cover: player intent, what happens next, is a roll needed, "
                + "segment completion status, major deviations, checkpoint-worthy moments.") String reasoning,

        @Description("Story prose in markdown. ONLY narrative — no field labels or mechanics. "
                + "Vivid descriptions, dialogue, sensory details. "
                + "If location changed, narrate the transition. "
                + "MUST end with a clear hook or call to action.") String narration,

        @Description("1-2 sentences capturing what happened AND where things stand now. MUST use the player character's actual name. DO NOT invent or alter character names.") String turnSummary,

        @Description("Set when a dice roll is needed. MUTUALLY EXCLUSIVE with playerChoices. "
                + "Narrate the attempt but NOT the outcome — stop and wait for the roll result. "
                + "null if no roll needed.") PendingRoll pendingRoll,

        @Description("Available choices for the player. Leave empty if a roll is pending.") List<String> playerChoices,

        @Description("just the location name, for example, \"Rusty Anchor Tavern\"") String currentLocation,

        @Description("Set to true when you have covered the content of the current adventure segment and are ready to move on. null or false means you are still working within the current segment.") Boolean segmentComplete,

        @Description("If the player makes a major decision that deviates from the written adventure, describe the deviation briefly. null means no deviation.") String majorDecision,

        @Description("A concise note about a key moment worth remembering (character revelation, major plot twist, important discovery). null means nothing noteworthy to checkpoint.") String checkpoint) {
}
