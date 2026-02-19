package dev.ebullient.soloplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.ebullient.soloplay.play.GameEngine;
import dev.ebullient.soloplay.play.GameResponse;
import dev.ebullient.soloplay.play.model.GameState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;

@QuarkusTest
class PlayWebSocketTest {

    @TestHTTPResource("/")
    URI baseUri;

    @Inject
    BasicWebSocketConnector connector;

    @Inject
    ObjectMapper objectMapper;

    @InjectMock
    GameEngine mockGameEngine;

    @BeforeEach
    void setup() {
        Mockito.when(mockGameEngine.getGameState(Mockito.anyString()))
                .thenAnswer(invocation -> {
                    String gameId = invocation.getArgument(0);
                    GameState game = new GameState();
                    game.setGameId(gameId);
                    game.setAdventureName("Test Adventure");
                    game.setGamePhase(GameState.GamePhase.CHARACTER_CREATION);
                    return game;
                });
    }

    @Test
    void testConnectionSendsSessionMessage() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        String gameId = "test-game-" + UUID.randomUUID();
        WebSocketClientConnection connection = connector
                .baseUri(wsUri(gameId))
                .onTextMessage((c, msg) -> {
                    receivedMessage.set(msg);
                    latch.countDown();
                })
                .connectAndAwait();

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive session message");

            String message = receivedMessage.get();
            assertNotNull(message);

            JsonNode json = objectMapper.readTree(message);
            assertEquals("session", json.get("type").asText());
            assertEquals(gameId, json.get("gameId").asText());
        } finally {
            connection.closeAndAwait();
        }
    }

    @Test
    void testHistoryRequestReturnsEmptyHistory() throws Exception {
        CountDownLatch sessionLatch = new CountDownLatch(1);
        CountDownLatch historyLatch = new CountDownLatch(1);
        AtomicReference<String> historyMessage = new AtomicReference<>();

        String gameId = "test-game-" + UUID.randomUUID();
        WebSocketClientConnection connection = connector
                .baseUri(wsUri(gameId))
                .onTextMessage((c, msg) -> {
                    try {
                        JsonNode json = objectMapper.readTree(msg);
                        String type = json.get("type").asText();
                        if ("session".equals(type)) {
                            sessionLatch.countDown();
                        } else if ("history".equals(type)) {
                            historyMessage.set(msg);
                            historyLatch.countDown();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                })
                .connectAndAwait();

        try {
            // Wait for session message
            assertTrue(sessionLatch.await(5, TimeUnit.SECONDS), "Should receive session message");

            // Send history request
            connection.sendTextAndAwait("{\"type\":\"history_request\",\"limit\":100}");

            // Wait for history response
            assertTrue(historyLatch.await(5, TimeUnit.SECONDS), "Should receive history message");

            String message = historyMessage.get();
            assertNotNull(message);

            JsonNode json = objectMapper.readTree(message);
            assertEquals("history", json.get("type").asText());
            assertTrue(json.get("messages").isArray());
            assertEquals(0, json.get("messages").size());
        } finally {
            connection.closeAndAwait();
        }
    }

    @Test
    void testUserMessageStreamsAssistantResponse() throws Exception {
        Mockito.when(mockGameEngine.processRequest(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.anyBoolean()))
                .thenAnswer(invocation -> {
                    var emitter = (dev.ebullient.soloplay.play.GameEventEmitter) invocation.getArgument(2);
                    emitter.assistantDelta("working...");
                    return GameResponse.reply("test");
                });

        CountDownLatch sessionLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        List<String> messageTypes = new CopyOnWriteArrayList<>();
        AtomicReference<String> assistantId = new AtomicReference<>();

        CountDownLatch historyLatch = new CountDownLatch(1);
        AtomicReference<String> historyMessage = new AtomicReference<>();

        String gameId = "test-game-" + UUID.randomUUID();
        WebSocketClientConnection connection = connector
                .baseUri(wsUri(gameId))
                .onTextMessage((c, msg) -> {
                    try {
                        JsonNode json = objectMapper.readTree(msg);
                        String type = json.get("type").asText();
                        messageTypes.add(type);
                        if ("session".equals(type)) {
                            sessionLatch.countDown();
                        }
                        if ("assistant_start".equals(type)) {
                            assistantId.set(json.get("id").asText());
                        }
                        if ("assistant_done".equals(type)) {
                            doneLatch.countDown();
                        }
                        if ("history".equals(type)) {
                            historyMessage.set(msg);
                            historyLatch.countDown();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                })
                .connectAndAwait();

        try {
            assertTrue(sessionLatch.await(5, TimeUnit.SECONDS), "Should receive session message");

            connection.sendTextAndAwait("{\"type\":\"user_message\",\"text\":\"hello\"}");

            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Should receive assistant_done");
            assertTrue(messageTypes.contains("user_echo"), "Should echo user message");
            assertTrue(messageTypes.contains("assistant_start"), "Should start assistant stream");
            assertTrue(messageTypes.contains("assistant_delta"), "Should stream assistant deltas");
            assertTrue(messageTypes.contains("assistant_done"), "Should finish assistant stream");
            assertNotNull(assistantId.get());

            // Wait for session message
            assertTrue(sessionLatch.await(5, TimeUnit.SECONDS), "Should receive session message");

            // Send history request
            connection.sendTextAndAwait("{\"type\":\"history_request\",\"limit\":100}");
            assertTrue(historyLatch.await(5, TimeUnit.SECONDS), "Should receive history message");

            String message = historyMessage.get();
            assertNotNull(message);
            JsonNode json = objectMapper.readTree(message);
            assertEquals("history", json.get("type").asText());
            assertTrue(json.get("messages").isArray());
            assertEquals(2, json.get("messages").size()); // user message, done message
        } finally {
            connection.closeAndAwait();
        }
    }

    private URI wsUri(String gameId) {
        return baseUri.resolve("ws/play/" + gameId);
    }
}
