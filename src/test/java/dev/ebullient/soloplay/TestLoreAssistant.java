package dev.ebullient.soloplay;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;

import dev.ebullient.soloplay.ai.JsonChatResponse;
import dev.ebullient.soloplay.ai.LoreAssistant;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestLoreAssistant implements LoreAssistant {

    @Override
    public JsonChatResponse lore(String question) {
        return new JsonChatResponse("Test lore response", List.of());
    }
}
