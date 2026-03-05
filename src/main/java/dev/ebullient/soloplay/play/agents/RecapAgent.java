package dev.ebullient.soloplay.play.agents;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@ApplicationScoped
@SystemMessage(fromResource = "prompts/agent-recap-system.txt")
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface RecapAgent {

    @UserMessage("""
            === NARRATION ===
            {narration}

            === PLAYER CHARACTERS ===
            {theParty}

            === CURRENT LOCATION ===
            {locationName}

            Summarize what just happened in 1-2 sentences.
            """)
    String summarize(
            String narration,
            List<String> theParty,
            String locationName);
}
