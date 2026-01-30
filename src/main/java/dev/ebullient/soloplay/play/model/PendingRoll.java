package dev.ebullient.soloplay.play.model;

import dev.langchain4j.model.output.structured.Description;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

public record PendingRoll(
        @Description("\"skill_check\", \"attack\", \"saving_throw\", \"ability_check\"") String type,
        @Description("\"persuasion\", \"stealth\", etc. (null for attacks/saves)") String skill,
        @Description("\"strength\", \"dexterity\", etc.") String ability,
        @Description("Difficulty class. Null if contested or attack roll") Integer dc,
        @Description("who/what this is for or against") String target,
        @Description("brief explanation of the roll for the player") String context) {

    @CheckedTemplate(basePath = "models")
    public static class Templates {
        public static native TemplateInstance pendingRoll(PendingRoll roll);
    }

    public String render() {
        return Templates.pendingRoll(this).render();
    }
}
