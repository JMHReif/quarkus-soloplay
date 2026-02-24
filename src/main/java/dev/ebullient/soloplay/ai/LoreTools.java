package dev.ebullient.soloplay.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.ogm.session.SessionFactory;

import dev.ebullient.soloplay.LoreRepository;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkus.logging.Log;

/**
 * AI Tools for lore document retrieval.
 * Provides cross-reference resolution and semantic search for campaign documents.
 */
@ApplicationScoped
public class LoreTools {

    @Inject
    LoreRepository loreRepository;

    @Inject
    SessionFactory sessionFactory;

    @Inject
    EmbeddingModel embeddingModel;

    @ConfigProperty(name = "campaign.setting.minScore", defaultValue = "0.3")
    Double minScore;

    @ConfigProperty(name = "campaign.setting.maxResults", defaultValue = "5")
    int maxResults;

    @ConfigProperty(name = "quarkus.langchain4j.neo4j.index-name", defaultValue = "document-index")
    String indexName;

    // Track the last directory context for relative path resolution
    private String lastDirectory = "";

    @Tool("""
            GM reference: retrieve a specific lore document by filename.
            Use the returned content to inform your narration — do NOT show raw text to the player.

            For relative paths like "./file.md", provide the full path from directory context.
            Example: if you fetched "vehicles/vehicles.md" and it links to "./damselfly-ship-aag.md",
            call getLoreDocument("vehicles/damselfly-ship-aag.md")
            """)
    public String getLoreDocument(String filename) {
        // Handle relative paths if we have directory context
        String resolvedFilename = filename;
        if (filename.startsWith("./")) {
            resolvedFilename = lastDirectory + filename.substring(2);
        }

        // Update directory context for future relative path resolution
        int lastSlash = resolvedFilename.lastIndexOf('/');
        if (lastSlash > 0) {
            lastDirectory = resolvedFilename.substring(0, lastSlash + 1);
        }

        String content = loreRepository.getDocumentByFilename(resolvedFilename);
        if (content == null) {
            return "Document not found: " + resolvedFilename;
        }
        return content;
    }

    @Tool("""
            GM reference: search your campaign notes by topic.
            Use this to look up monster stats, item properties, spell effects, NPC backgrounds,
            or location details when you need them for narration or encounter design.
            Use the returned content to inform your GMing — do NOT show raw text to the player.
            """)
    public String searchLore(String query) {
        Log.debugf("Tool searchLore: %s", query);

        float[] queryEmbedding = embeddingModel.embed(query).content().vector();

        var session = sessionFactory.openSession();
        String cypher = """
                CALL db.index.vector.queryNodes($indexName, $maxResults, $embedding)
                YIELD node, score
                WHERE score >= $minScore
                RETURN node.text AS text, node.name AS name,
                       node.filename AS filename, score
                ORDER BY score DESC
                """;

        Iterable<Map<String, Object>> rows = session.query(cypher, Map.of(
                "indexName", indexName,
                "embedding", queryEmbedding,
                "maxResults", maxResults,
                "minScore", minScore));

        List<String> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String text = (String) row.get("text");
            if (text != null && !text.isBlank()) {
                String name = (String) row.get("name");
                if (name != null) {
                    results.add("--- " + name + " ---\n" + text);
                } else {
                    results.add(text);
                }
            }
        }

        if (results.isEmpty()) {
            return "No lore found for: " + query;
        }

        Log.debugf("Tool searchLore: %d results", results.size());
        return String.join("\n\n", results);
    }
}
