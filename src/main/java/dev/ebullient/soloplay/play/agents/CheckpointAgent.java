package dev.ebullient.soloplay.play.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/agent-checkpoint-system.txt")
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface CheckpointAgent {

    @UserMessage("""
            === NARRATION ===
            {narration}
            {#if adventureContext}

            === ADVENTURE CONTEXT ===
            {adventureContext}
            {/if}
            {#if playerInput}

            === PLAYER ACTION ===
            {playerInput}
            {/if}

            Evaluate this narration for checkpoint-worthy moments and adventure progression.
            """)
    CheckpointDecision evaluate(
            String narration,
            String adventureContext,
            String playerInput);
}
