package dev.ebullient.soloplay.play.model;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.neo4j.ogm.annotation.Transient;

import dev.ebullient.soloplay.StringUtils;

public abstract class BaseEntity {

    protected Long createdAt;
    protected Long updatedAt;
    protected Set<String> tags;
    private Set<String> sources;

    @Transient
    protected boolean dirty;

    protected BaseEntity() {
        this.createdAt = Instant.now().toEpochMilli();
        this.updatedAt = Instant.now().toEpochMilli();
        this.tags = new HashSet<>();
        this.sources = new HashSet<>();
        this.dirty = false;
    }

    /**
     * @return the createdAt
     */
    public Long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return the updatedAt
     */
    public Long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @return the dirty
     */
    public boolean isDirty() {
        return dirty;
    }

    protected void markDirty() {
        this.updatedAt = Instant.now().toEpochMilli();
        this.dirty = true;
    }

    public void markClean() {
        this.dirty = false;
    }

    public Collection<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = new HashSet<>(StringUtils.normalize(tags));
        markDirty();
    }

    /**
     * Add a tag to this character (case-insensitive, normalized to lowercase).
     */
    public void addTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String normalized = StringUtils.normalize(tag);
        if (tags.add(normalized)) {
            markDirty();
        }
    }

    /**
     * Remove a tag from this character (case-insensitive).
     */
    public void removeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String normalized = StringUtils.normalize(tag);
        if (tags.remove(normalized)) {
            markDirty();
        }
    }

    /**
     * Check if this character has a specific tag (case-insensitive).
     */
    public boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String normalized = StringUtils.normalize(tag);
        return tags.contains(normalized);
    }

    /**
     * Check if this character has any of the provided tags (case-insensitive).
     */
    public boolean hasAnyTag(List<String> checkTags) {
        if (checkTags == null || checkTags.isEmpty()) {
            return false;
        }
        return checkTags.stream()
                .map(t -> t.trim().toLowerCase())
                .anyMatch(tags::contains);
    }

    /**
     * Check if this character has all of the provided tags (case-insensitive).
     */
    public boolean hasAllTags(List<String> checkTags) {
        if (checkTags == null || checkTags.isEmpty()) {
            return true;
        }
        return checkTags.stream()
                .map(t -> t.trim().toLowerCase())
                .allMatch(tags::contains);
    }

    public Collection<String> getSources() {
        return sources;
    }

    public void addSources(Collection<String> sources) {
        this.sources.addAll(sources);
        markDirty();
    }

    public void addSource(String source) {
        sources.add(source);
        markDirty();
    }
}
