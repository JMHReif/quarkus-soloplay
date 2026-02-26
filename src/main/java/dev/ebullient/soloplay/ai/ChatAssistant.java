package dev.ebullient.soloplay.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@OutputGuardrails(JsonChatResponseGuardrail.class)
public interface ChatAssistant {

    @SystemMessage("""
            You are a helpful AI assistant.

            Be conversational and friendly. Provide clear, concise answers.
            When uncertain, say so rather than guessing.
            """)
    JsonChatResponse chat(@UserMessage String userMessage);

}
