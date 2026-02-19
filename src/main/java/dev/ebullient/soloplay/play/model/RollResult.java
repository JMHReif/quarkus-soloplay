package dev.ebullient.soloplay.play.model;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

public record RollResult(
        String type, // matches pendingRoll.type
        int total, // final result after modifiers
        String breakdown, // "14 + 3 = 17"
        boolean success, // did it meet/beat DC?
        String context) { // copied from pendingRoll for continuity

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance rollResult(RollResult result);
    }

    public String render() {
        return Templates.rollResult(this).render();
    }
}
