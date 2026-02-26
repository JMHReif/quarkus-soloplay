package dev.ebullient.soloplay.ai;

import jakarta.enterprise.context.RequestScoped;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ToolBox;

@RequestScoped
@RegisterAiService(retrievalAugmentor = LoreRetriever.class, chatMemoryProviderSupplier = InMemoryChatMemoryProviderSupplier.class)
public interface LoreAssistant {

    @SystemMessage(fromResource = "prompts/lore-assistant.txt")
    @ToolBox(LoreTools.class)
    @OutputGuardrails(JsonChatResponseGuardrail.class)
    JsonChatResponse lore(@UserMessage String question);

}
