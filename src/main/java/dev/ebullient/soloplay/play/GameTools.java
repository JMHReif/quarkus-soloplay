package dev.ebullient.soloplay.play;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.Location;
import dev.langchain4j.agent.tool.Tool;

/**
 * AI Tools for querying game state.
 * Provides access to actors, locations, and story recap.
 */
@ApplicationScoped
public class GameTools {

    @Inject
    GameRepository gameRepository;

    @Inject
    GameContext gameContext;

    @Tool("""
            Get a recap of recent story events and campaign notes.
            Call this at the START of each turn to recall what happened recently.
            Returns recent turn summaries and any saved campaign notes.
            """)
    public String getStoryRecap() {
        String gameId = gameContext.getGameId();
        if (gameId == null) {
            return "No game context available.";
        }

        StringBuilder sb = new StringBuilder();

        // Recent events (last 5 turn summaries)
        List<Event> events = gameRepository.listEvents(gameId);
        if (!events.isEmpty()) {
            int start = Math.max(0, events.size() - 5);
            List<Event> recent = events.subList(start, events.size());
            sb.append("Recent turns:\n");
            for (Event event : recent) {
                sb.append("- Turn ").append(event.getTurnNumber())
                        .append(": ").append(event.getSummary()).append("\n");
            }
        }

        // Campaign notes (checkpoints: memory + milestones, skip character)
        List<Map<String, Object>> checkpoints = gameRepository.getCheckpoints(gameId);
        boolean hasNotes = false;
        for (var cp : checkpoints) {
            String category = (String) cp.get("category");
            if ("character".equals(category)) {
                continue;
            }
            if (!hasNotes) {
                sb.append("\nCampaign notes:\n");
                hasNotes = true;
            }
            sb.append("- ").append(cp.get("content")).append("\n");
        }

        return sb.isEmpty() ? "No story history yet." : sb.toString().trim();
    }

    @Tool("""
            Look up full details for an actor (NPC, creature, or player character) by name or alias.
            Returns backstory, tags, aliases, and event history.
            Use when you need deeper context beyond the party summary, or to check if an NPC already exists.
            """)
    public String findActor(String name) {
        String gameId = gameContext.getGameId();
        if (gameId == null) {
            return "Error: No game context available";
        }

        Actor actor = gameRepository.findActorByNameOrAlias(gameId, name);
        if (actor == null) {
            return "No actor found with name or alias: " + name;
        }
        return actor.render();
    }

    @Tool("""
            Look up full details for a location by name or alias.
            Returns description, tags, aliases, and event history.
            Use when you need deeper context beyond the location summary, or to check if a location already exists.
            """)
    public String findLocation(String name) {
        String gameId = gameContext.getGameId();
        if (gameId == null) {
            return "Error: No game context available";
        }

        Location location = gameRepository.findLocationByNameOrAlias(gameId, name);
        if (location == null) {
            return "No location found with name or alias: " + name;
        }
        return location.render();
    }
}
