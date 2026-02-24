package dev.ebullient.soloplay.play.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Transient;

@NodeEntity("Game")
public class GameState extends BaseEntity {

    public enum GamePhase {
        CHARACTER_CREATION,
        SCENE_INITIALIZATION,
        ACTIVE_PLAY,
        UNKNOWN;

        public GamePhase next() {
            return switch (this) {
                case CHARACTER_CREATION -> SCENE_INITIALIZATION;
                case SCENE_INITIALIZATION -> ACTIVE_PLAY;
                default -> GamePhase.UNKNOWN;
            };
        }
    }

    public enum CharacterCreationStage {
        NAME, // Get character name
        CLASS, // Get character class
        LEVEL, // Get character level
        SUMMARY, // Get brief summary
        DESCRIPTION, // Get description
        TAGS, // Get tags (race, background, etc.)
        REVIEW, // Review and confirm
        COMPLETE; // Done

        public CharacterCreationStage next() {
            return switch (this) {
                case NAME -> CLASS;
                case CLASS -> LEVEL;
                case LEVEL -> SUMMARY;
                case SUMMARY -> DESCRIPTION;
                case DESCRIPTION -> TAGS;
                case TAGS -> REVIEW;
                case REVIEW -> COMPLETE;
                case COMPLETE -> COMPLETE;
            };
        }

        public String fieldName() {
            return switch (this) {
                case NAME -> "name";
                case CLASS -> "class";
                case LEVEL -> "level";
                case SUMMARY -> "summary";
                case DESCRIPTION -> "description";
                case TAGS -> "tags";
                case REVIEW -> "review";
                case COMPLETE -> "complete";
            };
        }
    }

    @Id
    String gameId;
    String adventureName;
    GamePhase gamePhase;
    CharacterCreationStage characterCreationStage;

    // Gameplay state
    int turnNumber; // Increment each turn
    String currentLocation; // "location:docks"
    String lastNarration; // Previous turn's narration for continuity
    Long lastPlayedAt;

    @Transient
    Map<String, Stash> stash = new HashMap<>();

    /**
     * @return the gameId
     */
    public String getGameId() {
        return gameId;
    }

    /**
     * @param gameId the gameId to set
     */
    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    /**
     * @return the gamePhase
     */
    public GamePhase getGamePhase() {
        return gamePhase == null
                ? GamePhase.UNKNOWN
                : gamePhase;
    }

    /**
     * @param gamePhase the gamePhase to set
     */
    public void setGamePhase(GamePhase gamePhase) {
        this.gamePhase = gamePhase;
        markDirty();
    }

    public Long getLastPlayedAt() {
        return lastPlayedAt;
    }

    public Integer getTurnNumber() {
        return turnNumber;
    }

    public void incrementTurn() {
        turnNumber++;
        this.lastPlayedAt = Instant.now().toEpochMilli();
    }

    public String getAdventureName() {
        return adventureName;
    }

    public void setAdventureName(String adventureName) {
        this.adventureName = adventureName;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(String currentLocation) {
        this.currentLocation = currentLocation;
    }

    public String getLastNarration() {
        return lastNarration;
    }

    public void setLastNarration(String lastNarration) {
        this.lastNarration = lastNarration;
    }

    public CharacterCreationStage getCharacterCreationStage() {
        return characterCreationStage == null ? CharacterCreationStage.NAME : characterCreationStage;
    }

    public void setCharacterCreationStage(CharacterCreationStage stage) {
        this.characterCreationStage = stage;
        markDirty();
    }

    public void advanceCharacterCreationStage() {
        this.characterCreationStage = getCharacterCreationStage().next();
        markDirty();
    }

    public <T extends Stash> T getStash(String key, Class<T> clazz) {
        Stash draft = stash.get(key);
        if (clazz.isInstance(draft)) {
            return clazz.cast(draft);
        }
        return null;
    }

    public <T extends Stash> T getStashOrDefault(String key, Class<T> clazz, T fallback) {
        Stash draft = stash.getOrDefault(key, fallback);
        if (clazz.isInstance(draft)) {
            return clazz.cast(draft);
        }
        return fallback;
    }

    public <T extends Stash> void putStash(String key, T value) {
        this.stash.put(key, value);
    }

    public <T extends Stash> void removeStash(String key) {
        this.stash.remove(key);
    }

    public Object dumpStash() {
        return this.stash;
    }
}
