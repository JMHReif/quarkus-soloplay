package dev.ebullient.soloplay.play;

<<<<<<< Updated upstream
import dev.ebullient.soloplay.play.model.PlayerActorCreationPatch;
import dev.langchain4j.model.output.structured.Description;

public record ActorCreationResponse(
        @Description("Text response to the player in markdown format") String message,
        @Description("Updated character attributes; null or empty means no updates") PlayerActorCreationPatch patch) {
=======
import java.util.List;

public record ActorCreationResponse(String messageMarkdown, CharacterPatch patch) {

    public record CharacterPatch(
            String name,
            String actorClass,
            Integer level,
            String summary,
            String description,
            List<String> tags,
            List<String> aliases) {
    }
>>>>>>> Stashed changes
}
