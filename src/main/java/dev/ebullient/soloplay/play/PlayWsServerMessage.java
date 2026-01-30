package dev.ebullient.soloplay.play;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import dev.ebullient.soloplay.play.model.Stash;

/**
 * Messages sent from server to client over the Play WebSocket.
 *
 * Protocol:
 * - {@link Session}: Sent on connection open with basic session info
 * - {@link History}: Response to history_request with past messages
 * - {@link UserEcho}: Echo of the user message (includes sender)
 * - {@link AssistantStart}: Indicates assistant response is starting (includes message ID)
 * - {@link AssistantDelta}: Streaming chunk(s) from assistant response
 * - {@link AssistantDone}: Assistant response complete with final markdown/HTML
 * - {@link StatefulUpdate}: Stateful effect for client to store and send back (round-trip state)
 * - {@link Error}: Error occurred during processing
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PlayWsServerMessage.Session.class, name = "session"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.History.class, name = "history"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.AssistantExtraHtml.class, name = "html_fragment"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.UserEcho.class, name = "user_echo"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.AssistantStart.class, name = "assistant_start"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.AssistantDelta.class, name = "assistant_delta"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.AssistantDone.class, name = "assistant_done"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.StatefulUpdate.class, name = "stateful_update"),
        @JsonSubTypes.Type(value = PlayWsServerMessage.Error.class, name = "error")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface PlayWsServerMessage {

    /**
     * Sent immediately on WebSocket connection open.
     * Contains basic session metadata for the connected client.
     *
     * @param sessionId Server-generated session identifier (currently the connection id)
     * @param gameId Stable game identifier (from the WebSocket path parameter)
     * @param adventureName Display name for the adventure (may be null for new games)
     * @param gamePhase Current phase of the game (e.g., "CHARACTER_CREATION", "UNKNOWN")
     */
    record Session(
            String sessionId,
            String gameId,
            String adventureName,
            String gamePhase) implements PlayWsServerMessage {
    }

    /**
     * Response to a history_request.
     * Contains past conversation messages for this session.
     *
     * @param messages List of past messages in chronological order
     */
    record History(List<HistoryMessage> messages) implements PlayWsServerMessage {
    }

    /**
     * Echo of the user message. Used to confirm receipt and unify client-side flow.
     *
     * @param senderSessionId Session ID of the client that sent the message
     * @param text The user's message text
     */
    record UserEcho(String senderSessionId, String text) implements PlayWsServerMessage {
    }

    /**
     * A single message in the conversation history.
     *
     * @param role "user" or "assistant"
     * @param markdown Original markdown content
     * @param html Rendered HTML content
     * @param ts Timestamp when message was created
     */
    record HistoryMessage(
            String role,
            String markdown,
            String html,
            Instant ts) {
    }

    /**
     * Indicates the assistant is starting to respond.
     * The client should prepare to receive streaming deltas.
     *
     * @param id Server-generated message ID for correlation
     */
    record AssistantStart(String id) implements PlayWsServerMessage {
    }

    /**
     * A streaming chunk of the assistant's response.
     * Multiple deltas may be sent before AssistantDone.
     *
     * @param id Message ID (same as AssistantStart)
     * @param text Token(s) to append to the response
     */
    record AssistantDelta(String id, String text) implements PlayWsServerMessage {
    }

    /**
     * Assistant response is complete.
     * Contains the full response with final formatting.
     *
     * @param id Message ID (same as AssistantStart)
     * @param markdown Full response as markdown
     * @param html Full response rendered as HTML
     */
    record AssistantDone(
            String id,
            String markdown,
            String html) implements PlayWsServerMessage {
    }

    /**
     * An error occurred during processing.
     *
     * @param id Message ID if error is related to a specific request (may be null)
     * @param message Human-readable error description
     */
    record Error(String id, String message) implements PlayWsServerMessage {
    }

    /**
     * An error occurred during processing.
     *
     * @param id Message ID if error is related to a specific request (may be null)
     * @param message Human-readable error description
     */
    record AssistantExtraHtml(String id, String message) implements PlayWsServerMessage {
    }

    /**
     * Stateful effect for client to store and send back with next message.
     * Used for round-trip state (drafts, pending rolls, etc.) to keep server stateless.
     *
     * @param slot UI slot identifier (e.g., "actor_draft", "pending_roll")
     * @param html Optional HTML/markdown for display (null if state should be hidden)
     * @param stash The state object to store and round-trip (null clears the slot)
     */
    record StatefulUpdate(String slot, String html, Stash stash) implements PlayWsServerMessage {
    }
}
