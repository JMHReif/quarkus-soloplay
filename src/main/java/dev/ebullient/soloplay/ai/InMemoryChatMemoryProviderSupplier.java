package dev.ebullient.soloplay.ai;

import java.util.function.Supplier;

import org.eclipse.microprofile.config.ConfigProvider;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

/**
 * Provides in-memory chat memory for AI services that need tool calling
 * but don't require persistent conversation history.
 */
public class InMemoryChatMemoryProviderSupplier implements Supplier<ChatMemoryProvider> {

    @Override
    public ChatMemoryProvider get() {
        int maxMessages = ConfigProvider.getConfig()
                .getOptionalValue("campaign.lore.max-messages", Integer.class)
                .orElse(5);

        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .build();
    }
}
