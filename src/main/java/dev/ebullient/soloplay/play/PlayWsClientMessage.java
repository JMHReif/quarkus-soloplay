package dev.ebullient.soloplay.play;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import dev.ebullient.soloplay.play.model.Stash;

/**
 * Messages sent from client to server over the Play WebSocket.
 *
 * Protocol:
 * - {@link HistoryRequest}: Request conversation history
 * - {@link UserMessage}: Send a user message to the server
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PlayWsClientMessage.HistoryRequest.class, name = "history_request"),
        @JsonSubTypes.Type(value = PlayWsClientMessage.UserMessage.class, name = "user_message")
})
public sealed interface PlayWsClientMessage {

    /**
     * Request conversation history.
     *
     * @param limit Maximum number of messages to return (default: 100)
     */
    record HistoryRequest(Integer limit) implements PlayWsClientMessage {
        public HistoryRequest {
            if (limit == null) {
                limit = 100;
            }
        }
    }

    /**
     * User message to the server.
     *
     * @param text The player's message text
     * @param stash Round-trip state from previous server response (may be null)
     */
    record UserMessage(String text, Stash stash) implements PlayWsClientMessage {
    }
}
