package dev.ebullient.soloplay.play;

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
}
