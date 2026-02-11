package dev.ebullient.soloplay.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.ogm.session.SessionFactory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.quarkus.logging.Log;

@ApplicationScoped
public class LoreRetriever implements Supplier<RetrievalAugmentor> {
    private final RetrievalAugmentor augmentor;

    /**
     * Custom prompt template that clearly frames RAG content as reference material.
     * This helps prevent the LLM from echoing the content instead of using it.
     */
    private static final PromptTemplate RAG_PROMPT_TEMPLATE = PromptTemplate.from("""
            {{userMessage}}

            === REFERENCE MATERIAL (use as context, do NOT echo or summarize) ===
            Draw on this content to provide accurate, detailed answers.
            Synthesize and explain rather than copying verbatim.

            {{contents}}

            === END REFERENCE MATERIAL ===
            """);

    /**
     * Keyword patterns for detecting a document label from queries.
     * Maps pattern to the "label" metadata value (from YAML frontmatter).
     */
    private static final Map<Pattern, String> LABEL_PATTERNS = Map.of(
            Pattern.compile("\\b(monster|creature|enemy|beast|fiend|undead|goblin|orc)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Monster",
            Pattern.compile("\\b(item|weapon|armor|equipment|gear|sword|axe|shield|potion)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Item",
            Pattern.compile("\\b(spell|cantrip|ritual|incantation)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Spell",
            Pattern.compile("\\b(vehicle|ship|boat|airship|wagon|cart)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Vehicle",
            Pattern.compile(
                    "\\b(class|subclass|fighter|wizard|rogue|cleric|barbarian|paladin|ranger|monk|bard|druid|sorcerer|warlock)(?:es)?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Class",
            Pattern.compile("\\b(race|species|elf|dwarf|halfling|human|dragonborn|tiefling|gnome)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Race",
            Pattern.compile("\\b(background|acolyte|criminal|soldier|noble|sage|hermit|outlander)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            "Background",
            Pattern.compile("\\b(feat|feats)\\b",
                    Pattern.CASE_INSENSITIVE),
            "Feat");

    /**
     * Detect label from query text using keyword patterns.
     */
    private static String detectLabel(String query) {
        for (Map.Entry<Pattern, String> entry : LABEL_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(query).find()) {
                return entry.getValue();
            }
        }
        return null;
    }

    public LoreRetriever(
            SessionFactory sessionFactory,
            EmbeddingModel model,
            @ConfigProperty(name = "campaign.setting.minScore", defaultValue = "0.3") Double minScore,
            @ConfigProperty(name = "campaign.setting.maxResults", defaultValue = "5") int maxResults,
            @ConfigProperty(name = "quarkus.langchain4j.neo4j.index-name", defaultValue = "vector") String indexName) {

        // Create Cypher-based retriever with auto-detection and fallback
<<<<<<< Updated upstream
        // Explicit filter can be specified by prefixing query with [filter:label]
        ContentRetriever cypherRetriever = new ContentRetriever() {
            @Override
            public List<Content> retrieve(Query query) {
                String queryText = query.text();

                // Parse optional explicit filter prefix: [filter:label]
                String label = null;
                boolean explicitFilter = false;
                if (queryText.startsWith("[filter:")) {
                    int endBracket = queryText.indexOf(']');
                    if (endBracket > 8) {
                        label = queryText.substring(8, endBracket).trim();
                        queryText = queryText.substring(endBracket + 1).trim();
                        explicitFilter = true;
                    }
                }

                // Auto-detect label from query keywords if not explicitly set
                if (label == null) {
                    label = detectLabel(queryText);
                }

                Log.debugf("RAG Query: %s", queryText);
                if (label != null) {
                    Log.debugf("RAG Filter: label = %s (explicit=%s)", label, explicitFilter);
                }

                // Generate query embedding (without filter prefix)
                float[] queryEmbedding = model.embed(queryText).content().vector();

                // Execute vector similarity search with optional filter
                List<Content> results = executeVectorSearch(sessionFactory, indexName, queryEmbedding,
                        label, maxResults, minScore);

                // Fallback to unfiltered search if auto-detected filter returns few results
                if (!explicitFilter && label != null && results.size() < 2) {
                    Log.debugf("Auto-filtered search returned %d results, falling back to unfiltered", results.size());
                    results = executeVectorSearch(sessionFactory, indexName, queryEmbedding,
                            null, maxResults, minScore);
                }

                Log.debugf("RAG Retrieved %d results", results.size());
                for (int i = 0; i < results.size(); i++) {
                    Content c = results.get(i);
                    String text = c.textSegment().text();
                    Log.debugf("  [%d] %s...", i, text.substring(0, Math.min(100, text.length())));
                }
                return results;
=======
        // Explicit filter can be specified by prefixing query with [filter:contentType]
        ContentRetriever cypherRetriever = query -> {
            String queryText = query.text();

            // Parse optional explicit filter prefix: [filter:contentType]
            String contentType = null;
            boolean explicitFilter = false;
            if (queryText.startsWith("[filter:")) {
                int endBracket = queryText.indexOf(']');
                if (endBracket > 8) {
                    contentType = queryText.substring(8, endBracket).trim();
                    queryText = queryText.substring(endBracket + 1).trim();
                    explicitFilter = true;
                }
>>>>>>> Stashed changes
            }

            // Auto-detect contentType from query keywords if not explicitly set
            if (contentType == null) {
                contentType = detectContentType(queryText);
            }

            Log.debugf("RAG Query: %s", queryText);
            if (contentType != null) {
                Log.debugf("RAG Filter: contentType = %s (explicit=%s)", contentType, explicitFilter);
            }

            // Generate query embedding (without filter prefix)
            float[] queryEmbedding = model.embed(queryText).content().vector();

            // Execute vector similarity search with optional filter
            List<Content> results = executeVectorSearch(sessionFactory, indexName, queryEmbedding,
                    contentType, maxResults, minScore);

            // Fallback to unfiltered search if auto-detected filter returns few results
            if (!explicitFilter && contentType != null && results.size() < 2) {
                Log.debugf("Auto-filtered search returned %d results, falling back to unfiltered", results.size());
                results = executeVectorSearch(sessionFactory, indexName, queryEmbedding,
                        null, maxResults, minScore);
            }

            Log.debugf("RAG Retrieved %d results", results.size());
            for (int i = 0; i < results.size(); i++) {
                Content c = results.get(i);
                String text = c.textSegment().text();
                Log.debugf("  [%d] %s...", i, text.substring(0, Math.min(100, text.length())));
            }
            return results;
        };

        // Custom content injector that frames RAG content clearly
        var contentInjector = DefaultContentInjector.builder()
                .promptTemplate(RAG_PROMPT_TEMPLATE)
                .metadataKeysToInclude(List.of("name", "source", "filename", "label"))
                .build();

        augmentor = DefaultRetrievalAugmentor
                .builder()
                .contentRetriever(cypherRetriever)
                .contentInjector(contentInjector)
                .build();
    }

    /**
     * Execute vector similarity search with optional label filtering.
     * Fetches neighboring chunks (via NEXT relationships) to provide additional context.
     */
    private List<Content> executeVectorSearch(SessionFactory sessionFactory, String indexName,
            float[] queryEmbedding, String label, int maxResults, double minScore) {
        var session = sessionFactory.openSession();
        List<Content> results = new ArrayList<>();

        try {
            String cypher;
            Map<String, Object> params;

            if (label != null && !label.isBlank()) {
                // Filter by label metadata property, include neighbors
                // Use larger candidate pool (5x) to ensure enough matches after filtering
                cypher = """
                        CALL db.index.vector.queryNodes($indexName, $maxResults * 5, $embedding)
                        YIELD node, score
                        WHERE score >= $minScore AND toLower(node.label) = toLower($label)
                        OPTIONAL MATCH (prev)-[:NEXT]->(node)
                        OPTIONAL MATCH (node)-[:NEXT]->(next)
                        RETURN node.text AS text, node.name AS name, node.filename AS filename,
                               node.label AS label, node.sourceFile AS sourceFile, score,
                               prev.text AS prevText, next.text AS nextText
                        ORDER BY score DESC
                        LIMIT $maxResults
                        """;
                params = Map.of(
                        "indexName", indexName,
                        "embedding", queryEmbedding,
                        "maxResults", maxResults,
                        "minScore", minScore,
                        "label", label);
            } else {
                // No filtering, include neighbors
                cypher = """
                        CALL db.index.vector.queryNodes($indexName, $maxResults, $embedding)
                        YIELD node, score
                        WHERE score >= $minScore
                        OPTIONAL MATCH (prev)-[:NEXT]->(node)
                        OPTIONAL MATCH (node)-[:NEXT]->(next)
                        RETURN node.text AS text, node.name AS name, node.filename AS filename,
                               node.label AS label, node.sourceFile AS sourceFile, score,
                               prev.text AS prevText, next.text AS nextText
                        ORDER BY score DESC
                        """;
                params = Map.of(
                        "indexName", indexName,
                        "embedding", queryEmbedding,
                        "maxResults", maxResults,
                        "minScore", minScore);
            }

            Iterable<Map<String, Object>> rows = session.query(cypher, params);

            for (Map<String, Object> row : rows) {
                String text = (String) row.get("text");
                if (text != null && !text.isBlank()) {
                    // Build enriched text with neighboring context
                    StringBuilder enrichedText = new StringBuilder();

                    String prevText = (String) row.get("prevText");
                    if (prevText != null && !prevText.isBlank()) {
                        enrichedText.append(prevText).append("\n\n---\n\n");
                    }

                    enrichedText.append(text);

                    String nextText = (String) row.get("nextText");
                    if (nextText != null && !nextText.isBlank()) {
                        enrichedText.append("\n\n---\n\n").append(nextText);
                    }

                    Metadata metadata = new Metadata();
                    if (row.get("name") != null) {
                        metadata.put("name", row.get("name").toString());
                    }
                    if (row.get("filename") != null) {
                        metadata.put("filename", row.get("filename").toString());
                    }
                    if (row.get("label") != null) {
                        metadata.put("label", row.get("label").toString());
                    }
                    if (row.get("sourceFile") != null) {
                        metadata.put("source", row.get("sourceFile").toString());
                    }
                    // Track if neighbors were included
                    if (prevText != null || nextText != null) {
                        metadata.put("hasContext", "true");
                    }

                    TextSegment segment = TextSegment.from(enrichedText.toString(), metadata);
                    results.add(Content.from(segment));
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Error executing vector search: %s", e.getMessage());
        }

        return results;
    }

    @Override
    public RetrievalAugmentor get() {
        return augmentor;
    }
}
