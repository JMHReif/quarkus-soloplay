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

        Log.debugf("Processing compaction for %s: %d messages dropped",
                gameId, droppedMessages.size());

        List<String> summaries = extractTurnSummaries(droppedMessages);
        if (summaries.isEmpty()) {
            Log.debugf("No turn summaries found in dropped messages for %s", gameId);
            return;
        }

        // Append new summaries to existing memory checkpoint content
        StringBuilder recap = new StringBuilder();
        String existing = findExistingMemoryContent(gameId);
        if (existing != null) {
            recap.append(existing).append(" ");
        } else {
            recap.append("Earlier story: ");
        }
        for (String summary : summaries) {
            recap.append(summary).append(" ");
        }
        String content = recap.toString().trim();

        // Keep only the tail if it exceeds the cap (most recent context wins)
        if (content.length() > 1000) {
            content = content.substring(content.length() - 1000);
        }

        gameRepository.clearCheckpoints(gameId, "memory");
        gameRepository.saveCheckpoint(gameId, "memory", content, 0);

        Log.infof("Saved memory checkpoint for %s (%d new summaries, %d chars total)",
                gameId, summaries.size(), content.length());
    }

    private String findExistingMemoryContent(String gameId) {
        for (var cp : gameRepository.getCheckpoints(gameId)) {
            if ("memory".equals(cp.get("category"))) {
                return (String) cp.get("content");
            }
        }
        return null;
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
