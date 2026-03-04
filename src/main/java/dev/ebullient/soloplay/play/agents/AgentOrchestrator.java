package dev.ebullient.soloplay.play.agents;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.ebullient.soloplay.play.GamePlayResponse;
import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.ebullient.soloplay.play.model.RollResult;
import io.quarkus.logging.Log;

/**
 * Coordinates specialized agents to produce a GamePlayResponse.
 * Replaces the monolithic GamePlayAssistant with focused agent calls.
 */
@ApplicationScoped
public class AgentOrchestrator {

    @Inject
    NarrationAgent narrationAgent;

    @Inject
    DiceAgent diceAgent;

    @Inject
    SuggestionAgent suggestionAgent;

    @Inject
    CheckpointAgent checkpointAgent;

    @Inject
    RecapAgent recapAgent;

    /**
     * Scene start: first scene of an adventure or sandbox.
     */
    public GamePlayResponse sceneStart(
            String gameId,
            String adventureName,
            List<String> theParty,
            String adventureContext,
            String gameJournal) {

        Log.infof("AgentOrchestrator.sceneStart: gameId=%s", gameId);

        // 1. Narration
        NarrationResponse narration = narrationAgent.sceneStart(
                gameId, adventureName, theParty, adventureContext, gameJournal);
        Log.debugf("sceneStart narration: %s", narration);

        String location = narration.currentLocation();

        // 2. Sub-agents run concurrently
        var suggestF = CompletableFuture
                .supplyAsync(() -> callSuggestionAgent(narration.narration(), location, theParty, null, adventureContext));
        var checkpointF = CompletableFuture
                .supplyAsync(() -> callCheckpointAgent(narration.narration(), adventureContext, null));
        var recapF = CompletableFuture.supplyAsync(() -> callRecapAgent(narration.narration(), theParty, location));

        return assembleResponse(narration, null, suggestF.join(), checkpointF.join(), recapF.join());
    }

    /**
     * Recap: resuming a session.
     */
    public GamePlayResponse recap(
            String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String recentEvents,
            String adventureContext,
            String gameJournal) {

        Log.infof("AgentOrchestrator.recap: gameId=%s", gameId);

        // 1. Narration
        NarrationResponse narration = narrationAgent.recap(
                gameId, adventureName, theParty, locationName, recentEvents, adventureContext, gameJournal);
        Log.debugf("recap narration: %s", narration);

        String location = narration.currentLocation() != null ? narration.currentLocation() : locationName;

        // 2. Suggestions only (skip checkpoint and recap for session resume)
        List<String> suggestions = callSuggestionAgent(
                narration.narration(), location, theParty, null, adventureContext);

        return assembleResponse(narration, null, suggestions, null, null);
    }

    /**
     * Standard turn: player action with dice decision.
     */
    public GamePlayResponse turn(
            String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            String playerInput,
            String adventureContext,
            String gameJournal) {

        Log.infof("AgentOrchestrator.turn: gameId=%s, playerInput=%s", gameId, playerInput);

        // 1. Dice decision
        DiceDecision diceDecision = diceAgent.judge(adventureName, theParty, locationName, playerInput);
        Log.debugf("turn diceDecision: %s", diceDecision);

        // 2. Narration (informed by dice decision)
        NarrationResponse narration = narrationAgent.turn(
                gameId, adventureName, theParty, locationName, playerInput,
                adventureContext, gameJournal,
                diceDecision.rollNeeded(),
                diceDecision.rollNeeded() ? diceDecision.context() : null);
        Log.debugf("turn narration: %s", narration);

        String location = narration.currentLocation() != null ? narration.currentLocation() : locationName;

        // 3. Sub-agents run concurrently. Suggestions skipped when a roll is pending.
        PendingRoll pendingRoll = null;
        CompletableFuture<List<String>> suggestF;
        if (diceDecision.rollNeeded()) {
            pendingRoll = new PendingRoll(
                    diceDecision.type(),
                    diceDecision.skill(),
                    diceDecision.ability(),
                    diceDecision.dc(),
                    diceDecision.target(),
                    diceDecision.context());
            suggestF = CompletableFuture.completedFuture(null);
        } else {
            suggestF = CompletableFuture.supplyAsync(
                    () -> callSuggestionAgent(narration.narration(), location, theParty, playerInput, adventureContext));
        }

        var checkpointF = CompletableFuture
                .supplyAsync(() -> callCheckpointAgent(narration.narration(), adventureContext, playerInput));
        var recapF = CompletableFuture.supplyAsync(() -> callRecapAgent(narration.narration(), theParty, location));

        return assembleResponse(narration, pendingRoll, suggestF.join(), checkpointF.join(), recapF.join());
    }

    /**
     * Roll resolution: after the player rolls dice.
     */
    public GamePlayResponse resolveRoll(
            String gameId,
            String adventureName,
            List<String> theParty,
            String locationName,
            RollResult rollResult,
            String adventureContext,
            String gameJournal) {

        Log.infof("AgentOrchestrator.resolveRoll: gameId=%s, result=%s", gameId, rollResult);

        // 1. Narration (resolve the roll outcome)
        NarrationResponse narration = narrationAgent.resolveRoll(
                gameId, adventureName, theParty, locationName, rollResult, adventureContext, gameJournal);
        Log.debugf("resolveRoll narration: %s", narration);

        String location = narration.currentLocation() != null ? narration.currentLocation() : locationName;

        // 2. Sub-agents run concurrently
        var suggestF = CompletableFuture
                .supplyAsync(() -> callSuggestionAgent(narration.narration(), location, theParty, null, adventureContext));
        var checkpointF = CompletableFuture
                .supplyAsync(() -> callCheckpointAgent(narration.narration(), adventureContext, null));
        var recapF = CompletableFuture.supplyAsync(() -> callRecapAgent(narration.narration(), theParty, location));

        return assembleResponse(narration, null, suggestF.join(), checkpointF.join(), recapF.join());
    }

    // --- Helper methods ---

    private List<String> callSuggestionAgent(String narrationText, String location, List<String> theParty,
            String playerInput, String adventureContext) {
        try {
            SuggestionResponse response = suggestionAgent.suggest(
                    narrationText, location, theParty, playerInput, adventureContext);
            Log.debugf("suggestions: %s", response);
            return response != null && response.suggestions() != null
                    ? response.suggestions()
                    : List.of("Look around", "Ask a question", "Move on");
        } catch (Exception e) {
            Log.warnf(e, "SuggestionAgent failed, returning defaults");
            return List.of("Look around", "Ask a question", "Move on");
        }
    }

    private CheckpointDecision callCheckpointAgent(String narrationText, String adventureContext,
            String playerInput) {
        try {
            CheckpointDecision decision = checkpointAgent.evaluate(narrationText, adventureContext, playerInput);
            Log.debugf("checkpoint: %s", decision);
            return decision;
        } catch (Exception e) {
            Log.warnf(e, "CheckpointAgent failed, returning empty");
            return new CheckpointDecision(null, null, null);
        }
    }

    private String callRecapAgent(String narrationText, List<String> theParty, String location) {
        try {
            String summary = recapAgent.summarize(narrationText, theParty, location);
            Log.debugf("turnSummary: %s", summary);
            return summary;
        } catch (Exception e) {
            Log.warnf(e, "RecapAgent failed, returning null");
            return null;
        }
    }

    /**
     * Assemble a GamePlayResponse from individual agent outputs.
     */
    private GamePlayResponse assembleResponse(
            NarrationResponse narration,
            PendingRoll pendingRoll,
            List<String> playerChoices,
            CheckpointDecision checkpoint,
            String turnSummary) {

        return new GamePlayResponse(
                narration.reasoning(),
                narration.narration(),
                turnSummary,
                pendingRoll,
                playerChoices,
                narration.currentLocation(),
                checkpoint != null ? checkpoint.segmentComplete() : null,
                checkpoint != null ? checkpoint.majorDecision() : null,
                checkpoint != null ? checkpoint.checkpoint() : null);
    }
}
