package dev.ebullient.soloplay.api;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import dev.ebullient.soloplay.IngestService;
import dev.ebullient.soloplay.LoreRepository;
import dev.ebullient.soloplay.ai.JsonChatResponse;
import dev.ebullient.soloplay.ai.LoreAssistant;
import dev.ebullient.soloplay.ai.MarkdownAugmenter;
import dev.ebullient.soloplay.health.Neo4jHealth;
import io.quarkus.logging.Log;

/**
 * RAG-based lore query interface.
 * Queries against ingested campaign documents with semantic search.
 * Document ingestion is handled via the web UI at /ingest.
 */
@ApplicationScoped
@Path("/api/lore")
public class LoreResource {

    @Inject
    LoreAssistant settingAssistant;

    @Inject
    MarkdownAugmenter prettify;

    @Inject
    LoreRepository loreRepository;

    @Inject
    IngestService ingestService;

    @Inject
    Neo4jHealth neo4jHealth;

    /**
     * Query lore with optional label filter.
     *
     * @param question The search question
     * @param label Optional filter (e.g., "Monster", "Item", "Spell", "Vehicle")
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public String lore(@RestQuery String question, @RestQuery String label) {
        String queryWithFilter = buildFilteredQuery(question, label);
        JsonChatResponse chatResponse = settingAssistant.lore(queryWithFilter);
        return prettify.markdownToHtml(chatResponse.response());
    }

    /**
     * Build query with optional filter prefix.
     */
    private String buildFilteredQuery(String question, String label) {
        if (label != null && !label.isBlank()) {
            return "[filter:" + label.trim() + "] " + question;
        }
        return question;
    }

    /**
     * Retrieve a lore document by its filename (from YAML frontmatter).
     * Used for resolving cross-references in campaign documents.
     *
     * @param filename The document filename (e.g., "backgrounds/acolyte-xphb.md")
     * @return Raw markdown content or 404 if not found
     */
    @GET
    @Path("/doc")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDocument(@RestQuery String filename) {
        if (filename == null || filename.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Filename parameter is required")
                    .build();
        }

        String content = loreRepository.getDocumentByFilename(filename);
        if (content == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Document not found: " + filename)
                    .build();
        }
        return Response.ok(content).build();
    }

    /**
     * Query lore via POST with optional label filter.
     *
     * @param question The search question (body)
     * @param label Optional filter (e.g., "Monster", "Item", "Spell", "Vehicle")
     */
    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_HTML)
    public String postLore(String question, @RestQuery String label) {
        String queryWithFilter = buildFilteredQuery(question, label);
        JsonChatResponse chatResponse = settingAssistant.lore(queryWithFilter);
        return prettify.markdownToHtml(chatResponse.response());
    }

    /**
     * List all ingested files with embedding counts.
     * Returns a JSON list of files with embedding counts.
     */
    @GET
    @Path("/files")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> listFiles() {
        return ingestService.listIngestedFiles();
    }

    /**
     * List all available adventures from ingested documents.
     * Adventures are identified by having "adventures" in the sourceFile path.
     * Returns a JSON list of adventure names.
     */
    @GET
    @Path("/adventures")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listAdventures() {
        return loreRepository.listAdventures();
    }

    /**
     * Delete a specific file.
     * Query param: sourceFile
     */
    @DELETE
    @Path("/files")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> deleteFile(@RestQuery String sourceFile) {
        int deleteCount = ingestService.deleteFile(sourceFile);
        return Map.of(
                "deleted", deleteCount,
                "sourceFile", sourceFile);
    }

    /**
     * Delete all documents.
     */
    @DELETE
    @Path("/all")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> deleteAllDocuments() {
        int deleteCount = ingestService.deleteAllDocuments();
        return Map.of("deleted", deleteCount);
    }

    /**
     * Upload and ingest documents.
     * Accepts multipart form data with one or more document files.
     */
    @POST
    @Path("/ingest")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ingestDocuments(@RestForm("documents") List<FileUpload> files) {

        if (files == null || files.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "At least one document file is required"))
                    .build();
        }

        // Verify Neo4j connectivity before processing files
        try {
            neo4jHealth.neo4jIsAvailable();
        } catch (Exception e) {
            Log.errorf("Neo4j not available: %s", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Neo4j is not available: " + e.getMessage()))
                    .build();
        }

        // Process files using IngestService
        IngestService.IngestResult result = ingestService.ingestDocuments(files);

        // Convert result to API response format
        List<Map<String, String>> errors = result.errors().stream()
                .map(err -> Map.of(
                        "file", err.fileName(),
                        "error", err.errorMessage()))
                .toList();

        // Return result
        if (result.allFailed()) {
            // All files failed
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "All files failed to process",
                            "failures", errors))
                    .build();
        } else if (!errors.isEmpty()) {
            // Some files failed
            return Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(Map.of(
                            "processedFiles", result.processedFiles(),
                            "successCount", result.successCount(),
                            "failures", errors))
                    .build();
        } else {
            // All files succeeded
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of(
                            "processedFiles", result.processedFiles(),
                            "successCount", result.successCount()))
                    .build();
        }
    }
}
