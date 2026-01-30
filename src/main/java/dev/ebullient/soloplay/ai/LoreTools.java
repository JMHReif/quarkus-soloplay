package dev.ebullient.soloplay.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.ebullient.soloplay.LoreRepository;
import dev.langchain4j.agent.tool.Tool;

/**
 * AI Tools for lore document retrieval.
 * Provides cross-reference resolution for campaign documents.
 */
@ApplicationScoped
public class LoreTools {

    @Inject
    LoreRepository loreRepository;

    // Track the last directory context for relative path resolution
    private String lastDirectory = "";

    @Tool("""
            Retrieve lore document content by filename.

            For relative paths like "./file.md", provide the full path from directory context.
            Example: if you fetched "vehicles/vehicles.md" and it links to "./damselfly-ship-aag.md",
            call getLoreDocument("vehicles/damselfly-ship-aag.md")

            Returns the full document text, or an error message if not found.
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
}
