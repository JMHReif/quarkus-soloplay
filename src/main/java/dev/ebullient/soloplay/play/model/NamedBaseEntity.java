package dev.ebullient.soloplay.play.model;

import static dev.ebullient.soloplay.StringUtils.normalize;
import static dev.ebullient.soloplay.StringUtils.slugify;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.neo4j.ogm.annotation.Id;

public class NamedBaseEntity extends BaseEntity {

    @Id
    protected String id; // human readable slug

    protected String gameId;
    protected String name;
    protected String normalizedName;
    protected String summary; // Short, stable identifier (e.g., "Aged wizard", "Young warrior")
    protected String description; // Full narrative that can evolve over time

    // Alternative names (e.g., "Krux" for "Commodore Krux")
    protected Set<String> aliases = new HashSet<>();

    protected List<String> sources;

    public NamedBaseEntity() {
    }

    public NamedBaseEntity(String gameId, Patch p) {
        this.gameId = gameId;

        this.name = p.name();
        this.normalizedName = this.name == null ? null : normalize(this.name);
        this.id = gameId + ":" + slugify(this.name);

        this.summary = p.summary();
        this.description = p.description();
        setAliases(p.aliases());
        setTags(p.tags());

        markDirty();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
        markDirty();
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
        if (this.id != null) {
            this.id = gameId + ":" + slugify(name);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.normalizedName = name == null ? null : normalize(name);
        markDirty();
        // Note: id is NOT updated when name changes (id is immutable)
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
        markDirty();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        markDirty();
    }

    public Collection<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = new HashSet<>(normalize(aliases));
        this.markDirty();
    }

    /**
     * Add an alias to this character (case-insensitive, normalized to lowercase).
     */
    public void addAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        String normalized = normalize(alias);
        if (aliases.add(normalized)) {
            this.markDirty();
        }
    }

    /**
     * Remove an alias from this character (case-insensitive).
     */
    public void removeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        String normalized = normalize(alias);
        if (aliases.remove(normalized)) {
            this.markDirty();
        }
    }

    /**
     * Check if this character has a specific alias (case-insensitive).
     */
    public boolean hasAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return false;
        }
        String normalized = normalize(alias);
        return aliases.contains(normalized);
    }

    /**
     * Check if a name matches this character's name or any alias
     * (case-insensitive).
     */
    public boolean matchesNameOrAlias(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            return false;
        }
        String normalized = normalize(nameOrAlias);
        return name.toLowerCase().equals(normalized) || aliases.contains(normalized);
    }

    public NamedBaseEntity merge(Patch p) {
        if (p.summary() != null) {
            this.summary = p.summary();
        }
        if (p.description() != null) {
            this.description = p.description();
        }
        if (p.aliases() == null || p.aliases().isEmpty()) {
            setAliases(p.aliases());
        }
        if (p.tags() == null || p.tags().isEmpty()) {
            setTags(p.tags());
        }
        markDirty();
        return this;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        NamedBaseEntity other = (NamedBaseEntity) obj;
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }
        return true;
    }
}
