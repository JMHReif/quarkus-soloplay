package dev.ebullient.soloplay.play;

import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.StringUtils;
import dev.ebullient.soloplay.play.GameEffect.StatefulEffect;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.GameState.GamePhase;
import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.ebullient.soloplay.play.model.PlayerActorCreationPatch;
import dev.ebullient.soloplay.play.model.PlayerActorDraft;
import dev.ebullient.soloplay.play.model.Stash;

@ApplicationScoped
public class ActorCreationEngine {
    public static final String SLOT = "actor_draft";
    static final PlayerActorDraft EMPTY_DRAFT = new PlayerActorDraft(null, null, null, null, null, null, null, false);

    @Inject
    GameRepository gameRepository;

    @Inject
    ActorCreationAssistant assistant;

    @Inject
    ObjectMapper objectMapper;

    public GameResponse processRequest(GameState game, String playerInput, Stash clientDraft, GameEventEmitter emitter) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(emitter, "emitter");

        game.setGamePhase(GamePhase.CHARACTER_CREATION);

        String trimmed = playerInput == null ? "" : playerInput.trim();
        if (GameEngine.isHelpCommand(trimmed)) {
            return help(game);
        }

        // Use client-provided draft or fall back to empty
        var currentDraft = (clientDraft instanceof PlayerActorDraft pad) ? pad : EMPTY_DRAFT;
        if ("/cancel".equalsIgnoreCase(trimmed)) {
            return cancelDraft(game);
        }
        if ("/reset".equalsIgnoreCase(trimmed)) {
            return resetDraft();
        }
        if ("/confirm".equalsIgnoreCase(trimmed)) {
            return saveDraft(game, currentDraft, emitter);
        }
        emitter.assistantDelta("The GM is thinking…\n");

        try {
            ActorCreationResponse response = handleAssistantResponse(game, currentDraft, trimmed);

            // All is well with parsed response
            var message = response.message();
            var patch = response.patch();

            emitter.assistantDelta("Updating your character…\n");
            PlayerActorDraft updatedDraft = applyPatch(currentDraft, patch);

            return GameResponse.reply(
                    (message == null ? "ok." : message) + "\n\n"
                            + "\n\nUse `/confirm` when your character is ready.",
                    draftEffect(updatedDraft));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null) {
                message = e.toString();
            }
            return GameResponse.error("Unable to get a response from the GM: " + message);
        }
    }

    private GameResponse saveDraft(GameState game,
            PlayerActorDraft draft,
            GameEventEmitter emitter) {
        emitter.assistantDelta("Confirming character…\n");

        String missing = missingRequired(draft);
        if (missing != null) {
            return GameResponse.error("Can't confirm yet: " + missing);
        }

        PlayerActor actor = new PlayerActor(game.getGameId(), draft);
        emitter.assistantDelta("Saving character…\n");
        gameRepository.saveActor(actor);

        game.setGamePhase(game.getGamePhase().next());
        // Return clear effect to remove client state
        return GameResponse.reply("""
                Created your character: **%s** (%s, level %s).

                Use `/newcharacter` to create an additional character, or `/start` to start or resume your game.
                """.stripIndent().formatted(actor.getName(), actor.getActorClass(), actor.getLevel()), clearDraftEffect());
    }

    private GameResponse cancelDraft(GameState game) {
        String partyMembers = gameRepository.listPlayerActors(game.getGameId()).stream()
                .map(pa -> "%s, %s, level %s".formatted(pa.getName(), pa.getActorClass(), pa.getLevel()))
                .collect(Collectors.joining("; "));
        if (!partyMembers.isBlank()) {
            game.setGamePhase(game.getGamePhase().next());

            return GameResponse.reply("""
                    Exiting character creation.

                    Current party: %s

                    Use `/newcharacter` to create an additional character, or `/start` to start or resume your game.
                    """.stripIndent().formatted(partyMembers), clearDraftEffect());
        }
        return GameResponse.reply("Ok. Your draft has been reset, but you still need to define a character.",
                clearDraftEffect());
    }

    private GameResponse resetDraft() {
        // Return clear effect to remove client state
        return GameResponse.reply("Ok — cleared your character draft.", clearDraftEffect());
    }

    private ActorCreationResponse handleAssistantResponse(GameState game,
            PlayerActorDraft currentDraft,
            String playerInput) {
        String chatMemoryId = game.getGameId() + "-character";

        if ((playerInput.isBlank() || playerInput.equals("/start")) && currentDraft == EMPTY_DRAFT) {
            return assistant.start(chatMemoryId, game.getGameId(), game.getAdventureName());
        } else {
            return assistant.step(chatMemoryId, game.getGameId(), game.getAdventureName(), currentDraft,
                    playerInput);
        }
    }

    public GameResponse help(GameState game) {
        return GameResponse.reply("""
                Character creation commands:

                - `/confirm`: create the character (requires name, class, level)
                - `/reset`: clear the current draft
                - `/cancel`: exit character creation (requires at least one party member)
                - `/help` (or `help`, `?`): show commands
                """);
    }

    static String missingRequired(PlayerActorDraft draft) {
        if (draft == null) {
            return "no draft";
        }
        if (draft.name() == null || draft.name().isBlank()) {
            return "missing name";
        }
        if (draft.actorClass() == null || draft.actorClass().isBlank()) {
            return "missing class";
        }
        if (draft.level() == null || draft.level() < 1) {
            return "missing/invalid level";
        }
        return null;
    }

    static PlayerActorDraft applyPatch(PlayerActorDraft current, PlayerActorCreationPatch patch) {
        if (patch == null) {
            return current;
        }
        return new PlayerActorDraft(
                StringUtils.firstNonBlank(patch.name(), current.name()),
                StringUtils.firstNonBlank(patch.actorClass(), current.actorClass()),
                patch.level() != null ? patch.level() : current.level(),
                StringUtils.firstNonBlank(patch.summary(), current.summary()),
                StringUtils.firstNonBlank(patch.description(), current.description()),
                patch.tags() != null ? patch.tags() : current.tags(),
                patch.aliases() != null ? patch.aliases() : current.aliases(),
                current.confirmed());
    }

    /**
     * Create a StatefulEffect for an actor draft (for round-trip through client).
     * The draft is rendered as HTML for the draft panel display.
     */
    static StatefulEffect draftEffect(PlayerActorDraft draft) {
        if (draft == null) {
            return clearDraftEffect();
        }
        // Render as HTML for draft panel display
        String html = PlayerActor.Templates.playerActorDraft(draft).render();
        return new StatefulEffect(SLOT, html, draft);
    }

    /**
     * Create a StatefulEffect that clears the actor draft state.
     */
    static StatefulEffect clearDraftEffect() {
        return StatefulEffect.clear(SLOT);
    }
}
