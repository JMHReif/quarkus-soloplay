package dev.ebullient.soloplay.play.model;

import org.neo4j.ogm.annotation.NodeEntity;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@NodeEntity
public class Location extends NamedBaseEntity {

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance locationDetail(Location location);
    }

    public Location() {
        super();
    }

    public Location(String gameId, Patch p) {
        super(gameId, p);
    }

    @Override
    public Location merge(Patch p) {
        super.merge(p);
        return this;
    }

    public String render() {
        return Templates.locationDetail(this).render();
    }
}
