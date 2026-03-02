package dev.ebullient.soloplay.play;

import java.util.List;

import jakarta.enterprise.context.SessionScoped;

import dev.ebullient.soloplay.ai.LoreTools;
import dev.ebullient.soloplay.play.model.RollResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/game-play-system.txt")
@RegisterAiService(tools = { LoreTools.class, GameTools.class })
@OutputGuardrails(GamePlayResponseGuardrail.class)
@SessionScoped
public interface GamePlayAssistant {

    // --- Scene Start: First scene of the adventure ---

    @UserMessage(fromResource = "prompts/game-play-scene-start.txt")
    GamePlayResponse sceneStart(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String adventureContext,
            String gameJournal);

    // --- Recap: Resuming a session ---

    @UserMessage(fromResource = "prompts/game-play-recap.txt")
    GamePlayResponse recap(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String recentEvents, // formatted chat history
            String adventureContext,
            String gameJournal);

    // --- Standard Turn: Player action (no pending roll) ---

    @UserMessage(fromResource = "prompts/game-play-turn.txt")
    GamePlayResponse turn(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String playerInput,
            String adventureContext,
            String gameJournal);

    // --- Roll Resolution: Player completed a roll ---

    @UserMessage(fromResource = "prompts/game-play-roll.txt")
    GamePlayResponse resolveRoll(
            @MemoryId String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            RollResult rollResult,
            String adventureContext,
            String gameJournal);
}
