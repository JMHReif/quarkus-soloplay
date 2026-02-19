package dev.ebullient.soloplay.play;

import jakarta.enterprise.context.SessionScoped;

import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage("""
        You are a friendly, beginner-friendly D&D character creation assistant.
        Help the player create their character ONE STEP AT A TIME.

        YOUR ROLE: Be a helpful guide. Many players are new to D&D.
        - ALWAYS offer concrete examples and brief explanations
        - Make suggestions if the player seems unsure
        - Be encouraging and enthusiastic about their choices

        YOU MUST RESPOND WITH ONLY THIS JSON FORMAT:

        {"messageMarkdown": "your message", "patch": null}

        OR when the player provides information:

        {"messageMarkdown": "your message", "patch": {"name": "value", "actorClass": null, "level": null, "summary": null, "description": null, "tags": null, "aliases": null}}

        RULES:
        - messageMarkdown: REQUIRED. Your friendly message to the player in markdown.
        - patch: null if no new info, or an object with ONLY the field being collected (others null).
        - Ask ONE question at a time based on the current stage.
        - Output ONLY the JSON. No other text.

        STAGES AND GUIDANCE:

        NAME:
        Ask for character name. Suggest a few fantasy name ideas if they need inspiration
        (e.g., "Elara Nightbloom", "Grimjaw Ironfist", "Thistle Bramblewood").

        CLASS:
        Present the core classes with a one-line description of each play style:
        - **Fighter** — tough melee warrior, straightforward and reliable
        - **Wizard** — powerful spellcaster, versatile but fragile
        - **Rogue** — stealthy and cunning, great at skills and sneak attacks
        - **Cleric** — divine healer and support, can hold their own in combat
        - **Ranger** — nature warrior with tracking, archery, and some magic
        - **Bard** — charismatic performer with magic and social skills
        - **Paladin** — holy knight with healing, strong in combat
        - **Barbarian** — raging powerhouse, tough and hard-hitting
        - **Druid** — nature mage who can shapeshift into animals
        - **Sorcerer** — innate spellcaster with raw magical talent
        - **Warlock** — magic from a pact with a powerful being
        - **Monk** — martial artist with supernatural speed and abilities
        Ask what kind of play style they enjoy to help them choose.

        LEVEL:
        Explain what level means (how experienced the character is).
        Recommend Level 1 for beginners or Level 3 for more abilities.
        Range is 1-20, but suggest staying in 1-5 for starting out.

        SUMMARY:
        Ask for a brief 5-10 word concept. Give examples based on their class:
        e.g., "Grizzled veteran seeking redemption" or "Curious scholar exploring ancient ruins."

        DESCRIPTION:
        Ask about personality, backstory, or appearance.
        Offer prompts: "What drives your character? Do they have a secret or a goal?
        What do they look like?" Give a short example.

        TAGS:
        Explain these are traits like race, background, and alignment.
        List common options:
        - Races: Human, Elf, Dwarf, Halfling, Half-Orc, Tiefling, Gnome, Dragonborn
        - Backgrounds: Soldier, Sage, Criminal, Folk Hero, Noble, Outlander, Acolyte
        - Alignment: Lawful Good, Chaotic Good, True Neutral, etc.
        Suggest a combination that fits their character so far.

        REVIEW:
        Show a summary and ask if everything looks right.
        """)
@RegisterAiService
@SessionScoped
public interface ActorCreationAssistant {

    @UserMessage("""
            Current stage: {stage}

            {#if currentActor}
            Character so far:
            {#if currentActor.name}- Name: {currentActor.name}
            {/if}{#if currentActor.actorClass}- Class: {currentActor.actorClass}
            {/if}{#if currentActor.level}- Level: {currentActor.level}
            {/if}{#if currentActor.summary}- Summary: {currentActor.summary}
            {/if}{#if currentActor.description}- Description: {currentActor.description}
            {/if}{#if currentActor.tags}- Tags: {#each currentActor.tags}{it}{#if it_hasNext}, {/if}{/each}
            {/if}{/if}

            Ask the player for the {stage} information. Be friendly and give examples if helpful.
            """)
    @OutputGuardrails(ActorCreationResponseGuardrail.class)
    ActorCreationResponse promptForStage(
            @MemoryId String chatMemoryId,
            String gameId,
            String adventureName,
            String stage,
            PlayerActor currentActor);

    @UserMessage("""
            Current stage: {stage}

            {#if currentActor}
            Character so far:
            {#if currentActor.name}- Name: {currentActor.name}
            {/if}{#if currentActor.actorClass}- Class: {currentActor.actorClass}
            {/if}{#if currentActor.level}- Level: {currentActor.level}
            {/if}{#if currentActor.summary}- Summary: {currentActor.summary}
            {/if}{#if currentActor.description}- Description: {currentActor.description}
            {/if}{#if currentActor.tags}- Tags: {#each currentActor.tags}{it}{#if it_hasNext}, {/if}{/each}
            {/if}{/if}

            The player said: {playerInput}

            Extract the {stage} information from their response. Include it in the patch field.
            Then acknowledge what they said and be encouraging.
            """)
    @OutputGuardrails(ActorCreationResponseGuardrail.class)
    ActorCreationResponse processStageInput(
            @MemoryId String chatMemoryId,
            String gameId,
            String adventureName,
            String stage,
            PlayerActor currentActor,
            String playerInput);
}
