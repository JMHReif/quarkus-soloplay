package dev.ebullient.soloplay;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.neo4j.ogm.session.SessionFactory;

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

    // Regex pattern for markdown section headers (## Header)
    // (?m) enables multiline mode (^ matches line starts)
    // Positive lookahead (?=^##) splits before headers without consuming them
    static final java.util.regex.Pattern SECTION_HEADER_PATTERN = java.util.regex.Pattern
            .compile("(?m)(?=^## )");

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

    @Inject
    LoreRepository loreRepository;

    @Inject
    MarkdownDocumentParser markdownParser;

    public void ingestFile(String sourceFile, String content) {
        Log.infof("Processing file: %s (size: %d bytes)", sourceFile, content.length());

        boolean isAdventureFile = "adventures.txt".equals(sourceFile);
        List<String> allChunkIds = isAdventureFile ? new ArrayList<>() : null;

        if (content.contains(TOOLS_DOC_SEPARATOR)) {
            String[] parts = content.split(TOOLS_DOC_SEPARATOR);
            Log.infof("Found %d structured sections in %s", parts.length, sourceFile);
            int processedCount = 0;
            for (String part : parts) {
                String trimmed = part.trim();
                // Skip empty parts and parts that are just the separator
                if (!trimmed.isBlank() && !trimmed.equals("============")) {
                    Document document = markdownParser.parse(sourceFile, trimmed);
                    loreRepository.createFileNode(document);
                    List<String> chunkIds = chunkDocument(document);
                    if (allChunkIds != null) {
                        allChunkIds.addAll(chunkIds);
                    }
                    processedCount++;
                }
            }
            Log.infof("Processed %d non-empty notes from %s", processedCount, sourceFile);
        } else {
            Document document = markdownParser.parse(sourceFile, content.trim());
            loreRepository.createFileNode(document);
            List<String> chunkIds = chunkDocument(document);
            if (allChunkIds != null) {
                allChunkIds.addAll(chunkIds);
            }
        }

        // For adventures.txt, create NEXT relationships between chunks
        if (isAdventureFile && allChunkIds != null && !allChunkIds.isEmpty()) {
            createChunkRelationships(allChunkIds);
        }

        Log.infof("Completed processing file: %s", sourceFile);
    }

    private List<String> chunkDocument(Document document) {
        Metadata common = document.metadata();

        String sourceFile = common.getString("sourceFile");
        String prefix = common.getString("group");
        if (prefix == null) {
            prefix = "";
        }
        String content = document.text();

        List<TextSegment> segments = new ArrayList<>();
        // Track start index of multi-chunk sections for NEXT relationships
        List<int[]> chunkedSectionRanges = new ArrayList<>();

        if (prefix.length() + content.length() > chunkSize) {
            String[] sections = SECTION_HEADER_PATTERN.split(content);
            int sectionIndex = 0;
            for (String section : sections) {
                if (section.isBlank()) {
                    continue;
                }
                String sectionTitle = extractFirstLine(section);
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
                        subSegment.metadata().putAll(common.toMap());
                        subSegment.metadata()
                                .put("section", sectionTitle)
                                .put("sectionIndex", sectionIndex)
                                .put("chunkIndex", chunkIndex++);

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
                            .put("chunkIndex", 0);
                    segments.add(segment);
                }
                sectionIndex++;
            }
        } else if (!content.isBlank()) {
            TextSegment segment = TextSegment.from(
                    prefix + content,
                    common);
            segment.metadata()
                    .put("sectionIndex", 0)
                    .put("chunkIndex", 0);
            segments.add(segment);
        }

        // Generate embeddings and store
        Log.infof("Generating embeddings for %d segments from %s", segments.size(), sourceFile);

        if (segments.isEmpty()) {
            Log.warnf("No valid segments to embed for %s", sourceFile);
            return List.of();
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<String> chunkIds = embeddingStore.addAll(embeddings, segments);
        Log.infof("Stored %d embeddings for %s", embeddings.size(), sourceFile);

        // Create NEXT relationships for chunked sections (non-adventure files only)
        // Adventure files handle this separately with relationships across all chunks
        boolean isAdventureFile = "adventures.txt".equals(sourceFile);
        if (!isAdventureFile && !chunkedSectionRanges.isEmpty()) {
            createSectionChunkRelationships(chunkIds, chunkedSectionRanges);
        }

        return chunkIds;
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

    /**
     * Create NEXT relationships between sequential chunks for adventure files.
     */
    private void createChunkRelationships(List<String> chunkIds) {
        if (chunkIds.size() < 2) {
            return;
        }

        var session = sessionFactory.openSession();
        try (var tx = session.beginTransaction();) {
            for (int i = 0; i < chunkIds.size() - 1; i++) {
                String createNext = """
                        MATCH (d1:Document) WHERE d1.id = $fromId
                        MATCH (d2:Document) WHERE d2.id = $toId
                        CREATE (d1)-[:NEXT]->(d2)
                        """;
                session.query(createNext, Map.of(
                        "fromId", chunkIds.get(i),
                        "toId", chunkIds.get(i + 1)));
            }

            tx.commit();
            Log.infof("Created %d NEXT relationships between chunks", chunkIds.size() - 1);
        } catch (Exception e) {
            Log.errorf(e, "Error creating chunk relationships: %s", e.getMessage());
            throw new RuntimeException("Failed to create chunk relationships: " + e.getMessage(), e);
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
        try (var tx = session.beginTransaction()) {
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
            Log.errorf(e, "Error creating section chunk relationships: %s", e.getMessage());
            throw new RuntimeException("Failed to create section chunk relationships: " + e.getMessage(), e);
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
        try (var tx = session.beginTransaction()) {
            String deleteFilesCypher = """
                    MATCH (f:File)
                    WHERE f.sourceFile = $sourceFile
                    WITH count(f) as deleteCount
                    MATCH (f:File)
                    WHERE f.sourceFile = $sourceFile
                    DETACH DELETE f
                    RETURN deleteCount
                    """;

            Iterable<Map<String, Object>> fileResults = session.query(deleteFilesCypher,
                    Map.of("sourceFile", sourceFile));
            int fileDeleteCount = 0;
            for (Map<String, Object> row : fileResults) {
                fileDeleteCount = ((Long) row.get("deleteCount")).intValue();
            }

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
            Log.infof("Deleted %d file nodes for file: %s", fileDeleteCount, sourceFile);
            Log.infof("Deleted %d embeddings for file: %s", deleteCount, sourceFile);
            return deleteCount;
        } catch (Exception e) {
            Log.errorf(e, "Error deleting file: %s", e.getMessage());
            throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
        }
    }

    /**
     * Delete all document embeddings.
     * Returns the number of embeddings deleted.
     */
    public int deleteAllDocuments() {
        var session = sessionFactory.openSession();
        try (var tx = session.beginTransaction();) {
            String deleteFilesCypher = """
                    MATCH (f:File)
                    WITH count(f) as deleteCount
                    MATCH (f:File)
                    DETACH DELETE f
                    RETURN deleteCount
                    """;

            Iterable<Map<String, Object>> fileResults = session.query(deleteFilesCypher, Map.of());
            int fileDeleteCount = 0;
            for (Map<String, Object> row : fileResults) {
                fileDeleteCount = ((Long) row.get("deleteCount")).intValue();
            }

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
            Log.infof("Deleted %d file nodes", fileDeleteCount);
            Log.infof("Deleted %d document embeddings", deleteCount);
            return deleteCount;
        } catch (Exception e) {
            Log.errorf(e, "Error deleting documents: %s", e.getMessage());
            throw new RuntimeException("Failed to delete documents: " + e.getMessage(), e);
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
    public IngestResult ingestDocuments(List<FileUpload> files) {
        List<String> processedFiles = new ArrayList<>();
        List<FileError> errors = new ArrayList<>();

        for (var file : files) {
            try {
                String content = Files.readString(file.uploadedFile());
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
