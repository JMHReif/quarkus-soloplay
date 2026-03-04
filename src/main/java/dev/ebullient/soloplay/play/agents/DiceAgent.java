package dev.ebullient.soloplay.play.agents;

import java.util.List;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/agent-dice-system.txt")
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@OutputGuardrails(DiceDecisionGuardrail.class)
public interface DiceAgent {

    @UserMessage("""
            === PLAYER ACTION ===
            {#if adventureName}
            Adventure: {adventureName}
            {/if}

            Location: {locationName}

            Player characters: {theParty}

            Player says: {playerInput}

            Does this action require a dice roll? If so, what kind?
            """)
    DiceDecision judge(
            String adventureName,
            List<String> theParty,
            String locationName,
            String playerInput);
}
