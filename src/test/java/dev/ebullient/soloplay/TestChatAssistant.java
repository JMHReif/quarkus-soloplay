package dev.ebullient.soloplay;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;

import dev.ebullient.soloplay.ai.ChatAssistant;
import dev.ebullient.soloplay.ai.JsonChatResponse;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestChatAssistant implements ChatAssistant {

    @Override
    public JsonChatResponse chat(String userMessage) {
        return new JsonChatResponse("Test response", List.of());
    }
}
