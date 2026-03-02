package dev.ebullient.soloplay.play.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@NodeEntity
public class Event extends BaseEntity implements Stash {

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance eventDetail(Event event);
    }

    @Id
    private String id; // gameId:event-{timestamp}-{turnNumber}

    private String gameId;
    private String summary; // Brief description of what happened (from turnSummary)
    private Integer turnNumber; // Game turn when this occurred

    private Set<Actor> participants;
    private Set<Location> locations;

    public Event() {
        super();
        this.participants = new HashSet<>();
        this.locations = new HashSet<>();
    }

    public Event(String gameId, Integer turnNumber, String summary) {
        this();
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.summary = summary;
        this.id = gameId + ":event-" + createdAt + "-" + turnNumber;
        markDirty();
    }

    public String getId() {
        return id;
    }

    public String getGameId() {
        return gameId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
        markDirty();
    }

    public Integer getTurnNumber() {
        return turnNumber;
    }

    public Set<Actor> getParticipants() {
        return participants;
    }

    public void addParticipant(Actor actor) {
        if (participants.add(actor)) {
            markDirty();
        }
    }

    public void addParticipants(Collection<Actor> eventParticipants) {
        eventParticipants.forEach(a -> addParticipant(a));
    }

    public void removeParticipant(Actor actor) {
        if (participants.remove(actor)) {
            markDirty();
        }
    }

    public Set<Location> getLocations() {
        return locations;
    }

    public void addLocation(Location location) {
        if (locations.add(location)) {
            markDirty();
        }
    }

    public void addLocations(Collection<Location> eventLocations) {
        eventLocations.forEach(l -> addLocation(l));
    }

    public void removeLocation(Location location) {
        if (locations.remove(location)) {
            markDirty();
        }
    }

    public String render() {
        return Templates.eventDetail(this).render();
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
        Event other = (Event) obj;
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
