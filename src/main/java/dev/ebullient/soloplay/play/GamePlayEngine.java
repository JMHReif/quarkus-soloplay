package dev.ebullient.soloplay.play;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.GameEffect.HtmlFragment;
import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.BaseEntity;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.Location;
import dev.ebullient.soloplay.play.model.Patch;
import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.ebullient.soloplay.play.model.PlayerChoices;
import dev.ebullient.soloplay.play.model.RollResult;
import io.quarkus.logging.Log;

@ApplicationScoped
public class GamePlayEngine {

    @Inject
    GameRepository gameRepository;

    @Inject
    GamePlayAssistant assistant;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RollHandler rollHandler;

    public GameResponse sceneStart(GameState game, GameEventEmitter emitter) {
        emitter.assistantDelta("Setting the scene…\n");

        try {
            // Initialize adventure segment tracking if this game has an adventure
            String adventureContext = null;
            if (game.getAdventureName() != null) {
                Log.infof("sceneStart: adventure=%s, looking for first segment", game.getAdventureName());
                var firstSegment = gameRepository.findFirstAdventureSegment(game.getAdventureName());
                if (firstSegment != null) {
                    String docId = (String) firstSegment.get("id");
                    Log.infof("sceneStart: found first segment docId=%s", docId);
                    gameRepository.initCurrentStep(game.getGameId(), docId);
                    adventureContext = fetchAdventureContext(game);
                    Log.infof("sceneStart: adventureContext length=%d",
                            adventureContext != null ? adventureContext.length() : 0);
                } else {
                    Log.warnf("sceneStart: no adventure segments found for '%s'", game.getAdventureName());
                }
            } else {
                Log.infof("sceneStart: no adventure set (sandbox mode)");
            }

            var response = assistant.sceneStart(
                    game.getGameId(),
                    game.getAdventureName(),
                    listTheParty(game),
                    adventureContext,
                    formatJournal(game));

            return processResponse(game, response, emitter);
        } catch (Exception e) {
            return handleAssistantError(e);
        }
    }

    public GameResponse recap(GameState game, String recentEvents, GameEventEmitter emitter) {
        emitter.assistantDelta("Recapping the story…\n");

        try {
            String adventureContext = fetchAdventureContext(game);

            var response = assistant.recap(
                    game.getGameId(),
                    game.getAdventureName(),
                    listTheParty(game),
                    formatLocationContext(game),
                    recentEvents,
                    adventureContext,
                    formatJournal(game));

            return processResponse(game, response, emitter);
        } catch (Exception e) {
            return handleAssistantError(e);
        }
    }

    public GameResponse processRequest(GameState game, String playerInput, GameEventEmitter emitter) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(emitter, "emitter");

        String trimmed = playerInput == null ? "" : playerInput.trim();

        try {
            // Check for pending roll resolution
            PendingRoll pendingRoll = rollHandler.getPendingRoll(game);
            if (pendingRoll != null && isRollInput(trimmed)) {
                return resolveRoll(game, pendingRoll, trimmed, emitter);
            }

            // Standard turn
            return handleTurn(game, trimmed, emitter);
        } catch (Exception e) {
            return handleAssistantError(e);
        }
    }

    private GameResponse handleTurn(GameState game, String playerInput, GameEventEmitter emitter) {
        emitter.assistantDelta("The GM is thinking…\n");

        String adventureContext = fetchAdventureContext(game);
        Log.debugf("handleTurn: adventureContext=%s",
                adventureContext != null ? "present (" + adventureContext.length() + " chars)" : "null");

        var response = assistant.turn(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game),
                formatLocationContext(game),
                playerInput,
                adventureContext);

        return processResponse(game, response, emitter);
    }

    private GameResponse resolveRoll(GameState game, PendingRoll pending, String rollInput,
            GameEventEmitter emitter) {
        emitter.assistantDelta("Processing roll…\n");

        RollResult rollResult = rollHandler.handleRollCommand(game, rollInput);
        if (rollResult == null) {
            return GameResponse.error("Could not parse roll input: " + rollInput);
        }

        rollHandler.clearPendingRoll(game);

        // Send the roll result to chat immediately so player sees their roll
        var rollResultEffect = new GameEffect.HtmlFragment("roll_result", rollResult.render());

        String adventureContext = fetchAdventureContext(game);

        var response = assistant.resolveRoll(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game),
                formatLocationContext(game),
                rollResult,
                adventureContext);

        return processResponse(game, response, emitter, rollResultEffect);
    }

    private GameResponse processResponse(GameState game, GamePlayResponse response, GameEventEmitter emitter) {
        return processResponse(game, response, emitter, null);
    }

    private GameResponse processResponse(GameState game, GamePlayResponse response, GameEventEmitter emitter,
            GameEffect additionalEffect) {
        if (response == null || response.narration() == null) {
            return GameResponse.error("No response from GM");
        }

        game.setCurrentLocation(response.currentLocation());
        game.setLastNarration(response.narration());

        // Handle adventure segment progression
        if (Boolean.TRUE.equals(response.segmentComplete())) {
            gameRepository.advanceAdventureSegment(game.getGameId(), game.getTurnNumber());
        }
        if (response.majorDecision() != null && !response.majorDecision().isBlank()) {
            gameRepository.recordDecision(game.getGameId(), game.getTurnNumber(), response.majorDecision());
        }

        // Save checkpoint if the LLM flagged a key moment
        if (response.checkpoint() != null && !response.checkpoint().isBlank()) {
            gameRepository.saveCheckpoint(game.getGameId(), "milestone", response.checkpoint(), game.getTurnNumber());
        }

        // Apply patches (actors, locations, plot flags)
        emitter.assistantDelta("Updating world state…\n");
        patchesAndEvents(game, response);

        // Store pending roll if present
        emitter.assistantDelta("Checking for pending roll…\n");
        var pendingRollEffect = storePendingRoll(game, response.pendingRoll());

        // Collect all effects
        java.util.List<GameEffect> effects = new java.util.ArrayList<>();
        if (additionalEffect != null) {
            effects.add(additionalEffect);
        }
        if (pendingRollEffect != null) {
            effects.add(pendingRollEffect);
        }
        if (response.playerChoices() != null && !response.playerChoices().isEmpty()) {
            String html = new PlayerChoices(response.playerChoices()).render();
            effects.add(new GameEffect.HtmlFragment("player_choices", html));
        }

        return effects.isEmpty()
                ? GameResponse.reply(response.narration())
                : GameResponse.reply(response.narration(), effects.toArray(new GameEffect[0]));
    }

    private GameResponse handleAssistantError(Exception e) {
        Log.errorf(e, "Assistant call failed: %s", e.getMessage());
        return GameResponse.error(
                "The GM lost their train of thought. Please try again or rephrase your action.");
    }

    private HtmlFragment storePendingRoll(GameState game, PendingRoll roll) {
        return rollHandler.setPendingRoll(game, roll)
                .orElse(null);
    }

    private boolean isRollInput(String input) {
        // e.g., "/roll", "1d20+5", "15", etc.
        return input.startsWith("/roll") || input.matches("\\d+");
    }

    /**
     * Load non-character checkpoints for a game and format as campaign notes.
     * Character details are already in the party list — only milestones go here.
     * Returns null if there are no notes.
     */
    String formatJournal(GameState game) {
        var checkpoints = gameRepository.getCheckpoints(game.getGameId());
        if (checkpoints.isEmpty()) {
            return null;
        }

        StringBuilder notes = new StringBuilder();
        for (var cp : checkpoints) {
            String category = (String) cp.get("category");
            if ("character".equals(category)) {
                continue; // character details are in the party list
            }
            String content = (String) cp.get("content");
            notes.append("- ").append(content).append("\n");
        }

        return notes.isEmpty() ? null : notes.toString().trim();
    }

    /**
     * Fetch adventure context from the current GameSegment's linked Document.
     * Returns formatted current segment + next segment so the LLM can steer the story.
     */
    String fetchAdventureContext(GameState game) {
        Map<String, Object> ctx = gameRepository.getCurrentAdventureContext(game.getGameId());
        if (ctx == null || ctx.get("currentText") == null) {
            Log.debugf("fetchAdventureContext: no context found for game %s (ctx=%s)",
                    game.getGameId(), ctx == null ? "null" : "missing currentText");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- CURRENT SEGMENT (play this now) ---\n");
        if (ctx.get("chapterName") != null) {
            sb.append("Chapter: ").append(ctx.get("chapterName")).append("\n");
        }
        if (ctx.get("section") != null) {
            sb.append("Section: ").append(ctx.get("section")).append("\n");
        }
        sb.append("\n").append(ctx.get("currentText"));

        if (ctx.get("nextText") != null) {
            sb.append("\n\n--- NEXT SEGMENT (steer the story toward this) ---\n");
            if (ctx.get("nextChapterName") != null) {
                sb.append("Chapter: ").append(ctx.get("nextChapterName")).append("\n");
            }
            if (ctx.get("nextSection") != null) {
                sb.append("Section: ").append(ctx.get("nextSection")).append("\n");
            }
            // Cap next segment to avoid overflowing the model's context window
            String nextText = ctx.get("nextText").toString();
            if (nextText.length() > 600) {
                nextText = nextText.substring(0, 600) + "…";
            }
            sb.append("\n").append(nextText);
        } else {
            sb.append("\n\n[This is the final segment of the adventure.]");
        }

        return sb.toString();
    }

    private void patchesAndEvents(GameState game, GamePlayResponse response) {
        Set<BaseEntity> modified = new HashSet<>();
        Set<Actor> actors = new HashSet<>();
        Set<Location> locations = new HashSet<>();

        if (response.patches() != null) {
            for (Patch patch : response.patches()) {
                switch (patch.type()) {
                    case "actor" -> {
                        var merged = handleActor(game, patch);
                        actors.add(merged);
                    }
                    case "location" -> {
                        var merged = handleLocation(game, patch);
                        locations.add(merged);
                    }
                }
            }
        }

        if (response.actorsPresent() != null) {
            for (var actorName : response.actorsPresent()) {
                var actor = gameRepository.findActorByNameOrAlias(game.getGameId(), actorName);
                if (actor != null) {
                    actors.add(actor);
                }
            }
        }
        if (response.locationsPresent() != null) {
            for (var locationName : response.locationsPresent()) {
                var location = gameRepository.findLocationByNameOrAlias(game.getGameId(), locationName);
                if (location != null) {
                    locations.add(location);
                }
            }
        }

        // Save turn summary as event for recaps
        if (response.turnSummary() != null && !response.turnSummary().isBlank()) {
            Event event = new Event(game.getGameId(), game.getTurnNumber(), response.turnSummary());
            event.addParticipants(actors);
            event.addLocations(locations);
            modified.add(event);
        }

        modified.addAll(actors);
        modified.addAll(locations);

        gameRepository.saveAll(modified); // single TX
        gameRepository.linkEntitiesToGame(game.getGameId(), modified);
    }

    Actor handleActor(GameState game, Patch p) {
        var actor = gameRepository.findActorByNameOrAlias(game.getGameId(), p.name());
        if (actor == null) {
            return new Actor(game.getGameId(), p);
        }
        if (actor instanceof PlayerActor playerActor) {
            // preserve extra player actor attributes
            return playerActor.merge(p);
        }
        return actor.merge(p);
    }

    Location handleLocation(GameState game, Patch p) {
        var location = gameRepository.findLocationByNameOrAlias(game.getGameId(), p.name());
        if (location == null) {
            return new Location(game.getGameId(), p);
        }
        return location.merge(p);
    }

    List<String> listTheParty(GameState game) {
        return gameRepository.findTheParty(game.getGameId())
                .stream()
                .map(this::formatPartyMember)
                .toList();
    }

    private String formatPartyMember(Actor actor) {
        if (actor instanceof PlayerActor pa) {
            return PlayerActor.Templates.playerActorSummary(pa).render();
        }
        return Actor.Templates.actorSummary(actor).render();
    }

    private String formatLocationContext(GameState game) {
        String locName = game.getCurrentLocation();
        if (locName == null || locName.isBlank()) {
            return "Unknown";
        }
        Location loc = gameRepository.findLocationByNameOrAlias(game.getGameId(), locName);
        if (loc != null && loc.getSummary() != null && !loc.getSummary().isBlank()) {
            return locName + " -- " + loc.getSummary();
        }
        return locName;
    }
}
