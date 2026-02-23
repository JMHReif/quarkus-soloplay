package dev.ebullient.soloplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.ogm.session.SessionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkus.logging.Log;

@ApplicationScoped
public class IngestService {
    static final String TOOLS_DOC_SEPARATOR = "============\n";

    // Regex pattern for YAML frontmatter
    // (?s) enables DOTALL mode (. matches newlines)
    // ^--- matches opening ---
    // (.*?) captures content (non-greedy)
    // \n--- matches closing ---
    static final java.util.regex.Pattern YAML_FRONTMATTER_PATTERN = java.util.regex.Pattern
            .compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n");

    // Regex pattern for markdown section headers (## through ##### headers)
    // (?m) enables multiline mode (^ matches line starts)
    // Positive lookahead splits before headers without consuming them
    // Matches header levels 2-5: ##, ###, ####, #####
    static final java.util.regex.Pattern SECTION_HEADER_PATTERN = java.util.regex.Pattern
            .compile("(?m)(?=^#{2,5} )");

    @ConfigProperty(name = "campaign.chunk.size", defaultValue = "500")
    int chunkSize;

    @ConfigProperty(name = "campaign.chunk.overlap", defaultValue = "50")
    int chunkOverlap;

    @Inject
    EmbeddingStore<TextSegment> embeddingStore; // Neo4j

    @Inject
    EmbeddingModel embeddingModel; // Ollama nomic-embed-text

    @Inject
    SessionFactory sessionFactory;

    public void ingestFile(String filename, String content) {
        Log.infof("Processing file: %s (size: %d bytes)", filename, content.length());

        boolean isAdventureFile = "adventures.txt".equals(filename);
        List<String> allChunkIds = new ArrayList<>();
        int[] globalSequence = { 0 };

        if (content.contains(TOOLS_DOC_SEPARATOR)) {
            String[] parts = content.split(TOOLS_DOC_SEPARATOR);
            Log.infof("Found %d structured sections in %s", parts.length, filename);
            int processedCount = 0;
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isBlank() && !trimmed.equals("============")) {
                    List<String> chunkIds = processStructuredMarkdown(filename, trimmed, globalSequence, isAdventureFile);
                    allChunkIds.addAll(chunkIds);
                    processedCount++;
                }
            }
            Log.infof("Processed %d non-empty sections from %s", processedCount, filename);
        } else {
            List<String> chunkIds = processStructuredMarkdown(filename, content.trim(), globalSequence, isAdventureFile);
            allChunkIds.addAll(chunkIds);
        }

        // For adventure files, add label and create one long NEXT chain
        if (isAdventureFile && !allChunkIds.isEmpty()) {
            addLabelToNodes(allChunkIds, "Adventure");
            if (allChunkIds.size() > 1) {
                createChunkRelationships(allChunkIds);
            }
            Log.infof("Adventure: %d chunks linked sequentially", allChunkIds.size());
        }

        Log.infof("Completed processing file: %s", filename);
    }

    /**
     * Convert numeric fields from strings to integers in Neo4j.
     * The embedding store may serialize all metadata as strings.
     */
    private void convertNumericFields(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return;
        }

        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            String cypher = """
                    MATCH (d:Document)
                    WHERE d.id IN $nodeIds
                    SET d.partIndex = CASE WHEN d.partIndex IS NOT NULL THEN toInteger(d.partIndex) ELSE null END,
                        d.partNumber = CASE WHEN d.partNumber IS NOT NULL THEN toInteger(d.partNumber) ELSE null END,
                        d.sectionIndex = CASE WHEN d.sectionIndex IS NOT NULL THEN toInteger(d.sectionIndex) ELSE null END,
                        d.chunkIndex = CASE WHEN d.chunkIndex IS NOT NULL THEN toInteger(d.chunkIndex) ELSE null END,
                        d.sequenceNumber = CASE WHEN d.sequenceNumber IS NOT NULL THEN toInteger(d.sequenceNumber) ELSE null END,
                        d.chapterNumber = CASE WHEN d.chapterNumber IS NOT NULL THEN toInteger(d.chapterNumber) ELSE null END,
                        d.index = CASE WHEN d.index IS NOT NULL THEN toInteger(d.index) ELSE null END
                    """;
            session.query(cypher, Map.of("nodeIds", nodeIds));

            tx.commit();
            Log.debugf("Converted numeric fields for %d nodes", nodeIds.size());
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error converting numeric fields: %s", e.getMessage());
        } finally {
            tx.close();
        }
    }

    private List<String> processStructuredMarkdown(String filename, String content, int[] globalSequence,
            boolean isAdventureFile) {
        // Parse YAML frontmatter
        // Note: structured frontmatter includes the real filename
        // Keep ingest sourceFile for traceability + allow re-processing
        Map<String, Object> yamlMetadata = parseYamlFrontmatter(content);
        yamlMetadata.put("sourceFile", filename);
        yamlMetadata.put("canonical", "true");

        Metadata common = Metadata.from(yamlMetadata);

        String cleanContent = removeYamlFrontmatter(content)
                .replaceAll("\\^[a-z0-9]+$", ""); // replace block references

        String prefix = "";
        if (yamlMetadata.containsKey("adventureName")) {
            prefix += "Adventure: %s\n\n".formatted(yamlMetadata.get("adventureName"));
        }
        if (yamlMetadata.containsKey("chapterName")) {
            prefix += "Chapter %s: %s\n\n".formatted(yamlMetadata.get("chapterNumber"), yamlMetadata.get("chapterName"));
        }

        List<TextSegment> segments = new ArrayList<>();
        // Track start index of multi-chunk sections for NEXT relationships
        List<int[]> chunkedSectionRanges = new ArrayList<>();

        if (prefix.length() + cleanContent.length() > chunkSize) {
            String[] sections = SECTION_HEADER_PATTERN.split(cleanContent);
            int sectionIndex = 0;
            for (String section : sections) {
                if (section.isBlank()) {
                    continue;
                }
                String sectionTitle = extractFirstLine(section);
                String lowerTitle = sectionTitle.toLowerCase();
                if (lowerTitle.contains("statblock")
                        || lowerTitle.equals("traits")
                        || lowerTitle.equals("actions")
                        || lowerTitle.equals("legendary actions")
                        || lowerTitle.equals("regional effects")) {
                    Log.infof("Skipping statblock section: %s", sectionTitle);
                    continue;
                }
                String enrichedSection = prefix + section;

                // If section is still too large, chunk it again
                if (enrichedSection.length() > chunkSize * 2) {
                    Log.infof("Section %d '%s' is large (%d chars), splitting into chunks", sectionIndex, sectionTitle,
                            section.length());
                    var doc = Document.from(enrichedSection);
                    var splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
                    List<TextSegment> subSegments = splitter.split(doc);

                    // Track range for NEXT relationships
                    int startIdx = segments.size();

                    int chunkIndex = 0;
                    for (TextSegment subSegment : subSegments) {
                        subSegment.metadata()
                                .put("section", sectionTitle)
                                .put("sectionIndex", sectionIndex)
                                .put("chunkIndex", chunkIndex++)
                                .put("sequenceNumber", globalSequence[0]++)
                                .put("sourceFile", filename)
                                .put("canonical", "true");
                        subSegment.metadata().putAll(yamlMetadata);
                        segments.add(subSegment);
                    }

                    // Record range if more than one chunk
                    if (subSegments.size() > 1) {
                        chunkedSectionRanges.add(new int[] { startIdx, segments.size() - 1 });
                    }
                } else {
                    TextSegment segment = TextSegment.from(
                            enrichedSection,
                            common);
                    segment.metadata()
                            .put("section", sectionTitle)
                            .put("sectionIndex", sectionIndex)
                            .put("chunkIndex", 0)
                            .put("sequenceNumber", globalSequence[0]++);
                    segments.add(segment);
                }
                sectionIndex++;
            }
        } else if (!cleanContent.isBlank()) {
            TextSegment segment = TextSegment.from(
                    prefix + cleanContent,
                    common);
            segment.metadata()
                    .put("sectionIndex", 0)
                    .put("chunkIndex", 0)
                    .put("sequenceNumber", globalSequence[0]++);
            segments.add(segment);
        }

        // Generate embeddings and store
        Log.infof("Generating embeddings for %d segments from %s", segments.size(), filename);

        if (segments.isEmpty()) {
            Log.warnf("No valid segments to embed for %s", filename);
            return List.of();
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<String> chunkIds = embeddingStore.addAll(embeddings, segments);
        Log.infof("Stored %d embeddings for %s", embeddings.size(), filename);

        // Add source-specific label to nodes (e.g., items.txt → :Item)
        String label = deriveLabelFromFilename(filename);
        if (label != null) {
            addLabelToNodes(chunkIds, label);
        }

        // Convert numeric fields from strings to integers
        convertNumericFields(chunkIds);

        // Create NEXT relationships for chunked sections (non-adventure files only)
        // Adventure files create one long NEXT chain at the end in ingestFile()
        if (!isAdventureFile && !chunkedSectionRanges.isEmpty()) {
            createSectionChunkRelationships(chunkIds, chunkedSectionRanges);
        }

        return chunkIds;
    }

    /**
     * Derive a PascalCase singular label from a filename.
     * Example: "items.txt" → "Item", "magic-items.txt" → "MagicItem"
     */
    private String deriveLabelFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }

        // Remove extension
        String baseName = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;

        if (baseName.isBlank()) {
            return null;
        }

        // Singularization rules for common English plural forms
        if (baseName.endsWith("ies")) {
            // abilities → ability
            baseName = baseName.substring(0, baseName.length() - 3) + "y";
        } else if (baseName.endsWith("sses")) {
            // classes → class
            baseName = baseName.substring(0, baseName.length() - 2);
        } else if (baseName.endsWith("xes")) {
            // boxes → box
            baseName = baseName.substring(0, baseName.length() - 2);
        } else if (baseName.endsWith("ches")) {
            // watches → watch
            baseName = baseName.substring(0, baseName.length() - 2);
        } else if (baseName.endsWith("shes")) {
            // dishes → dish
            baseName = baseName.substring(0, baseName.length() - 2);
        } else if (baseName.endsWith("s") && !baseName.endsWith("ss")) {
            // items → item, adventures → adventure, monsters → monster
            baseName = baseName.substring(0, baseName.length() - 1);
        }

        // Convert to PascalCase, handling hyphens and underscores
        // "magic-item" → "MagicItem", "magic_item" → "MagicItem"
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : baseName.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    /**
     * Add an additional label to Document nodes.
     */
    private void addLabelToNodes(List<String> nodeIds, String label) {
        if (nodeIds.isEmpty() || label == null) {
            return;
        }

        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            String cypher = """
                    MATCH (d:Document) WHERE d.id IN $nodeIds
                    SET d:%s
                    """.formatted(label);
            session.query(cypher, Map.of("nodeIds", nodeIds));

            tx.commit();
            Log.infof("Added label :%s to %d nodes", label, nodeIds.size());
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error adding label to nodes: %s", e.getMessage());
        } finally {
            tx.close();
        }
    }

    /**
     * Create NEXT relationships between sequential chunks.
     * Uses sequenceNumber metadata to ensure correct ordering.
     */
    private void createChunkRelationships(List<String> chunkIds) {
        if (chunkIds.size() < 2) {
            return;
        }

        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            // Query chunks back ordered by sequenceNumber to ensure correct order
            String getOrderedChunks = """
                    MATCH (d:Document)
                    WHERE d.id IN $chunkIds
                    RETURN d.id as id
                    ORDER BY d.sequenceNumber
                    """;
            Iterable<Map<String, Object>> results = session.query(getOrderedChunks, Map.of("chunkIds", chunkIds));

            List<String> orderedIds = new ArrayList<>();
            for (Map<String, Object> row : results) {
                orderedIds.add((String) row.get("id"));
            }

            // Create NEXT relationships in sequence order
            for (int i = 0; i < orderedIds.size() - 1; i++) {
                String createNext = """
                        MATCH (d1:Document) WHERE d1.id = $fromId
                        MATCH (d2:Document) WHERE d2.id = $toId
                        MERGE (d1)-[:NEXT]->(d2)
                        """;
                session.query(createNext, Map.of(
                        "fromId", orderedIds.get(i),
                        "toId", orderedIds.get(i + 1)));
            }

            tx.commit();
            Log.infof("Created %d NEXT relationships between chunks (ordered by sequenceNumber)", orderedIds.size() - 1);
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error creating chunk relationships: %s", e.getMessage());
            throw new RuntimeException("Failed to create chunk relationships: " + e.getMessage(), e);
        } finally {
            tx.close();
        }
    }

    /**
     * Create NEXT relationships between chunks within the same section.
     * Used for non-adventure files where sections are independently chunked.
     */
    private void createSectionChunkRelationships(List<String> chunkIds, List<int[]> sectionRanges) {
        if (sectionRanges.isEmpty()) {
            return;
        }

        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            int relationshipCount = 0;
            for (int[] range : sectionRanges) {
                int startIdx = range[0];
                int endIdx = range[1];

                for (int i = startIdx; i < endIdx; i++) {
                    String createNext = """
                            MATCH (d1:Document) WHERE d1.id = $fromId
                            MATCH (d2:Document) WHERE d2.id = $toId
                            CREATE (d1)-[:NEXT]->(d2)
                            """;
                    session.query(createNext, Map.of(
                            "fromId", chunkIds.get(i),
                            "toId", chunkIds.get(i + 1)));
                    relationshipCount++;
                }
            }

            tx.commit();
            Log.infof("Created %d NEXT relationships within %d sections", relationshipCount, sectionRanges.size());
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error creating section chunk relationships: %s", e.getMessage());
            throw new RuntimeException("Failed to create section chunk relationships: " + e.getMessage(), e);
        } finally {
            tx.close();
        }
    }

    private String extractFirstLine(String content) {
        int newlineIndex = content.indexOf('\n');
        if (newlineIndex > 0) {
            return content.substring(0, newlineIndex)
                    .replaceAll("^#* ", "")
                    .trim();
        }
        return content.trim();
    }

    private String removeYamlFrontmatter(String content) {
        return YAML_FRONTMATTER_PATTERN.matcher(content).replaceFirst("").trim();
    }

    private Map<String, Object> parseYamlFrontmatter(String content) {
        try {
            // Extract YAML content between --- delimiters using regex
            var matcher = YAML_FRONTMATTER_PATTERN.matcher(content);

            if (!matcher.find()) {
                return new HashMap<>(); // No frontmatter - this is fine
            }

            String yamlContent = matcher.group(1).trim();

            // Parse YAML using Jackson
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMap = yamlMapper.readValue(yamlContent, Map.class);

            // If aliases exists but name doesn't, use first alias as name
            if (!rawMap.containsKey("name") && rawMap.containsKey("aliases")) {
                Object aliases = rawMap.get("aliases");
                if (aliases instanceof List<?> list && !list.isEmpty()) {
                    rawMap.put("name", list.get(0));
                }
            }

            // Convert to Map<String, String> for metadata
            Map<String, Object> result = new HashMap<>();

            Object loreTags = rawMap.get("loreTags");
            if (loreTags instanceof List<?> loreTagList) {
                parseHierarchicalTags(loreTagList, result);
            }

            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Skip null values
                if (value == null) {
                    Log.debugf("Skipping null value for YAML key: %s", key);
                    continue;
                }

                // Handle lists as comma-delimited strings
                if (value instanceof List<?> list) {
                    result.put(key,
                            list.stream()
                                    .map(Object::toString)
                                    .collect(Collectors.joining(",")));
                } else {
                    result.put(key, value.toString());
                }
            }
            return result;
        } catch (Exception e) {
            // Document has frontmatter but it's malformed - fail the upload
            Log.errorf(e, "Failed to parse YAML frontmatter: %s", e.getMessage());
            throw new DocumentProcessingException(
                    "Invalid YAML frontmatter: " + e.getMessage(), e);
        }
    }

    /**
     * Parse hierarchical loreTags into structured metadata.
     * Example: "lore/monster/cr/8" → metadata.put("monster.cr", "8")
     *
     * @param loreTags List of hierarchical tags from YAML
     * @param metadata Metadata map to populate
     */
    private void parseHierarchicalTags(List<?> loreTags, Map<String, Object> metadata) {
        if (loreTags == null || loreTags.isEmpty()) {
            return;
        }

        boolean contentTypeSet = false;

        for (Object tag : loreTags) {
            String tagStr = tag.toString();

            // Only process tags with "lore/" prefix
            if (!tagStr.startsWith("lore/")) {
                continue;
            }

            // Remove "lore/" prefix
            String path = tagStr.substring(5); // "monster/cr/8"

            // Split into parts: ["monster", "cr", "8"]
            String[] parts = path.split("/");

            if (parts.length == 0 || "compendium".equals(parts[0])) {
                continue;
            }

            // Set contentType from first lore/ tag encountered
            if (!contentTypeSet) {
                metadata.put("contentType", parts[0]);
                contentTypeSet = true;
            }

            final String key;
            final String value;
            // Parse nested properties
            if (parts.length == 2) {
                key = parts[0];
                value = parts[1];
            } else if (parts.length >= 3) {
                // Complex case: "lore/monster/cr/8" → metadata["monster.cr"] = "8"
                // Build dotted key from all parts except the last
                StringBuilder keyBuilder = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length - 1; i++) {
                    keyBuilder.append(".").append(parts[i]);
                }
                key = keyBuilder.toString();
                value = parts[parts.length - 1].replaceAll("\\s+", " ").trim();
            } else {
                // Simple case: "lore/statblock" → contentType already set, no other value to
                // save
                continue;
            }

            if (value.isEmpty()) {
                continue; // Skip tags with empty values
            }

            // Convert to list if multiple of the same key, e.g.
            // - lore/monster/environment/grassland
            // - lore/monster/environment/hill
            // - lore/monster/environment/mountain
            metadata.merge(key, value, (v1, v2) -> v1 + ", " + v2);
        }
    }

    /**
     * List all ingested files with their embedding counts.
     * Returns a list of file info maps.
     */
    public List<Map<String, Object>> listIngestedFiles() {
        var session = sessionFactory.openSession();
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.sourceFile IS NOT NULL
                    RETURN n.sourceFile as sourceFile,
                           count(*) as embeddingCount
                    ORDER BY n.sourceFile
                    """;

            Iterable<Map<String, Object>> results = session.query(cypher, Map.of());
            results.forEach(row -> {
                String sourceFile = (String) row.get("sourceFile");
                Long embeddingCount = (Long) row.get("embeddingCount");

                result.add(Map.of(
                        "sourceFile", sourceFile,
                        "embeddingCount", embeddingCount));
            });
        } catch (Exception e) {
            Log.errorf(e, "Error listing ingested files: %s", e.getMessage());
        }

        return result;
    }

    /**
     * Delete all embeddings for a specific file.
     * Returns the number of embeddings deleted.
     */
    public int deleteFile(String sourceFile) {
        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.sourceFile = $sourceFile
                    WITH count(n) as deleteCount
                    MATCH (n:Document)
                    WHERE n.sourceFile = $sourceFile
                    DETACH DELETE n
                    RETURN deleteCount
                    """;

            Iterable<Map<String, Object>> results = session.query(cypher,
                    Map.of("sourceFile", sourceFile));

            int deleteCount = 0;
            for (Map<String, Object> row : results) {
                deleteCount = ((Long) row.get("deleteCount")).intValue();
            }

            tx.commit();
            Log.infof("Deleted %d embeddings for file: %s", deleteCount, sourceFile);
            return deleteCount;
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error deleting file: %s", e.getMessage());
            throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
        } finally {
            tx.close();
        }
    }

    /**
     * Delete all document embeddings.
     * Returns the number of embeddings deleted.
     */
    public int deleteAllDocuments() {
        var session = sessionFactory.openSession();
        var tx = session.beginTransaction();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WITH count(n) as deleteCount
                    MATCH (n:Document)
                    DETACH DELETE n
                    RETURN deleteCount
                    """;

            Iterable<Map<String, Object>> results = session.query(cypher, Map.of());

            int deleteCount = 0;
            for (Map<String, Object> row : results) {
                deleteCount = ((Long) row.get("deleteCount")).intValue();
            }

            tx.commit();
            Log.infof("Deleted %d document embeddings", deleteCount);
            return deleteCount;
        } catch (Exception e) {
            tx.rollback();
            Log.errorf(e, "Error deleting documents: %s", e.getMessage());
            throw new RuntimeException("Failed to delete documents: " + e.getMessage(), e);
        } finally {
            tx.close();
        }
    }

    /**
     * Result of batch document ingestion.
     * Contains lists of successfully processed files and any errors encountered.
     */
    public record IngestResult(
            List<String> processedFiles,
            List<FileError> errors,
            int successCount) {

        public IngestResult(List<String> processedFiles, List<FileError> errors) {
            this(processedFiles, errors, processedFiles.size());
        }

        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        public boolean allFailed() {
            return successCount == 0 && hasErrors();
        }
    }

    public record FileError(String fileName, String errorMessage) {
    }

    /**
     * Ingest multiple document files.
     * Processes all files and returns a result with successes and failures.
     *
     * @param files List of file uploads to process
     * @return IngestResult containing processed files and any errors
     */
    public IngestResult ingestDocuments(List<org.jboss.resteasy.reactive.multipart.FileUpload> files) {
        List<String> processedFiles = new ArrayList<>();
        List<FileError> errors = new ArrayList<>();

        for (var file : files) {
            try {
                String content = java.nio.file.Files.readString(file.uploadedFile());
                ingestFile(file.fileName(), content);
                processedFiles.add(file.fileName());
            } catch (Exception e) {
                Log.errorf(e, "Error processing file: %s", file.fileName());
                errors.add(new FileError(file.fileName(), e.getMessage()));
            }
        }

        return new IngestResult(processedFiles, errors);
    }
}
