package dev.ebullient.soloplay.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.GameState.GamePhase;
import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.ebullient.soloplay.play.model.Stash;
import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@ApplicationScoped
public class GameEngine {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance gameStatus(GameState game, Long lastPlayed, List<String> partyMembers);
    }

    @Inject
    GameRepository gameRepository;

    @Inject
    ActorCreationEngine actorCreationEngine;

    @Inject
    RollHandler rollHandler;

    @Inject
    GamePlayEngine gamePlayEngine;

    @Inject
    GameContext gameContext;

    public GameState getGameState(String gameId) {
        return gameRepository.findGameById(gameId);
    }

    public GameResponse processRequest(GameState game, String playerInput, Stash clientDraft,
            GameEventEmitter emitter, boolean resuming) {
        Objects.requireNonNull(emitter, "emitter");
        gameContext.setGameState(game, gamePlayEngine.listTheParty(game));

        GamePhase phase = game.getGamePhase();
        Log.debugf("Resuming game phase: %s", phase);

        String trimmed = playerInput == null ? "" : playerInput.trim();

        // RECOVERY: normalize UNKNOWN into a concrete phase
        if (phase == GamePhase.UNKNOWN) {
            if (gameRepository.hasProtagonists(game.getGameId())) {
                Log.infof("Recovering game %s from UNKNOWN to ACTIVE_PLAY (protagonists exist)", game.getGameId());
                game.setGamePhase(GamePhase.ACTIVE_PLAY);
                phase = GamePhase.ACTIVE_PLAY;
            } else {
                Log.infof("Recovering game %s from UNKNOWN to CHARACTER_CREATION (no protagonists)", game.getGameId());
                game.setGamePhase(GamePhase.CHARACTER_CREATION);
                phase = GamePhase.CHARACTER_CREATION;
            }
        }

        boolean createActors = phase == GamePhase.CHARACTER_CREATION || "/newcharacter".equals(trimmed);
        if (isStatusCommand(trimmed)) {
            return handleStatusCommand(game);
        }
        if (isHelpCommand(trimmed)) {
            return handleHelpCommand(game, createActors);
        }

        final GameResponse response;
        if (createActors) {
            response = actorCreationEngine.processRequest(game, playerInput, clientDraft, emitter);
            gameRepository.refreshTheParty(game.getGameId());
        } else if (game.getGamePhase() == GamePhase.SCENE_INITIALIZATION || resuming) {
            // Check for existing events to decide: recap or fresh start
            List<Event> events = gameRepository.listEvents(game.getGameId());
            if (events.isEmpty()) {
                // New game - start the opening scene
                response = gamePlayEngine.sceneStart(game, emitter);
                game.incrementTurn();
            } else {
                // Resuming - build recap from event summaries
                String recentEvents = formatRecentEvents(events);
                response = gamePlayEngine.recap(game, recentEvents, emitter);
            }
            game.setGamePhase(game.getGamePhase().next());
        } else {
            response = gamePlayEngine.processRequest(game, playerInput, clientDraft, emitter);
            game.incrementTurn();
        }

        gameRepository.saveGame(game);
        return response;
    }

    GameResponse handleHelpCommand(GameState game, boolean createActors) {
        // Help responses do not change game state
        if (createActors) {
            return actorCreationEngine.help(game);
        }
        return GameResponse.reply("""
                Available commands:

                - `/newcharacter`: create new player character
                - `/roll`: roll dice or enter roll result
                - `/start`: start or resume play
                - `/status`: show game state information
                - `/help` (or `help`, `?`): show commands
                """);
    }

    static boolean isHelpCommand(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return input.equalsIgnoreCase("/help")
                || input.equalsIgnoreCase("help")
                || input.equals("?");
    }

    static boolean isStatusCommand(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return input.equalsIgnoreCase("/status");
    }

    GameResponse handleStatusCommand(GameState game) {
        var party = gameRepository.findTheParty(game.getGameId());
        List<String> partyMembers = new ArrayList<>();
        for (Actor member : party) {
            partyMembers.add(formatPartyMember(member));
        }

        String rendered = Templates.gameStatus(game, game.getLastPlayedAt(), partyMembers).render();
        return GameResponse.reply(rendered);
    }

    private String formatPartyMember(Actor member) {
        if (member instanceof PlayerActor pa) {
            return PlayerActor.Templates.playerActorSummary(pa).render();
        }
        return Actor.Templates.actorSummary(member).render();
    }

    /**
     * Format recent events into a summary for the recap prompt.
     * Takes the last few turn summaries for context.
     */
    String formatRecentEvents(List<Event> events) {
        if (events.isEmpty()) {
            return "No previous events.";
        }

        // Take last 10 events for context
        int start = Math.max(0, events.size() - 10);
        List<Event> recent = events.subList(start, events.size());

        StringBuilder sb = new StringBuilder();
        for (Event event : recent) {
            sb.append(event.render());
        }
        return sb.toString().trim();
    }
}
