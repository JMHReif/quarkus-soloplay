package dev.ebullient.soloplay.play.agents;

import dev.langchain4j.model.output.structured.Description;

public record NarrationResponse(
        @Description("Think step by step before narrating. "
                + "Cover: player intent, what happens next, segment completion status, "
                + "major deviations, checkpoint-worthy moments.") String reasoning,

        @Description("Story prose in markdown. ONLY narrative — no field labels or mechanics. "
                + "Vivid descriptions, dialogue, sensory details. "
                + "If location changed, narrate the transition. "
                + "MUST end with a clear hook or call to action. "
                + "KEEP UNDER 150 WORDS.") String narration,

        @Description("Just the location name, for example, \"Rusty Anchor Tavern\"") String currentLocation) {
}
