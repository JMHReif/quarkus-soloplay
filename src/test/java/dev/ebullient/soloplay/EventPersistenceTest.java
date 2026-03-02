package dev.ebullient.soloplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.SessionFactory;

import dev.ebullient.soloplay.play.model.Actor;
import dev.ebullient.soloplay.play.model.Event;
import dev.ebullient.soloplay.play.model.Location;
import dev.ebullient.soloplay.play.model.Patch;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class EventPersistenceTest {
    final static String gameId = "EventPersistenceTest";

    @Inject
    GameRepository gameRepository;

    @Inject
    SessionFactory sessionFactory;

    @BeforeEach
    void cleanDb() {
        gameRepository.deleteGame(gameId);
        var gameState = gameRepository.findGameById(gameId);
        assertNull(gameState);
    }

    @Test
    void eventLocationsArePersistedAndLoaded() {
        String gameId = "EventPersistenceTest";
        gameRepository.createGame(gameId, "Test Adventure");

        Location location = new Location(gameId, new Patch(
                "location",
                "The Rusty Anchor",
                "A dockside tavern",
                null,
                List.of(),
                List.of(),
                List.of()));

        Actor actor = new Actor(gameId, new Patch(
                "actor",
                "Krux",
                "An astral elf",
                null,
                List.of(),
                List.of(),
                List.of()));

        Event event = new Event(gameId, 1, "The party arrives at the Rusty Anchor.");
        event.addLocation(location);
        event.addParticipant(actor);

        assertTrue(event.isDirty());
        assertTrue(location.isDirty());
        assertTrue(actor.isDirty());

        assertEquals(1, event.getLocations().size());
        assertEquals(1, event.getParticipants().size());

        gameRepository.saveAll(new HashSet<>(List.of(event, location, actor)));

        List<Event> events = gameRepository.listEvents(gameId);
        assertEquals(1, events.size());

        Event loaded = events.get(0);
        assertNotNull(loaded.getLocations());
        assertEquals(1, loaded.getLocations().size());
        assertTrue(loaded.getLocations().stream().anyMatch(l -> "The Rusty Anchor".equals(l.getName())));

        assertNotNull(loaded.getParticipants());
        assertEquals(1, loaded.getParticipants().size());
        assertTrue(loaded.getParticipants().stream().anyMatch(a -> "Krux".equals(a.getName())));

        assertNotNull(gameRepository.findLocationByNameOrAlias(gameId, "the rusty anchor"));
        assertNotNull(gameRepository.findActorByNameOrAlias(gameId, "krux"));
    }
}
