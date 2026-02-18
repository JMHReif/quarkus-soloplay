package dev.ebullient.soloplay.play;

import java.util.List;

import dev.ebullient.soloplay.play.model.Patch;
import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.langchain4j.model.output.structured.Description;

public record GamePlayResponse(
        @Description("Narrative description of current situation") String narration,

        @Description("1-2 sentences capturing what happened AND where things stand now. MUST use the player character's actual name. DO NOT invent or alter character names.") String turnSummary,

        @Description("If a roll is required, specify it here. Leave null if presenting choices.") PendingRoll pendingRoll,

        @Description("Available choices for the player. Leave empty if a roll is pending.") List<String> playerChoices,

        @Description("Changes to world state. null or [] means no world state changes. ONLY use type \"actor\" (for NPCs/creatures) or \"location\". Never use \"event\", \"player_actor\", or any other type.") List<Patch> patches,

        @Description("just the location name, for example, \"Rusty Anchor Tavern\"") String currentLocation,

        @Description("names of all NPCs or creatures currently in the scene") List<String> actorsPresent,

        @Description("names of location(s) relevant to the scene") List<String> locationsPresent,

        @Description("list of lore document filenames used. If you did not use lore docs or tools, sources = []. Don't invent filenames.") List<String> sources,

        @Description("Set to true when you have covered the content of the current adventure segment and are ready to move on. null or false means you are still working within the current segment.") Boolean segmentComplete,

        @Description("If the player makes a major decision that deviates from the written adventure, describe the deviation briefly. null means no deviation.") String majorDecision,

        @Description("A concise note about a key moment worth remembering (character revelation, major plot twist, important discovery). null means nothing noteworthy to checkpoint.") String checkpoint) {
}
