package dev.ebullient.soloplay.play;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.GameEffect.StatefulEffect;
import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.BaseEntity;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.Location;
import dev.ebullient.soloplay.play.model.Patch;
import dev.ebullient.soloplay.play.model.PendingRollStash;
import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.ebullient.soloplay.play.model.PlayerChoices;
import dev.ebullient.soloplay.play.model.RollResult;
import dev.ebullient.soloplay.play.model.Stash;

@ApplicationScoped
public class GamePlayEngine {
    static final String EVENT_STASH = "prev_event";

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

        var response = assistant.sceneStart(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game));

        return processResponse(game, response, emitter);
    }

    public GameResponse recap(GameState game, String recentEvents, GameEventEmitter emitter) {
        emitter.assistantDelta("Recapping the story…\n");

        var response = assistant.recap(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game),
                game.getCurrentLocation(),
                recentEvents);

        return processResponse(game, response, emitter);
    }

    /**
     * Process a player request using client-provided stash for stateless operation.
     *
     * @param game The game state
     * @param playerInput The player's input
     * @param clientStash The stash round-tripped from the client (may be PendingRoll, etc.)
     * @param emitter Event emitter for streaming responses
     * @return The game response with effects (including any new StatefulEffect for round-trip)
     */
    public GameResponse processRequest(GameState game, String playerInput, Stash clientStash,
            GameEventEmitter emitter) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(emitter, "emitter");

        String trimmed = playerInput == null ? "" : playerInput.trim();

        // Check for pending roll resolution (from client-provided stash)
        if (clientStash instanceof PendingRollStash pendingRollStash && rollHandler.isRollInput(trimmed)) {
            return resolveRoll(game, pendingRollStash, trimmed, emitter);
        }

        // Standard turn
        return handleTurn(game, trimmed, emitter);
    }

    private GameResponse handleTurn(GameState game, String playerInput, GameEventEmitter emitter) {
        emitter.assistantDelta("The GM is thinking…\n");

        // TODO: gather context for the turn
        var response = assistant.turn(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game),
                game.getCurrentLocation(),
                game.getStash(EVENT_STASH, Event.class),
                playerInput);

        return processResponse(game, response, emitter);
    }

    private GameResponse resolveRoll(GameState game, PendingRollStash pendingStash, String rollInput,
            GameEventEmitter emitter) {
        emitter.assistantDelta("Processing roll…\n");

        RollResult rollResult = rollHandler.handleRollCommand(pendingStash, rollInput);
        if (rollResult == null) {
            return GameResponse.error("Could not parse roll input: " + rollInput);
        }

        // Roll is resolved - clear the pending roll by sending a clear effect
        var clearEffect = rollHandler.clearPendingRollEffect();

        var response = assistant.resolveRoll(
                game.getGameId(),
                game.getAdventureName(),
                listTheParty(game),
                game.getCurrentLocation(),
                game.getStash(EVENT_STASH, Event.class),
                rollResult);

        return processResponse(game, response, emitter, clearEffect);
    }

    private GameResponse processResponse(GameState game, GamePlayResponse response, GameEventEmitter emitter) {
        return processResponse(game, response, emitter, null);
    }

    private GameResponse processResponse(GameState game, GamePlayResponse response, GameEventEmitter emitter,
            StatefulEffect additionalEffect) {
        if (response == null || response.narration() == null) {
            return GameResponse.error("No response from GM");
        }

        game.setCurrentLocation(response.currentLocation());

        // Apply patches (actors, locations, plot flags)
        emitter.assistantDelta("Updating world state…\n");
        patchesAndEvents(game, response);

        // Create pending roll effect if present (for round-trip through client)
        var pendingRollEffect = rollHandler.createPendingRollEffect(response.pendingRoll());

        // Build response with effects
        var reply = GameResponse.reply(response.narration());

        if (pendingRollEffect != null) {
            reply = reply.withEffects(pendingRollEffect);
        }
        if (response.playerChoices() != null && !response.playerChoices().isEmpty()) {
            String html = new PlayerChoices(response.playerChoices()).render();
            reply = reply.withEffects(new GameEffect.HtmlFragment("player_choices", html));
        }
        if (additionalEffect != null) {
            reply = reply.withEffects(additionalEffect);
        }

        return reply;
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
            game.putStash(EVENT_STASH, event);
        }

        modified.addAll(actors);
        modified.addAll(locations);

        gameRepository.saveAll(modified); // single TX
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
        StringBuilder sb = new StringBuilder();
        sb.append(actor.getName());

        if (actor instanceof PlayerActor pa) {
            if (pa.getActorClass() != null) {
                sb.append(" (");
                if (pa.getLevel() != null) {
                    sb.append("Level ").append(pa.getLevel()).append(" ");
                }
                sb.append(pa.getActorClass()).append(")");
            }
        }

        if (actor.getSummary() != null && !actor.getSummary().isBlank()) {
            sb.append(": ").append(actor.getSummary());
        }

        return sb.toString();
    }
}
