package dev.ebullient.soloplay.play;

import java.util.List;

import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.langchain4j.model.output.structured.Description;

public record GamePlayNarration(
        @Description("FIRST: Think step by step before narrating. "
                + "Cover: player intent, what happens next, is a roll needed, "
                + "NPCs/locations present or introduced, world state changes, "
                + "segment completion status, major deviations, checkpoint-worthy moments.") String reasoning,

        @Description("Story prose in markdown. ONLY narrative — no field labels or mechanics. "
                + "Vivid descriptions, dialogue, sensory details. "
                + "If location changed, narrate the transition. "
                + "MUST end with a clear hook or call to action.") String narration,

        @Description("Set when a dice roll is needed. MUTUALLY EXCLUSIVE with playerChoices. "
                + "Narrate the attempt but NOT the outcome — stop and wait for the roll result. "
                + "null if no roll needed.") PendingRoll pendingRoll,

        @Description("2-3 short, action-oriented suggestions. MUTUALLY EXCLUSIVE with pendingRoll. "
                + "REQUIRED unless a roll is pending (empty [] if roll is pending). "
                + "Player can always type their own action instead.") List<String> playerChoices) {
}
