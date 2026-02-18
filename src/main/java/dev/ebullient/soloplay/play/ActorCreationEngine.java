package dev.ebullient.soloplay.play;

import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.GameState.CharacterCreationStage;
import dev.ebullient.soloplay.play.model.GameState.GamePhase;
import dev.ebullient.soloplay.play.model.PlayerActor;
import io.quarkus.logging.Log;

@ApplicationScoped
public class ActorCreationEngine {
    static final String CURRENT_ACTOR_KEY = "current_actor_id";

    @Inject
    GameRepository gameRepository;

    @Inject
    ActorCreationAssistant assistant;

    public GameResponse processRequest(GameState game, String playerInput, GameEventEmitter emitter) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(emitter, "emitter");

        game.setGamePhase(GamePhase.CHARACTER_CREATION);

        String trimmed = playerInput == null ? "" : playerInput.trim();

        // Handle commands
        if (isHelpCommand(trimmed)) {
            return help(game);
        }
        if ("/cancel".equalsIgnoreCase(trimmed)) {
            return cancelCreation(game);
        }
        if ("/status".equalsIgnoreCase(trimmed)) {
            return showStatus(game);
        }
        if ("/done".equalsIgnoreCase(trimmed) || "/confirm".equalsIgnoreCase(trimmed)) {
            return finishCharacter(game);
        }
        if ("/back".equalsIgnoreCase(trimmed)) {
            return goBack(game);
        }

        CharacterCreationStage stage = game.getCharacterCreationStage();
        Log.debugf("Character creation stage: %s, input: %s", stage, trimmed);

        // If starting fresh or continuing
        if (trimmed.isBlank() || trimmed.equals("/start") || trimmed.equals("/newcharacter")) {
            return promptForCurrentStage(game, emitter);
        }

        // Process player input for current stage
        return processStageInput(game, stage, trimmed, emitter);
    }

    private GameResponse promptForCurrentStage(GameState game, GameEventEmitter emitter) {
        CharacterCreationStage stage = game.getCharacterCreationStage();
        PlayerActor currentActor = getCurrentActor(game);

        emitter.assistantDelta("Preparing character creation...\n");

        String chatMemoryId = game.getGameId() + "-character";

        try {
            ActorCreationResponse response = assistant.promptForStage(
                    chatMemoryId,
                    game.getGameId(),
                    game.getAdventureName(),
                    stage.name(),
                    currentActor);

            return GameResponse.reply(response.messageMarkdown());
        } catch (Exception e) {
            Log.errorf(e, "Error prompting for stage %s", stage);
            return GameResponse.error("Unable to get a response: " + e.getMessage());
        }
    }

    private GameResponse processStageInput(GameState game, CharacterCreationStage stage,
            String playerInput, GameEventEmitter emitter) {
        emitter.assistantDelta("Processing your input...\n");

        String chatMemoryId = game.getGameId() + "-character";
        PlayerActor currentActor = getCurrentActor(game);

        try {
            ActorCreationResponse response = assistant.processStageInput(
                    chatMemoryId,
                    game.getGameId(),
                    game.getAdventureName(),
                    stage.name(),
                    currentActor,
                    playerInput);

            // Apply any patch from the response
            if (response.patch() != null) {
                Log.infof("Character patch for stage %s: %s", stage, response.patch());
                currentActor = applyPatchAndSave(game, currentActor, response.patch());
                emitter.assistantDelta("Saved to character sheet.\n");

                // Check if we should advance to next stage
                if (shouldAdvanceStage(stage, currentActor)) {
                    game.advanceCharacterCreationStage();
                    gameRepository.saveGame(game);
                    Log.debugf("Advanced to stage: %s", game.getCharacterCreationStage());
                }
            }

            // Build response message
            String message = response.messageMarkdown();
            if (game.getCharacterCreationStage() == CharacterCreationStage.REVIEW) {
                message += "\n\n" + formatCharacterSummary(currentActor);
                message += "\n\nType `/done` to finish, `/back` to make changes, or tell me what you'd like to change.";
            } else if (game.getCharacterCreationStage() == CharacterCreationStage.COMPLETE) {
                return finishCharacter(game);
            }

            return GameResponse.reply(message);
        } catch (Exception e) {
            Log.errorf(e, "Error processing stage input for %s", stage);
            return GameResponse.error("Unable to process your input: " + e.getMessage());
        }
    }

    private boolean shouldAdvanceStage(CharacterCreationStage stage, PlayerActor actor) {
        if (actor == null)
            return false;

        return switch (stage) {
            case NAME -> actor.getName() != null && !actor.getName().isBlank();
            case CLASS -> actor.getActorClass() != null && !actor.getActorClass().isBlank();
            case LEVEL -> actor.getLevel() != null && actor.getLevel() > 0;
            case SUMMARY -> actor.getSummary() != null && !actor.getSummary().isBlank();
            case DESCRIPTION -> actor.getDescription() != null && !actor.getDescription().isBlank();
            case TAGS -> true; // Tags are optional, always advance
            case REVIEW -> false; // Don't auto-advance from review
            case COMPLETE -> false;
        };
    }

    private PlayerActor applyPatchAndSave(GameState game, PlayerActor actor,
            ActorCreationResponse.CharacterPatch patch) {
        if (actor == null && patch.name() != null) {
            // Create new actor with just the name
            actor = new PlayerActor();
            actor.setGameId(game.getGameId());
            actor.setName(patch.name());
            // Generate ID
            String id = game.getGameId() + ":" + patch.name().toLowerCase().replace(" ", "-");
            actor.setId(id);
        }

        if (actor == null) {
            Log.warn("Cannot apply patch without actor or name");
            return null;
        }

        // Apply patch fields
        if (patch.name() != null)
            actor.setName(patch.name());
        if (patch.actorClass() != null)
            actor.setActorClass(patch.actorClass());
        if (patch.level() != null)
            actor.setLevel(patch.level());
        if (patch.summary() != null)
            actor.setSummary(patch.summary());
        if (patch.description() != null)
            actor.setDescription(patch.description());
        if (patch.tags() != null && !patch.tags().isEmpty()) {
            actor.setTags(patch.tags());
        }
        if (patch.aliases() != null && !patch.aliases().isEmpty()) {
            actor.setAliases(patch.aliases());
        }

        // Save to graph
        gameRepository.saveActor(actor);
        Log.infof("Character state: name=%s, class=%s, level=%s, summary=%s, tags=%s",
                actor.getName(), actor.getActorClass(), actor.getLevel(),
                actor.getSummary(), actor.getTags());

        return actor;
    }

    private PlayerActor getCurrentActor(GameState game) {
        List<PlayerActor> actors = gameRepository.listPlayerActors(game.getGameId());
        // Return the most recently created incomplete actor, or null
        return actors.isEmpty() ? null : actors.get(actors.size() - 1);
    }

    private GameResponse cancelCreation(GameState game) {
        List<PlayerActor> actors = gameRepository.listPlayerActors(game.getGameId());
        if (actors.isEmpty()) {
            return GameResponse.reply("No character to cancel. Start over with `/newcharacter`.");
        }

        // Reset stage to allow creating another character
        game.setCharacterCreationStage(CharacterCreationStage.NAME);
        game.setGamePhase(GamePhase.CHARACTER_CREATION.next());
        gameRepository.saveGame(game);

        return GameResponse.reply(
                "Character creation cancelled. You have " + actors.size() + " character(s).\n\n" +
                        "Use `/newcharacter` to create another, or `/start` to begin the adventure.");
    }

    private GameResponse showStatus(GameState game) {
        PlayerActor actor = getCurrentActor(game);
        CharacterCreationStage stage = game.getCharacterCreationStage();

        StringBuilder sb = new StringBuilder();
        sb.append("**Character Creation Status**\n\n");
        sb.append("Current stage: ").append(stage.fieldName()).append("\n\n");

        if (actor != null) {
            sb.append(formatCharacterSummary(actor));
        } else {
            sb.append("No character started yet.\n");
        }

        return GameResponse.reply(sb.toString());
    }

    private GameResponse finishCharacter(GameState game) {
        PlayerActor actor = getCurrentActor(game);

        if (actor == null || actor.getName() == null) {
            return GameResponse.error("No character to finish. Please provide at least a name.");
        }
        if (actor.getActorClass() == null) {
            return GameResponse.error("Please provide a class before finishing.");
        }
        if (actor.getLevel() == null) {
            actor.setLevel(1);
            gameRepository.saveActor(actor);
        }

        // Mark creation complete
        game.setCharacterCreationStage(CharacterCreationStage.COMPLETE);
        game.setGamePhase(GamePhase.CHARACTER_CREATION.next());
        gameRepository.saveGame(game);

        // Clear party cache so the new character shows up
        gameRepository.refreshTheParty(game.getGameId());

        return GameResponse.reply(
                "**Character Created!**\n\n" +
                        formatCharacterSummary(actor) + "\n\n" +
                        "Use `/newcharacter` to create another party member, or `/start` to begin your adventure!");
    }

    private GameResponse goBack(GameState game) {
        CharacterCreationStage current = game.getCharacterCreationStage();
        CharacterCreationStage previous = switch (current) {
            case NAME -> CharacterCreationStage.NAME;
            case CLASS -> CharacterCreationStage.NAME;
            case LEVEL -> CharacterCreationStage.CLASS;
            case SUMMARY -> CharacterCreationStage.LEVEL;
            case DESCRIPTION -> CharacterCreationStage.SUMMARY;
            case TAGS -> CharacterCreationStage.DESCRIPTION;
            case REVIEW -> CharacterCreationStage.TAGS;
            case COMPLETE -> CharacterCreationStage.REVIEW;
        };

        game.setCharacterCreationStage(previous);
        gameRepository.saveGame(game);

        return GameResponse.reply("Moved back to: " + previous.fieldName() +
                "\n\nWhat would you like to change?");
    }

    private String formatCharacterSummary(PlayerActor actor) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(actor.getName() != null ? actor.getName() : "(unnamed)").append("**\n");
        if (actor.getActorClass() != null) {
            sb.append("Class: ").append(actor.getActorClass());
            if (actor.getLevel() != null) {
                sb.append(" (Level ").append(actor.getLevel()).append(")");
            }
            sb.append("\n");
        }
        if (actor.getSummary() != null) {
            sb.append("Summary: ").append(actor.getSummary()).append("\n");
        }
        if (actor.getDescription() != null) {
            sb.append("Description: ").append(actor.getDescription()).append("\n");
        }
        if (actor.getTags() != null && !actor.getTags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", actor.getTags())).append("\n");
        }
        return sb.toString();
    }

    public GameResponse help(GameState game) {
        return GameResponse.reply("""
                **Character Creation Commands**

                - Just type naturally to describe your character
                - `/status` - Show current character progress
                - `/back` - Go back to previous step
                - `/done` or `/confirm` - Finish character creation
                - `/cancel` - Cancel and keep existing characters
                - `/newcharacter` - Start a new character
                - `/help` - Show this help

                **Current Stage:** """ + (game != null ? game.getCharacterCreationStage().fieldName() : "none"));
    }

    private boolean isHelpCommand(String input) {
        return input.equalsIgnoreCase("/help") ||
                input.equalsIgnoreCase("help") ||
                input.equals("?");
    }
}
