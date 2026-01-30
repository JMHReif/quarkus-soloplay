package dev.ebullient.soloplay.play.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Marker interface for objects that can be round-tripped through the client.
 * Jackson type info enables polymorphic deserialization when sent via WebSocket.
 *
 * All Stash types are round-tripped via StatefulEffect for server-stateless operation.
 * Each type may have different display semantics (draft panel, HTML widget, etc.)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PlayerActorDraft.class, name = "actor_draft"),
        @JsonSubTypes.Type(value = PendingRollStash.class, name = "pending_roll")
})
public interface Stash {
}
