package dev.ebullient.soloplay.play.model;

import org.neo4j.ogm.annotation.NodeEntity;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@NodeEntity
public class Actor extends NamedBaseEntity {

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance actorDetail(Actor actor);

        public static native TemplateInstance actorSummary(Actor actor);
    }

    public Actor() {
        super();
    }

    public Actor(String gameId, Patch p) {
        super(gameId, p);
    }

    @Override
    public Actor merge(Patch p) {
        super.merge(p);
        var patchSources = p.sources();
        if (patchSources != null && !patchSources.isEmpty()) {
            getSources().addAll(patchSources);
        }
        return this;
    }

    public String render() {
        return Templates.actorDetail(this).render();
    }
}
