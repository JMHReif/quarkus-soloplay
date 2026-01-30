package dev.ebullient.soloplay.ai;

import java.util.List;

import dev.langchain4j.model.output.structured.Description;

public record JsonChatResponse(
        @Description("Your answer in markdown format") String response,
        @Description("List of source filenames referenced (e.g., 'locations/tavern.md')") List<String> sources) {
}
