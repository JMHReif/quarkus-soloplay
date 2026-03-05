package dev.ebullient.soloplay.play.agents;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@ApplicationScoped
@SystemMessage(fromResource = "prompts/agent-suggestion-system.txt")
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface SuggestionAgent {

    @UserMessage("""
            {#if playerInput}
            === WHAT THE PLAYER JUST DID ===
            {playerInput}

            {/if}
            === GM NARRATION ===
            {narration}

            === CURRENT LOCATION ===
            {locationName}

            === PLAYER CHARACTERS ===
            {theParty}
            {#if adventureContext}

            === ADVENTURE CONTEXT ===
            {adventureContext}
            {/if}

            Based on the narration above, generate 2-3 concrete next actions.
            Reference specific names, objects, or events from the narration.
            """)
    SuggestionResponse suggest(
            String narration,
            String locationName,
            List<String> theParty,
            String playerInput,
            String adventureContext);
}
