package dev.ebullient.soloplay.play.agents;

import java.util.List;

import jakarta.enterprise.context.SessionScoped;

import dev.ebullient.soloplay.ai.LoreTools;
import dev.ebullient.soloplay.play.GameTools;
import dev.ebullient.soloplay.play.model.RollResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/agent-narration-system.txt")
@RegisterAiService(tools = { LoreTools.class, GameTools.class })
@OutputGuardrails(NarrationResponseGuardrail.class)
@SessionScoped
public interface NarrationAgent {

    @UserMessage(fromResource = "prompts/agent-narration-scene-start.txt")
    NarrationResponse sceneStart(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String adventureContext,
            String gameJournal);

    @UserMessage(fromResource = "prompts/agent-narration-recap.txt")
    NarrationResponse recap(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String recentEvents,
            String adventureContext,
            String gameJournal);

    @UserMessage(fromResource = "prompts/agent-narration-turn.txt")
    NarrationResponse turn(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String playerInput,
            String adventureContext,
            String gameJournal,
            boolean rollNeeded,
            String diceContext);

    @UserMessage(fromResource = "prompts/agent-narration-roll.txt")
    NarrationResponse resolveRoll(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            RollResult rollResult,
            String adventureContext,
            String gameJournal);
}
