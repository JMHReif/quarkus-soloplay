package dev.ebullient.soloplay.play.agents;

import java.util.List;

import dev.langchain4j.model.output.structured.Description;

public record SuggestionResponse(
        @Description("2-3 concrete action suggestions. Each should reference specific NPCs, objects, "
                + "or events from the narration. Start each with a verb.") List<String> suggestions) {
}
