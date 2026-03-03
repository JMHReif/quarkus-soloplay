package dev.ebullient.soloplay.play.agents;

import dev.langchain4j.model.output.structured.Description;

public record CheckpointDecision(
        @Description("A concise note about a key moment worth remembering "
                + "(character revelation, major plot twist, important discovery). "
                + "null means nothing noteworthy to checkpoint.") String checkpoint,

        @Description("true when the current adventure segment content has been covered "
                + "and it is time to move on. null or false means still working within "
                + "the current segment.") Boolean segmentComplete,

        @Description("If the player made a major decision that deviates from the written adventure, "
                + "describe the deviation briefly. null means no deviation.") String majorDecision) {
}
