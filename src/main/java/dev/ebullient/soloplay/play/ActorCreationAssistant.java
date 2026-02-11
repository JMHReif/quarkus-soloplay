package dev.ebullient.soloplay.play;

import jakarta.enterprise.context.SessionScoped;

import dev.ebullient.soloplay.play.model.PlayerActor;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

<<<<<<< Updated upstream
@SystemMessage(fromResource = "prompts/actor-creation-system.txt")
@RegisterAiService(tools = LoreTools.class, retrievalAugmentor = LoreRetriever.class)
@SessionScoped
public interface ActorCreationAssistant {

    @UserMessage(fromResource = "prompts/actor-creation-step.txt")
=======
@SystemMessage("""
        You are a friendly D&D character creation assistant. Help the player create their character ONE STEP AT A TIME.

        YOU MUST RESPOND WITH ONLY THIS JSON FORMAT:

        {"messageMarkdown": "your message", "patch": null}

        OR when the player provides information:

        {"messageMarkdown": "your message", "patch": {"name": "value", "actorClass": null, "level": null, "summary": null, "description": null, "tags": null, "aliases": null}}

        RULES:
        - messageMarkdown: REQUIRED. Your friendly message to the player.
        - patch: null if no new info, or an object with ONLY the field being collected (others null).
        - Be conversational and encouraging.
        - Ask ONE question at a time based on the current stage.
        - Output ONLY the JSON. No other text.

        STAGES:
        - NAME: Ask for character name
        - CLASS: Ask for character class (Fighter, Wizard, Rogue, etc.)
        - LEVEL: Ask for starting level (suggest 1-5)
        - SUMMARY: Ask for a brief 5-10 word description
        - DESCRIPTION: Ask for backstory/personality
        - TAGS: Ask for race, background, alignment as tags
        - REVIEW: Confirm the character looks good
        """)
@RegisterAiService
@SessionScoped
public interface ActorCreationAssistant {

    @UserMessage("""
            Current stage: {stage}

            {#if currentActor}
            Character so far:
            - Name: {currentActor.name}
            - Class: {currentActor.actorClass}
            - Level: {currentActor.level}
            - Summary: {currentActor.summary}
            - Description: {currentActor.description}
            - Tags: {currentActor.tags}
            {/if}

            Ask the player for the {stage} information. Be friendly and give examples if helpful.
            """)
>>>>>>> Stashed changes
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
            - Name: {currentActor.name}
            - Class: {currentActor.actorClass}
            - Level: {currentActor.level}
            - Summary: {currentActor.summary}
            - Description: {currentActor.description}
            - Tags: {currentActor.tags}
            {/if}

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
