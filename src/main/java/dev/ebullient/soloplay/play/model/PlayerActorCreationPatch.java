package dev.ebullient.soloplay.play.model;

import java.util.List;

import dev.langchain4j.model.output.structured.Description;

public record PlayerActorCreationPatch(
        @Description("Character name") String name,
        @Description("Class") String actorClass,
        @Description("Level; default to adventure recommendation or 1") Integer level,
        @Description("Brief 5-10 word description") String summary,
        @Description("Longer description; can be brief initially, will evolve during play") String description,
        @Description("Tags for additional information: race, background, alignment") List<String> tags,
        @Description("Alternate names this character uses") List<String> aliases,
        @Description("A short explanation of the change") String rationale,
        @Description("list of lore document filenames used. If you did not use lore docs or tools, sources = []. Don't invent filenames.") List<String> sources) {
}
