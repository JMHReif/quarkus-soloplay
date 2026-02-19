package dev.ebullient.soloplay.ai.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.logging.Log;

/**
 * Persists LangChain4j chat memory to Neo4j.
 *
 * Stores conversation history as a ChatMemory node with serialized JSON messages.
 * Memory is keyed by storyThreadId (the @MemoryId parameter from PlayAgent).
 *
 * Node structure:
 * (:ChatMemory {id: "story-thread-slug", messagesJson: "[...]", updatedAt: ...})
 */
@ApplicationScoped
public class Neo4jChatMemoryStore implements ChatMemoryStore {
    @Inject
    SessionFactory sessionFactory;

    @Inject
    Event<ChatMemoryCompactedEvent> compactedEvent;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = memoryId.toString();
        Log.debugf("Getting messages for memoryId: %s", id);

        Session session = sessionFactory.openSession();
        Iterable<Map<String, Object>> results = session.query(
                "MATCH (m:ChatMemory {id: $id}) RETURN m.messagesJson AS messagesJson",
                Map.of("id", id));

        for (Map<String, Object> row : results) {
            String messagesJson = (String) row.get("messagesJson");
            if (messagesJson != null && !messagesJson.isBlank()) {
                List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(messagesJson);
                Log.debugf("Retrieved %d messages for memoryId: %s", messages.size(), id);
                return messages;
            }
        }

        Log.debugf("No messages found for memoryId: %s", id);
        return List.of();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();
        Instant now = Instant.now();

        Log.debugf("Updating %d messages for memoryId: %s", messages.size(), id);

        // Detect compaction by comparing with previously stored messages
        List<ChatMessage> previousMessages = getMessages(memoryId);
        List<ChatMessage> droppedMessages = detectDroppedMessages(previousMessages, messages);

        if (!droppedMessages.isEmpty()) {
            Log.infof("Memory compaction detected for %s: %d messages dropped",
                    id, droppedMessages.size());
            compactedEvent.fire(new ChatMemoryCompactedEvent(id, droppedMessages));
        }

        // Persist the new messages
        String messagesJson = ChatMessageSerializer.messagesToJson(messages);
        Session session = sessionFactory.openSession();
        session.query(
                """
                        MERGE (m:ChatMemory {id: $id})
                        SET m.messagesJson = $messagesJson,
                            m.updatedAt = $updatedAt
                        """,
                Map.of(
                        "id", id,
                        "messagesJson", messagesJson,
                        "updatedAt", now.toString()));

        // Link ChatMemory to its Game via HAS_MEMORY relationship.
        // memoryId is either "gameId" or "gameId-character" — strip suffix to get gameId.
        String gameId = id.endsWith("-character") ? id.substring(0, id.length() - "-character".length()) : id;
        session.query(
                """
                        MATCH (g:Game {gameId: $gameId})
                        MATCH (m:ChatMemory {id: $id})
                        MERGE (g)-[:HAS_MEMORY]->(m)
                        """,
                Map.of("gameId", gameId, "id", id));
    }

    /**
     * Detect messages that were dropped during compaction.
     * Compares the start of the previous list with the new list to find
     * messages that are no longer present.
     */
    private List<ChatMessage> detectDroppedMessages(List<ChatMessage> previous, List<ChatMessage> current) {
        if (previous.isEmpty() || current.isEmpty()) {
            return List.of();
        }

        // Find where the current list starts in the previous list
        // The current list should be a suffix of what was there + new messages
        // Messages at the start of previous that aren't in current were dropped

        List<ChatMessage> dropped = new ArrayList<>();

        // Find the first message in current that matches something in previous
        // All previous messages before that match point were dropped
        ChatMessage firstCurrent = current.get(0);

        for (int i = 0; i < previous.size(); i++) {
            ChatMessage prev = previous.get(i);
            if (messagesEqual(prev, firstCurrent)) {
                // Found the match point - everything before i was dropped
                return dropped;
            }
            dropped.add(prev);
        }

        // If we didn't find a match, assume all previous messages were dropped
        // (this shouldn't normally happen but handles edge cases)
        return dropped;
    }

    /**
     * Compare two ChatMessages for equality.
     * Uses JSON serialization for comparison since ChatMessage subtypes
     * have different accessor methods (UserMessage.singleText(), AiMessage.text(), etc.)
     */
    private boolean messagesEqual(ChatMessage a, ChatMessage b) {
        if (a.type() != b.type()) {
            return false;
        }
        // Use JSON serialization for reliable comparison
        String jsonA = ChatMessageSerializer.messageToJson(a);
        String jsonB = ChatMessageSerializer.messageToJson(b);
        return jsonA.equals(jsonB);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = memoryId.toString();
        Log.debugf("Deleting messages for memoryId: %s", id);

        Session session = sessionFactory.openSession();
        session.query(
                "MATCH (m:ChatMemory {id: $id}) DETACH DELETE m",
                Map.of("id", id));
    }
}
