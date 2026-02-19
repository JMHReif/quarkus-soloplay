package dev.ebullient.soloplay.ai.memory;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.ebullient.soloplay.GameRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.quarkus.logging.Log;

/**
 * Listens for chat memory compaction events and saves turn summaries
 * as "memory" checkpoints so the LLM retains context from evicted messages.
 *
 * When the chat memory window overflows and older messages are dropped,
 * this listener extracts turnSummary fields from AI responses and saves
 * them as a single checkpoint that flows into the game journal.
 */
@ApplicationScoped
public class ChatMemoryCompactionListener {

    @Inject
    GameRepository gameRepository;

    @Inject
    ObjectMapper objectMapper;

    public void onCompaction(@Observes ChatMemoryCompactedEvent event) {
        String gameId = event.gameId();
        List<ChatMessage> droppedMessages = event.droppedMessages();

        Log.infof("Processing compaction for %s: %d messages dropped",
                gameId, droppedMessages.size());

        List<String> summaries = extractTurnSummaries(droppedMessages);
        if (summaries.isEmpty()) {
            Log.infof("No turn summaries found in dropped messages for %s", gameId);
            return;
        }

        // Combine into a concise recap
        StringBuilder recap = new StringBuilder("Earlier story: ");
        for (String summary : summaries) {
            recap.append(summary).append(" ");
        }
        String content = recap.toString().trim();

        // Cap to avoid bloating the journal
        if (content.length() > 1000) {
            content = content.substring(content.length() - 1000);
        }

        // Replace any previous memory checkpoint with this updated one
        gameRepository.clearCheckpoints(gameId, "memory");
        gameRepository.saveCheckpoint(gameId, "memory", content, 0);

        Log.infof("Saved memory checkpoint for %s (%d summaries, %d chars)",
                gameId, summaries.size(), content.length());
    }

    /**
     * Extract turnSummary fields from dropped AiMessages.
     * AiMessage text is the raw JSON response from the LLM.
     */
    private List<String> extractTurnSummaries(List<ChatMessage> messages) {
        List<String> summaries = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage aiMsg && aiMsg.text() != null) {
                String summary = parseTurnSummary(aiMsg.text());
                if (summary != null && !summary.isBlank()) {
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    private String parseTurnSummary(String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode summaryNode = root.get("turnSummary");
            if (summaryNode != null && !summaryNode.isNull()) {
                return summaryNode.asText();
            }
        } catch (Exception e) {
            // Not valid JSON or no turnSummary — skip silently
            Log.debugf("Could not parse turnSummary from AI message: %s", e.getMessage());
        }
        return null;
    }
}
