package dev.ebullient.soloplay.play.model;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

@NodeEntity("File")
public class FileNode {

    @Id
    String filename;

    String sourceFile;

    String text;

    FileNode() {
    }

    public FileNode(String filename, String sourceFile, String text) {
        this.filename = filename;
        this.sourceFile = sourceFile;
        this.text = text;
    }

    /**
     * @return the filename
     */
    public String getFilename() {
        return filename;
    }

    /**
     * @param filename the filename to set
     */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * @param text the text to set
     */
    public void setText(String text) {
        this.text = text;
    }

}
