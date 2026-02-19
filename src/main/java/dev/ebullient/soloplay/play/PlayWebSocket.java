package dev.ebullient.soloplay.play;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

import dev.ebullient.soloplay.ai.MarkdownAugmenter;
import dev.ebullient.soloplay.play.GameEffect.HtmlFragment;
import dev.ebullient.soloplay.play.model.GameState;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * WebSocket endpoint for streaming Play interactions.
 *
 * Path: /ws/play/{gameId}
 *
 * Provides real-time streaming of assistant responses with token-by-token
 * delivery.
 */
@WebSocket(path = "/ws/play/{gameId}")
public class PlayWebSocket {
    static final int MAX_HISTORY_MESSAGES = 250;

    // Simple in-memory history store per gameId.
    private static final Map<String, List<PlayWsServerMessage.HistoryMessage>> HISTORY = new ConcurrentHashMap<>();

    // Simple per-game connection counts to allow cleanup when the last client disconnects.
    private static final Map<String, Integer> ACTIVE_CONNECTIONS = new ConcurrentHashMap<>();

    /**
     * Shared generation locks per game.
     * Prevents concurrent generation across all connections to the same gameId.
     */
    private static final Map<String, AtomicBoolean> GENERATION_LOCKS = new ConcurrentHashMap<>();

    @Inject
    WebSocketConnection connection;

    @Inject
    OpenConnections openConnections;

    @Inject
    MarkdownAugmenter prettify;

    @Inject
    GameEngine gameEngine;

    String gameId;
    GameState gameState;

    /**
     * Called when a client connects to the WebSocket.
     */
    @OnOpen
    public Uni<PlayWsServerMessage> onOpen(@PathParam String gameId) {
        Log.infof("WebSocket connection opened (connection: %s, gameId: %s)", connection.id(), gameId);

        this.gameId = gameId;
        this.gameState = gameEngine.getGameState(gameId);
        if (gameState == null) {
            Log.warnf("Game not found: %s", gameId);
            return Uni.createFrom().item(
                    new PlayWsServerMessage.Error(null, "Game not found: " + gameId));
        }

        GENERATION_LOCKS.computeIfAbsent(gameId, k -> new AtomicBoolean(false));
        ACTIVE_CONNECTIONS.merge(gameId, 1, Integer::sum);

        String phase = gameState.getGamePhase().name();
        var initSession = new PlayWsServerMessage.Session(connection.id(), gameId, gameState.getAdventureName(), phase);
        return Uni.createFrom().item(initSession);
    }

    /**
     * Called when a client disconnects.
     */
    @OnClose
    public void onClose() {
        Log.infof("WebSocket connection closed (connection: %s)", connection.id());
        if (gameId == null) {
            return;
        }

        int remaining = ACTIVE_CONNECTIONS.merge(gameId, -1, Integer::sum);
        if (remaining <= 0) {
            ACTIVE_CONNECTIONS.remove(gameId);
            HISTORY.remove(gameId);
            AtomicBoolean lock = GENERATION_LOCKS.remove(gameId);
            if (lock != null) {
                lock.set(false);
            }
        }
    }

    /**
     * Called when an error occurs during WebSocket processing.
     */
    @OnError
    public PlayWsServerMessage onError(Throwable error) {
        Log.errorf(error, "WebSocket error for connection: %s",
                connection.id());
        return new PlayWsServerMessage.Error(null, "Internal error: " + error.getMessage());
    }

    /**
     * Handles incoming client messages.
     * Routes to appropriate handler based on message type.
     */
    @OnTextMessage
    @RunOnVirtualThread
    public Multi<PlayWsServerMessage> onMessage(PlayWsClientMessage message) {
        return switch (message) {
            case PlayWsClientMessage.HistoryRequest req -> handleHistoryRequest(req, 100);
            case PlayWsClientMessage.UserMessage msg -> handleUserMessage(msg);
            default -> Multi.createFrom().item(new PlayWsServerMessage.Error(null, "Unsupported message type"));
        };
    }

    private Multi<PlayWsServerMessage> handleHistoryRequest(PlayWsClientMessage.HistoryRequest historyRequest, int limit) {
        List<PlayWsServerMessage.HistoryMessage> allMessages = HISTORY.getOrDefault(gameId, List.of());
        if (allMessages.isEmpty()) {
            return Multi.createFrom().item(new PlayWsServerMessage.History(List.of()));
        }
        int start = Math.max(0, allMessages.size() - limit);
        return Multi.createFrom().item(new PlayWsServerMessage.History(allMessages.subList(start, allMessages.size())));
    }

    private Multi<PlayWsServerMessage> handleUserMessage(PlayWsClientMessage.UserMessage userMessage) {
        String playerInput = userMessage.text();
        if (playerInput == null || playerInput.isBlank()) {
            return Multi.createFrom().item(new PlayWsServerMessage.Error(null, "Message text is required"));
        }
        boolean resuming = HISTORY.getOrDefault(gameId, List.of()).isEmpty();

        // Enforce single in-flight generation per gameId
        AtomicBoolean lock = GENERATION_LOCKS.computeIfAbsent(gameId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            return Multi.createFrom().item(
                    new PlayWsServerMessage.Error(null, "Generation already in progress for this game"));
        }

        String assistantId = UUID.randomUUID().toString();
        try {
            Log.infof("User message received (id: %s): %s", assistantId, truncate(playerInput, 100));
            if (!playerInput.startsWith("/")) {
                appendToHistory("user", playerInput);
            }

            broadcastToGameId(new PlayWsServerMessage.UserEcho(connection.id(), playerInput));
            broadcastToGameId(new PlayWsServerMessage.AssistantStart(assistantId));

            GameEventEmitter emitter = text -> broadcastToGameId(new PlayWsServerMessage.AssistantDelta(assistantId, text));
            GameResponse response = gameEngine.processRequest(gameState, playerInput, emitter, resuming);

            if (response instanceof GameResponse.Error error) {
                broadcastToGameId(new PlayWsServerMessage.Error(assistantId, error.message()));
            } else if (response instanceof GameResponse.Reply reply) {
                // Send effects BEFORE narrative (e.g., roll results shown first)
                for (GameEffect effect : reply.effects()) {
                    PlayWsServerMessage outbound = toServerMessage(effect);
                    if (outbound != null) {
                        broadcastToGameId(outbound);
                    }
                }

                String assistantMarkdown = reply.assistantMarkdown() == null ? "" : reply.assistantMarkdown();
                Log.debugf("Reply markdown length: %d, first 100 chars: %s",
                        assistantMarkdown.length(),
                        assistantMarkdown.substring(0, Math.min(100, assistantMarkdown.length())));
                String assistantHtml = prettify.markdownToHtml(assistantMarkdown);
                Log.debugf("Reply HTML length: %d", assistantHtml.length());
                broadcastToGameId(new PlayWsServerMessage.AssistantDone(assistantId, assistantMarkdown, assistantHtml));

                appendToHistory("assistant", assistantMarkdown, assistantHtml);
            } else {
                broadcastToGameId(new PlayWsServerMessage.Error(assistantId, "Unsupported response from GameEngine"));
            }
        } catch (Exception e) {
            Log.errorf(e, "Error handling user message for gameId: %s", gameId);
            broadcastToGameId(new PlayWsServerMessage.Error(assistantId, "Internal error: " + e.getMessage()));
        } finally {
            lock.set(false);
        }

        return Multi.createFrom().empty();
    }

    private static PlayWsServerMessage toServerMessage(GameEffect effect) {
        return switch (effect) {
            case HtmlFragment fragment -> new PlayWsServerMessage.AssistantExtraHtml(fragment.slot(), fragment.html());
            default -> null;
        };
    }

    private void appendToHistory(String role, String markdown) {
        appendToHistory(role, markdown, prettify.markdownToHtml(markdown));
    }

    private void appendToHistory(String role, String markdown, String html) {
        if (gameId == null) {
            return;
        }
        HISTORY.compute(gameId, (k, messages) -> {
            List<PlayWsServerMessage.HistoryMessage> list = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            list.add(new PlayWsServerMessage.HistoryMessage(role, markdown, html, Instant.now()));
            if (list.size() > MAX_HISTORY_MESSAGES) {
                list = list.subList(list.size() - MAX_HISTORY_MESSAGES, list.size());
            }
            return list.isEmpty() ? List.of() : List.copyOf(list);
        });
    }

    /**
     * sendAndAwait to preserve order when broadcast
     *
     * @param broadcaster
     * @param messages
     */
    private void broadcastToGameId(PlayWsServerMessage message) {
        String endpointId = connection.endpointId();
        openConnections.stream()
                .filter(c -> endpointId.equals(c.endpointId()))
                .filter(c -> gameId.equals(c.pathParam("gameId")))
                .forEach(c -> {
                    c.sendTextAndAwait(message);
                });
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
