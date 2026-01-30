package dev.ebullient.soloplay.play.model;

import java.util.List;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

/**
 * Pre-rendered "what can I do next?" choices presented to the player.
 * Rendered outside of narration chat bubbles (like pending rolls).
 */
public record PlayerChoices(List<String> choices) {

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance playerChoices(PlayerChoices choices);
    }

    public String render() {
        return Templates.playerChoices(this).render();
    }
}
