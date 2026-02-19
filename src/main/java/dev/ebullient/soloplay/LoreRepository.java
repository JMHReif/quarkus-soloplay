package dev.ebullient.soloplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.neo4j.ogm.session.SessionFactory;

import dev.ebullient.soloplay.play.model.FileNode;
import dev.langchain4j.data.document.Document;
import io.quarkus.logging.Log;

@ApplicationScoped
public class LoreRepository {

    @Inject
    SessionFactory sessionFactory;

    public void createFileNode(Document document) {
        String filename = document.metadata().getString("filename");
        if (filename == null) {
            return;
        }
        String sourceFile = document.metadata().getString("sourceFile");
        String group = document.metadata().getString("group");
        String text = (group == null || group.isBlank())
                ? document.text()
                : group + document.text();
        var session = sessionFactory.openSession();
        try (var tx = session.beginTransaction()) {
            var fileNode = new FileNode(filename, sourceFile, text);
            session.save(fileNode);
            tx.commit();
        } catch (Exception e) {
            Log.errorf(e, "Error saving file node: %s", e.getMessage());
        }
    }

    /**
     * Retrieve document content by filename (from YAML frontmatter).
     * Used for resolving cross-references in campaign documents.
     *
     * @param filename The filename (e.g., "backgrounds/acolyte-xphb.md")
     * @return text content
     */
    public String getDocumentByFilename(String filename) {
        var session = sessionFactory.openSession();
        try {
            String filenameWithMd = filename.endsWith(".md") ? filename : filename + ".md";
            String filenameWithoutMd = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;

            // Try exact match first, then with/without .md extension
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.filename = $filename
                       OR n.filename = $filenameWithMd
                       OR n.filename = $filenameWithoutMd
                    RETURN n.text as text
                    ORDER BY n.sectionIndex, n.chunkIndex
                    """;

            Iterable<Map<String, Object>> results = session.query(cypher, Map.of(
                    "filename", filename,
                    "filenameWithMd", filenameWithMd,
                    "filenameWithoutMd", filenameWithoutMd));

            StringBuilder content = new StringBuilder();
            for (Map<String, Object> row : results) {
                String text = (String) row.get("text");
                if (text != null) {
                    if (content.length() > 0) {
                        content.append("\n\n");
                    }
                    content.append(text);
                }
            }

            if (content.length() == 0) {
                return null;
            }
            return content.toString();
        } catch (Exception e) {
            Log.errorf(e, "Error retrieving file node: %s", e.getMessage());
        }
        return null;
    }

    /**
     * List all available adventures from ingested documents.
     * Adventures are identified by having an "adventureName" attribute
     * Returns a list of distinct adventure names.
     */
    public List<String> listAdventures() {
        var session = sessionFactory.openSession();
        List<String> result = new ArrayList<>();

        try {
            // Query for documents with adventureName attribute
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.adventureName IS NOT NULL
                    RETURN DISTINCT n.adventureName as adventureName
                    ORDER BY adventureName
                    """;

            Iterable<Map<String, Object>> results = session.query(cypher, Map.of());
            results.forEach(row -> {
                String adventureName = (String) row.get("adventureName");
                if (adventureName != null && !adventureName.isBlank()) {
                    result.add(adventureName);
                }
            });
        } catch (Exception e) {
            Log.errorf(e, "Error listing adventures: %s", e.getMessage());
        }

        return result;
    }

    /**
     * Validate that an adventure exists in the ingested documents.
     * Returns true if the adventure is found, false otherwise.
     */
    public boolean validateAdventureExists(String adventureName) {
        if (adventureName == null) {
            return false;
        }

        List<String> adventures = listAdventures();
        return adventures.contains(adventureName);
    }

    /**
     * Search for adventure-specific content by keyword.
     * Returns matching text segments from documents tagged with the adventure name.
     *
     * @param adventureName The adventure to search within
     * @param keyword Search term to find in document text
     * @param limit Maximum number of results to return
     * @return List of matching text segments
     */
    public List<String> searchAdventureContent(String adventureName, String keyword, int limit) {
        var session = sessionFactory.openSession();
        List<String> results = new ArrayList<>();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.adventureName = $adventureName
                      AND toLower(n.text) CONTAINS toLower($keyword)
                    RETURN n.text as text, n.section as section
                    ORDER BY n.sectionIndex, n.chunkIndex
                    LIMIT $limit
                    """;

            Iterable<Map<String, Object>> rows = session.query(cypher, Map.of(
                    "adventureName", adventureName,
                    "keyword", keyword,
                    "limit", limit));

            for (Map<String, Object> row : rows) {
                String text = (String) row.get("text");
                if (text != null && !text.isBlank()) {
                    results.add(text);
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Error searching adventure content: %s", e.getMessage());
        }

        return results;
    }

    /**
     * List all distinct filenames for documents in an adventure.
     *
     * @param adventureName The adventure name
     * @return List of filenames
     */
    public List<String> listAdventureFiles(String adventureName) {
        var session = sessionFactory.openSession();
        List<String> results = new ArrayList<>();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.adventureName = $adventureName
                      AND n.filename IS NOT NULL
                    RETURN DISTINCT n.filename as filename
                    ORDER BY filename
                    """;

            Iterable<Map<String, Object>> rows = session.query(cypher, Map.of(
                    "adventureName", adventureName));

            for (Map<String, Object> row : rows) {
                String filename = (String) row.get("filename");
                if (filename != null && !filename.isBlank()) {
                    results.add(filename);
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Error listing adventure files: %s", e.getMessage());
        }

        return results;
    }

    /**
     * List all distinct chapters for documents in an adventure.
     *
     * @param adventureName The adventure name
     * @return List of chapter names (formatted as "chapterNumber: chapterName")
     */
    public List<String> listAdventureChapters(String adventureName) {
        var session = sessionFactory.openSession();
        List<String> results = new ArrayList<>();

        try {
            String cypher = """
                    MATCH (n:Document)
                    WHERE n.adventureName = $adventureName
                      AND n.chapterName IS NOT NULL
                    RETURN DISTINCT n.chapterNumber as chapterNumber, n.chapterName as chapterName
                    ORDER BY chapterNumber
                    """;

            Iterable<Map<String, Object>> rows = session.query(cypher, Map.of(
                    "adventureName", adventureName));

            for (Map<String, Object> row : rows) {
                String chapterNumber = (String) row.get("chapterNumber");
                String chapterName = (String) row.get("chapterName");
                if (chapterName != null && !chapterName.isBlank()) {
                    if (chapterNumber != null && !chapterNumber.isBlank()) {
                        results.add(chapterNumber + ": " + chapterName);
                    } else {
                        results.add(chapterName);
                    }
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Error listing adventure chapters: %s", e.getMessage());
        }

        return results;
    }
}
