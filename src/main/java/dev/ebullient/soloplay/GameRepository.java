package dev.ebullient.soloplay;

import static dev.ebullient.soloplay.StringUtils.normalize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.BaseEntity;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.Location;
import dev.ebullient.soloplay.play.model.PlayerActor;

@ApplicationScoped
public class GameRepository {

    @Inject
    SessionFactory sessionFactory;

    private Map<String, List<Actor>> partyCache = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, List<PlayerActor>> playerActorCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ========= GAME ===============

    public GameState findGameById(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (g:Game {gameId: $gameId})
                RETURN g
                """;
        return session.queryForObject(GameState.class, cypher, Map.of("gameId", gameId));
    }

    public GameState getOrCreateGameById(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MERGE (g:Game {gameId: $gameId})
                ON CREATE SET g.gamePhase = $gamePhase
                RETURN g
                """;
        return session.queryForObject(GameState.class, cypher, Map.of(
                "gameId", gameId,
                "gamePhase", GameState.GamePhase.CHARACTER_CREATION.name()));
    }

    public GameState createGame(String gameId, String adventureName) {
        GameState game = new GameState();
        game.setGameId(gameId);
        game.setAdventureName(adventureName);
        game.setGamePhase(GameState.GamePhase.CHARACTER_CREATION);
        saveGame(game);
        return game;
    }

    public void saveGame(GameState game) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            session.save(game);
            tx.commit();
            game.markClean();
        }
    }

    public List<GameState> listGames() {
        var session = sessionFactory.openSession();
        return new ArrayList<>(session.loadAll(GameState.class));
    }

    public void deleteGame(String gameId) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            // Delete all relationship-linked entities (actors, events, locations, segments, chat memory)
            String relCypher = """
                    MATCH (g:Game {gameId: $gameId})
                    OPTIONAL MATCH (g)-[r]->(n)
                    WHERE NOT n:Document
                    DETACH DELETE n
                    WITH g
                    DETACH DELETE g
                    """;
            session.query(relCypher, Map.of("gameId", gameId));

            // Belt-and-suspenders: clean up any orphaned nodes with matching gameId
            String fallbackCypher = """
                    MATCH (n {gameId: $gameId})
                    DETACH DELETE n
                    """;
            session.query(fallbackCypher, Map.of("gameId", gameId));

            // Clean up chat memory that may not have a gameId property
            String memoryCypher = """
                    MATCH (m:ChatMemory)
                    WHERE m.id IN [$gameId, $characterMemoryId]
                    DETACH DELETE m
                    """;
            session.query(memoryCypher, Map.of(
                    "gameId", gameId,
                    "characterMemoryId", gameId + "-character"));

            tx.commit();
        }

        // Clear caches for this gameId
        partyCache.remove(gameId);
        playerActorCache.remove(gameId);
    }

    // ========= ACTORS ===============

    public List<Actor> findTheParty(String gameId) {
        return partyCache.computeIfAbsent(gameId, this::loadTheParty);
    }

    public List<Actor> refreshTheParty(String gameId) {
        // clear caches (refreshParty command)
        partyCache.remove(gameId);
        playerActorCache.remove(gameId);
        return findTheParty(gameId);
    }

    private List<Actor> loadTheParty(String gameId) {
        var session = sessionFactory.openSession();
        // PlayerActors + Actors tagged as "party" or "player-controlled"
        String cypher = """
                MATCH (a {gameId: $gameId})
                WHERE a:PlayerActor OR (a:Actor AND ('party' IN a.tags OR 'player-controlled' IN a.tags))
                RETURN a
                """;
        Iterable<Actor> result = session.query(Actor.class, cypher, Map.of("gameId", gameId));
        List<Actor> party = new ArrayList<>();
        result.forEach(party::add);
        return party;
    }

    public List<PlayerActor> listPlayerActors(String gameId) {
        return playerActorCache.computeIfAbsent(gameId, k -> {
            var session = sessionFactory.openSession();
            String cypher = """
                    MATCH (a:PlayerActor {gameId: $gameId})
                    RETURN a
                    """;
            Iterable<PlayerActor> result = session.query(PlayerActor.class, cypher, Map.of("gameId", gameId));
            List<PlayerActor> actors = new ArrayList<>();
            result.forEach(actors::add);
            return actors;
        });
    }

    public PlayerActor findPlayerActorByNameOrAlias(String gameId, String nameOrAlias) {
        var session = sessionFactory.openSession();
        String normalized = normalize(nameOrAlias);
        String cypher = """
                MATCH (a:PlayerActor {gameId: $gameId})
                WHERE a.normalizedName = $name OR $name IN a.aliases
                RETURN a
                LIMIT 1
                """;
        return session.queryForObject(PlayerActor.class, cypher, Map.of("gameId", gameId, "name", normalized));
    }

    public boolean hasProtagonists(String gameId) {
        var actors = listPlayerActors(gameId);
        return actors != null && !actors.isEmpty();
    }

    public List<Actor> listActors(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (a:Actor {gameId: $gameId})
                RETURN a
                """;
        Iterable<Actor> result = session.query(Actor.class, cypher, Map.of("gameId", gameId));
        List<Actor> actors = new ArrayList<>();
        result.forEach(actors::add);
        return actors;
    }

    public void saveActor(Actor actor) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            session.save(actor);
            // Link actor to its game via HAS_ACTOR relationship
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})
                    MATCH (a {id: $entityId})
                    WHERE a:Actor OR a:PlayerActor
                    MERGE (g)-[:HAS_ACTOR]->(a)
                    """;
            session.query(cypher, Map.of("gameId", actor.getGameId(), "entityId", actor.getId()));
            tx.commit();
            actor.markClean();
        }
    }

    public Actor findActorByNameOrAlias(String gameId, String nameOrAlias) {
        var session = sessionFactory.openSession();
        String normalized = normalize(nameOrAlias);
        String cypher = """
                MATCH (a {gameId: $gameId})
                WHERE (a:Actor OR a:PlayerActor)
                  AND (a.normalizedName = $name OR $name IN a.aliases)
                RETURN a
                LIMIT 1
                """;
        // Use query() instead of queryForObject() for polymorphic resolution
        Iterable<Actor> result = session.query(Actor.class, cypher, Map.of("gameId", gameId, "name", normalized));
        return result.iterator().hasNext() ? result.iterator().next() : null;
    }

    public List<Actor> findActorsByTag(String gameId, String tag) {
        var session = sessionFactory.openSession();
        String normalized = normalize(tag);
        String cypher = """
                MATCH (a:Actor {gameId: $gameId})
                WHERE $tag IN a.tags
                RETURN a
                """;
        Iterable<Actor> result = session.query(Actor.class, cypher, Map.of("gameId", gameId, "tag", normalized));
        List<Actor> actors = new ArrayList<>();
        result.forEach(actors::add);
        return actors;
    }

    // ========= LOCATIONS ===============

    public List<Location> listLocations(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (l:Location {gameId: $gameId})
                RETURN l
                """;
        Iterable<Location> result = session.query(Location.class, cypher, Map.of("gameId", gameId));
        List<Location> locations = new ArrayList<>();
        result.forEach(locations::add);
        return locations;
    }

    public Location findLocationByNameOrAlias(String gameId, String nameOrAlias) {
        var session = sessionFactory.openSession();
        String normalized = normalize(nameOrAlias);
        String cypher = """
                MATCH (l:Location {gameId: $gameId})
                WHERE l.normalizedName = $name OR $name IN l.aliases
                RETURN l
                LIMIT 1
                """;
        return session.queryForObject(Location.class, cypher, Map.of("gameId", gameId, "name", normalized));
    }

    public List<Location> findLocationsByTag(String gameId, String tag) {
        var session = sessionFactory.openSession();
        String normalized = normalize(tag);
        String cypher = """
                MATCH (l:Location {gameId: $gameId})
                WHERE $tag IN l.tags
                RETURN l
                """;
        Iterable<Location> result = session.query(Location.class, cypher, Map.of("gameId", gameId, "tag", normalized));
        List<Location> locations = new ArrayList<>();
        result.forEach(locations::add);
        return locations;
    }

    // ========= EVENTS ===============

    public List<Event> listEvents(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (e:Event {gameId: $gameId})
                RETURN e
                ORDER BY e.turnNumber
                """;
        Iterable<Event> result = session.query(Event.class, cypher, Map.of("gameId", gameId));
        List<Event> events = new ArrayList<>();
        result.forEach(e -> events.add(session.load(Event.class, e.getId(), 1)));
        return events;
    }

    public List<Event> findEventsByTag(String gameId, String tag) {
        var session = sessionFactory.openSession();
        String normalized = normalize(tag);
        String cypher = """
                MATCH (e:Event {gameId: $gameId})
                WHERE $tag IN e.tags
                RETURN e
                ORDER BY e.turnNumber
                """;
        Iterable<Event> result = session.query(Event.class, cypher, Map.of("gameId", gameId, "tag", normalized));
        List<Event> events = new ArrayList<>();
        result.forEach(events::add);
        return events;
    }

    // ========= ADVENTURE SEGMENTS ===============

    /**
     * Find the first Adventure Document node by sequenceNumber.
     * Returns a map with "id" and "text" keys, or null if not found.
     */
    public Map<String, Object> findFirstAdventureSegment(String adventureName) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (d:Document:Adventure {adventureName: $adventureName})
                RETURN d.id AS id, d.text AS text
                ORDER BY d.sequenceNumber ASC
                LIMIT 1
                """;
        var result = session.query(cypher, Map.of("adventureName", adventureName));
        var it = result.iterator();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Create a GameSegment node and link it to the Game via CURRENT_STEP.
     * Removes any existing CURRENT_STEP relationship first (idempotent).
     */
    public void initCurrentStep(String gameId, String documentId) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})
                    OPTIONAL MATCH (g)-[old:CURRENT_STEP]->()
                    DELETE old
                    WITH g
                    CREATE (gs:GameSegment {
                        id: $segmentId,
                        gameId: $gameId,
                        documentId: $documentId,
                        status: 'current',
                        createdAt: $now
                    })
                    CREATE (g)-[:CURRENT_STEP]->(gs)
                    """;
            session.query(cypher, Map.of(
                    "gameId", gameId,
                    "segmentId", gameId + ":segment-1",
                    "documentId", documentId,
                    "now", System.currentTimeMillis()));
            tx.commit();
        }
    }

    /**
     * Get adventure context by joining GameSegment.documentId to Document.
     * Returns current segment text/metadata and full next segment text/metadata.
     */
    public Map<String, Object> getCurrentAdventureContext(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (g:Game {gameId: $gameId})-[:CURRENT_STEP]->(gs:GameSegment)
                MATCH (d:Document {id: gs.documentId})
                OPTIONAL MATCH (d)-[:NEXT]->(nextDoc:Document)
                RETURN d.text AS currentText, d.chapterName AS chapterName,
                       d.section AS section,
                       nextDoc.text AS nextText, nextDoc.chapterName AS nextChapterName,
                       nextDoc.section AS nextSection
                """;
        var result = session.query(cypher, Map.of("gameId", gameId));
        var it = result.iterator();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Advance to the next adventure segment. Marks the old GameSegment as completed,
     * creates a new GameSegment for the next Document (via NEXT chain).
     * Returns false if there is no next Document.
     */
    public boolean advanceAdventureSegment(String gameId, int turnNumber) {
        var session = sessionFactory.openSession();

        // First check if there's a next document
        String checkCypher = """
                MATCH (g:Game {gameId: $gameId})-[:CURRENT_STEP]->(gs:GameSegment)
                MATCH (d:Document {id: gs.documentId})-[:NEXT]->(nextDoc:Document)
                RETURN nextDoc.id AS nextDocId, gs.id AS oldSegmentId
                """;
        var result = session.query(checkCypher, Map.of("gameId", gameId));
        var it = result.iterator();
        if (!it.hasNext()) {
            return false;
        }
        var row = it.next();
        String nextDocId = (String) row.get("nextDocId");

        // Advance in a transaction
        try (Transaction tx = session.beginTransaction()) {
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})-[rel:CURRENT_STEP]->(oldGs:GameSegment)
                    SET oldGs.status = 'completed', oldGs.turnNumber = $turnNumber, oldGs.completedAt = $now
                    DELETE rel
                    CREATE (g)-[:COMPLETED_STEP]->(oldGs)
                    CREATE (newGs:GameSegment {
                        id: $segmentId,
                        gameId: $gameId,
                        documentId: $nextDocId,
                        status: 'current',
                        createdAt: $now
                    })
                    CREATE (g)-[:CURRENT_STEP]->(newGs)
                    """;
            session.query(cypher, Map.of(
                    "gameId", gameId,
                    "turnNumber", turnNumber,
                    "now", System.currentTimeMillis(),
                    "segmentId", gameId + ":segment-" + (turnNumber + 1),
                    "nextDocId", nextDocId));
            tx.commit();
        }
        return true;
    }

    /**
     * Record a player decision that deviates from the written adventure.
     */
    public void recordDecision(String gameId, int turnNumber, String summary) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})-[:CURRENT_STEP]->(gs:GameSegment)
                    CREATE (dec:GameSegment {
                        id: $segmentId,
                        gameId: $gameId,
                        documentId: gs.documentId,
                        status: 'decision',
                        turnNumber: $turnNumber,
                        summary: $summary,
                        createdAt: $now
                    })
                    CREATE (g)-[:DECISION]->(dec)
                    """;
            session.query(cypher, Map.of(
                    "gameId", gameId,
                    "turnNumber", turnNumber,
                    "segmentId", gameId + ":decision-" + turnNumber,
                    "summary", summary,
                    "now", System.currentTimeMillis()));
            tx.commit();
        }
    }

    // ========= CHECKPOINTS ===============

    /**
     * Create a checkpoint GameSegment and link it to the Game via CHECKPOINT.
     */
    public void saveCheckpoint(String gameId, String category, String content, int turnNumber) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})
                    CREATE (gs:GameSegment {
                        id: $segmentId,
                        gameId: $gameId,
                        status: 'checkpoint',
                        category: $category,
                        summary: $content,
                        turnNumber: $turnNumber,
                        createdAt: $now
                    })
                    CREATE (g)-[:CHECKPOINT]->(gs)
                    """;
            session.query(cypher, Map.of(
                    "gameId", gameId,
                    "segmentId", gameId + ":checkpoint-" + category + "-" + turnNumber,
                    "category", category,
                    "content", content,
                    "turnNumber", turnNumber,
                    "now", System.currentTimeMillis()));
            tx.commit();
        }
    }

    /**
     * Delete all checkpoints of a given category for a game.
     */
    public void clearCheckpoints(String gameId, String category) {
        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            String cypher = """
                    MATCH (g:Game {gameId: $gameId})-[r:CHECKPOINT]->(gs:GameSegment {status: 'checkpoint', category: $category})
                    DETACH DELETE gs
                    """;
            session.query(cypher, Map.of("gameId", gameId, "category", category));
            tx.commit();
        }
    }

    /**
     * Return all checkpoint GameSegments for a game, ordered by turnNumber.
     */
    public List<Map<String, Object>> getCheckpoints(String gameId) {
        var session = sessionFactory.openSession();
        String cypher = """
                MATCH (g:Game {gameId: $gameId})-[:CHECKPOINT]->(gs:GameSegment {status: 'checkpoint'})
                RETURN gs.category AS category, gs.summary AS content, gs.turnNumber AS turnNumber
                ORDER BY gs.turnNumber
                """;
        var result = session.query(cypher, Map.of("gameId", gameId));
        List<Map<String, Object>> checkpoints = new ArrayList<>();
        result.forEach(checkpoints::add);
        return checkpoints;
    }

    public void saveAll(Collection<? extends BaseEntity> entities) {
        if (entities.isEmpty()) {
            return;
        }

        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            for (var entity : entities) {
                if (entity.isDirty()) {
                    session.save(entity, 1);
                    entity.markClean();
                }
            }
            tx.commit();
        }
    }

    /**
     * Create Game→Entity relationships via Cypher for all saved entities.
     * Actors and Locations link directly to Game (HAS_ACTOR, HAS_LOCATION).
     * Events form a chain: Game-[:HAS_EVENT]->E1-[:NEXT_EVENT]->E2->...
     * Uses MERGE so it's idempotent (safe to call repeatedly).
     */
    public void linkEntitiesToGame(String gameId, Collection<? extends BaseEntity> entities) {
        if (entities.isEmpty()) {
            return;
        }

        // Group entity IDs by relationship type
        List<String> actorIds = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();
        List<String> locationIds = new ArrayList<>();

        for (var entity : entities) {
            if (entity instanceof Actor a) {
                actorIds.add(a.getId());
            } else if (entity instanceof Event e) {
                eventIds.add(e.getId());
            } else if (entity instanceof Location l) {
                locationIds.add(l.getId());
            }
        }

        var session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            if (!actorIds.isEmpty()) {
                String cypher = """
                        MATCH (g:Game {gameId: $gameId})
                        MATCH (a {gameId: $gameId})
                        WHERE (a:Actor OR a:PlayerActor) AND a.id IN $ids
                        MERGE (g)-[:HAS_ACTOR]->(a)
                        """;
                session.query(cypher, Map.of("gameId", gameId, "ids", actorIds));
            }
            if (!locationIds.isEmpty()) {
                String cypher = """
                        MATCH (g:Game {gameId: $gameId})
                        MATCH (l:Location {gameId: $gameId})
                        WHERE l.id IN $ids
                        MERGE (g)-[:HAS_LOCATION]->(l)
                        """;
                session.query(cypher, Map.of("gameId", gameId, "ids", locationIds));
            }
            // Chain events: first links to Game, subsequent link to the previous tail
            for (String eventId : eventIds) {
                String cypher = """
                        MATCH (g:Game {gameId: $gameId})
                        MATCH (e:Event {id: $eventId})
                        OPTIONAL MATCH (g)-[:HAS_EVENT]->(first:Event)
                        OPTIONAL MATCH (first)-[:NEXT_EVENT*0..]->(tail:Event)
                        WHERE NOT (tail)-[:NEXT_EVENT]->()
                        WITH g, e, tail
                        FOREACH (_ IN CASE WHEN tail IS NULL THEN [1] ELSE [] END |
                            MERGE (g)-[:HAS_EVENT]->(e)
                        )
                        FOREACH (_ IN CASE WHEN tail IS NOT NULL AND tail <> e THEN [1] ELSE [] END |
                            MERGE (tail)-[:NEXT_EVENT]->(e)
                        )
                        """;
                session.query(cypher, Map.of("gameId", gameId, "eventId", eventId));
            }
            tx.commit();
        }
    }

}
