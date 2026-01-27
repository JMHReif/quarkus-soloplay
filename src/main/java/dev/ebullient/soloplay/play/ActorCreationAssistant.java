package dev.ebullient.soloplay.play;

import jakarta.enterprise.context.SessionScoped;

import dev.ebullient.soloplay.ai.LoreRetriever;
import dev.ebullient.soloplay.ai.LoreTools;
import dev.ebullient.soloplay.play.model.PlayerActorDraft;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/actor-creation-system.txt")
@RegisterAiService(tools = LoreTools.class, retrievalAugmentor = LoreRetriever.class)
@SessionScoped
public interface ActorCreationAssistant {

    @UserMessage(fromResource = "prompts/actor-creation-step.txt")
    @OutputGuardrails(ActorCreationResponseGuardrail.class)
    ActorCreationResponse step(
            @MemoryId String chatMemoryId,
            String gameId,
            String adventureName,
            PlayerActorDraft currentDraft,
            String playerInput);

    @UserMessage("""
            Welcome the player to character creation.

            Introduce yourself and ask them about their character concept.
            What kind of character do they want to play?
            """)
    @OutputGuardrails(ActorCreationResponseGuardrail.class)
    ActorCreationResponse start(
            @MemoryId String chatMemoryId,
            String gameId,
            String adventureName);
}
