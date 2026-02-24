package dev.ebullient.soloplay.ai.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.logging.Log;

/**
 * Persists LangChain4j chat memory to Neo4j as a chain of individual message nodes.
 *
 * Each message is stored as a separate :ChatMessage node, chained via :NEXT_MESSAGE
 * relationships. The first node in the chain is linked from the Game via :HAS_MEMORY.
 *
 * Node structure:
 * (:Game)-[:HAS_MEMORY]->(M1:ChatMessage)-[:NEXT_MESSAGE]->(M2)-[:NEXT_MESSAGE]->(M3)
 *
 * Each ChatMessage node carries:
 * {id, memoryId, sequence, messageJson, messageType, createdAt}
 */
@ApplicationScoped
public class Neo4jChatMemoryStore implements ChatMemoryStore {
    @Inject
    SessionFactory sessionFactory;

    @Inject
    Event<ChatMemoryCompactedEvent> compactedEvent;

    /**
     * Matches bulky template sections that repeat identically each turn.
     * Strips: ADVENTURE SEGMENTS, CAMPAIGN NOTES, CURRENT LOCATION,
     * PLAYER CHARACTERS, CURRENT ADVENTURE SEGMENT.
     * Stops at the next === header, unique content markers, or end of string.
     */
    private static final Pattern TEMPLATE_SECTION = Pattern.compile(
            "=== (?:ADVENTURE SEGMENTS|CAMPAIGN NOTES[^\\n]*|CURRENT LOCATION"
                    + "|PLAYER CHARACTERS|CURRENT ADVENTURE SEGMENT) ===\\n"
                    + "[\\s\\S]*?"
                    + "(?==== [A-Z]|Player says:|The player rolled for:|Welcome the player|RESPOND matching|\\Z)");

    private record StoredMessage(long sequence, String messageJson, String messageType) {
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = memoryId.toString();
        Log.debugf("Getting messages for memoryId: %s", id);

        Session session = sessionFactory.openSession();
        Iterable<Map<String, Object>> results = session.query(
                """
                        MATCH (m:ChatMessage {memoryId: $id})
                        RETURN m.messageJson AS messageJson, m.sequence AS sequence
                        ORDER BY m.sequence ASC
                        """,
                Map.of("id", id));

        List<ChatMessage> messages = new ArrayList<>();
        for (Map<String, Object> row : results) {
            String messageJson = (String) row.get("messageJson");
            if (messageJson != null && !messageJson.isBlank()) {
                messages.add(ChatMessageDeserializer.messagesFromJson("[" + messageJson + "]").get(0));
            }
        }

        Log.debugf("Retrieved %d messages for memoryId: %s", messages.size(), id);
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();
        Log.debugf("Updating %d messages for memoryId: %s", messages.size(), id);

        if (messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }

        // 1. Read existing chain
        List<StoredMessage> existing = readExistingChain(id);

        // 2. Detect compaction: LangChain4j calls updateMessages() once per message
        //    add (user, then AI). Each call adds exactly 1 message to the window.
        //    If incoming <= existing, at least 1 message was evicted.
        if (existing.size() >= 2) {
            int droppedCount = existing.size() + 1 - messages.size();
            if (droppedCount > 0) {
                Log.debugf("Memory eviction for %s: %d message(s) evicted", id, droppedCount);
                // Collect only dropped AiMessages (which contain turn summaries)
                List<ChatMessage> droppedAiMessages = new ArrayList<>();
                for (StoredMessage sm : existing) {
                    if (droppedAiMessages.size() >= droppedCount)
                        break;
                    if ("SYSTEM".equals(sm.messageType()) || "USER".equals(sm.messageType()))
                        continue;
                    droppedAiMessages.add(
                            ChatMessageDeserializer.messagesFromJson("[" + sm.messageJson() + "]").get(0));
                }
                if (!droppedAiMessages.isEmpty()) {
                    compactedEvent.fire(
                            new ChatMemoryCompactedEvent(extractGameId(id), droppedAiMessages));
                }
            }
        }

        // 3. Full replacement: delete all existing, create all incoming.
        //    With max-messages typically <=10, this is cheap and avoids
        //    fragile JSON-equality diffs that break on serialization round-trips.
        Session session = sessionFactory.openSession();
        try (Transaction tx = session.beginTransaction()) {
            if (!existing.isEmpty()) {
                session.query(
                        "MATCH (m:ChatMessage {memoryId: $id}) DETACH DELETE m",
                        Map.of("id", id));
            }

            Instant now = Instant.now();
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                // Strip bulky template content from user messages before persisting.
                // The current turn's full template is sent to the LLM, but stored history
                // keeps only the unique content (player input, roll details, etc.)
                if (msg instanceof UserMessage um) {
                    msg = UserMessage.from(stripTemplateContent(um.singleText()));
                }
                String msgJson = ChatMessageSerializer.messageToJson(msg);
                String messageType = msg.type().name();
                long seq = i + 1;
                String nodeId = id + ":msg-" + seq;

                session.query(
                        """
                                OPTIONAL MATCH (tail:ChatMessage {memoryId: $id})
                                WHERE NOT (tail)-[:NEXT_MESSAGE]->()
                                WITH tail LIMIT 1
                                CREATE (new:ChatMessage {
                                    id: $nodeId,
                                    memoryId: $id,
                                    sequence: $seq,
                                    messageJson: $json,
                                    messageType: $type,
                                    createdAt: $now
                                })
                                FOREACH (_ IN CASE WHEN tail IS NOT NULL THEN [1] ELSE [] END |
                                    CREATE (tail)-[:NEXT_MESSAGE]->(new)
                                )
                                """,
                        Map.of(
                                "id", id,
                                "nodeId", nodeId,
                                "seq", seq,
                                "json", msgJson,
                                "type", messageType,
                                "now", now.toString()));
            }

            // Repoint HAS_MEMORY to head (node with no incoming NEXT_MESSAGE).
            // Silently skips if Game node doesn't exist.
            String gameId = extractGameId(id);
            session.query(
                    """
                            MATCH (g:Game {gameId: $gameId})
                            OPTIONAL MATCH (g)-[old:HAS_MEMORY]->(:ChatMessage {memoryId: $id})
                            DELETE old
                            WITH g
                            MATCH (head:ChatMessage {memoryId: $id})
                            WHERE NOT (:ChatMessage)-[:NEXT_MESSAGE]->(head)
                            MERGE (g)-[:HAS_MEMORY]->(head)
                            """,
                    Map.of("gameId", gameId, "id", id));

            tx.commit();
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = memoryId.toString();
        Log.debugf("Deleting messages for memoryId: %s", id);

        Session session = sessionFactory.openSession();
        session.query(
                "MATCH (m:ChatMessage {memoryId: $id}) DETACH DELETE m",
                Map.of("id", id));
    }

    private List<StoredMessage> readExistingChain(String id) {
        Session session = sessionFactory.openSession();
        Iterable<Map<String, Object>> results = session.query(
                """
                        MATCH (m:ChatMessage {memoryId: $id})
                        RETURN m.messageJson AS messageJson, m.sequence AS sequence, m.messageType AS messageType
                        ORDER BY m.sequence ASC
                        """,
                Map.of("id", id));

        List<StoredMessage> chain = new ArrayList<>();
        for (Map<String, Object> row : results) {
            long sequence = ((Number) row.get("sequence")).longValue();
            String messageJson = (String) row.get("messageJson");
            String messageType = (String) row.get("messageType");
            chain.add(new StoredMessage(sequence, messageJson, messageType));
        }
        return chain;
    }

    /**
     * Strip repeated template content from a user message, keeping only the
     * unique parts (player input, roll details, action header, adventure name).
     */
    String stripTemplateContent(String text) {
        String stripped = TEMPLATE_SECTION.matcher(text).replaceAll("");
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.trim();
    }

    private String extractGameId(String memoryId) {
        return memoryId.endsWith("-character")
                ? memoryId.substring(0, memoryId.length() - "-character".length())
                : memoryId;
    }
}
