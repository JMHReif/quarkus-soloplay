package dev.ebullient.soloplay.play;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.quarkus.logging.Log;

@ApplicationScoped
public class GamePlayNarrationGuardrail implements OutputGuardrail {

    public static final String REPROMPT_MESSAGE = "Your response had a JSON formatting error.";

    public static final String REPROMPT_PROMPT = "Do NOT call any tools. Respond ONLY with a JSON object. "
            + "Required fields: \"reasoning\" (your thinking), \"narration\" (story text). "
            + "You MUST also include exactly ONE of: \"pendingRoll\" (if a dice roll is needed) "
            + "or \"playerChoices\" (array of 2-3 suggested actions). Never omit both. "
            + "Do not acknowledge this correction.";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        try {
            // Tool-call responses are intermediate — let the framework handle them
            if (responseFromLLM.hasToolExecutionRequests()) {
                Log.debugf("NarrationGuardrail: passing through tool execution request");
                return OutputGuardrailResult.success();
            }

            Log.debugf("NarrationGuardrail AiMessage: %s", responseFromLLM.text());
            if (responseFromLLM.text() == null || responseFromLLM.text().isBlank()) {
                return reprompt("No text in response",
                        "You MUST respond with a JSON object now. Do NOT call any more tools. "
                                + REPROMPT_PROMPT);
            }

            GamePlayNarration narration = objectMapper.readValue(responseFromLLM.text(), GamePlayNarration.class);
            if (narration.narration() == null || narration.narration().isBlank()) {
                return reprompt("Missing narration", REPROMPT_PROMPT);
            }
            if (containsFieldLabels(narration.narration())) {
                return reprompt(
                        "The narration field must contain ONLY story prose. Do not include field labels like "
                                + "'Reasoning:', 'PendingRoll:', 'PlayerChoices:' inside the narration. "
                                + "Those belong in their own JSON fields.",
                        REPROMPT_PROMPT);
            }
            boolean hasRoll = narration.pendingRoll() != null;
            boolean hasChoices = narration.playerChoices() != null && !narration.playerChoices().isEmpty();

            // Auto-fix: if both are set, use reasoning to decide which to keep.
            // If the model mentioned a DC in its reasoning, it intended a roll — keep the roll.
            // Otherwise, keep choices (the model resolved the action and moved on).
            if (hasRoll && hasChoices) {
                boolean reasoningMentionsDC = narration.reasoning() != null
                        && DC_PATTERN.matcher(narration.reasoning()).find();
                if (reasoningMentionsDC) {
                    Log.debugf("NarrationGuardrail: both set, reasoning mentions DC — keeping roll, dropping choices");
                    narration = new GamePlayNarration(
                            narration.reasoning(), narration.narration(),
                            narration.pendingRoll(), null);
                } else {
                    Log.debugf("NarrationGuardrail: both set, no DC in reasoning — keeping choices, dropping roll");
                    narration = new GamePlayNarration(
                            narration.reasoning(), narration.narration(),
                            null, narration.playerChoices());
                }
                String fixed = objectMapper.writeValueAsString(narration);
                return OutputGuardrailResult.successWith(fixed, narration);
            }
            if (!hasRoll && !hasChoices) {
                return reprompt("Every response MUST include either a pendingRoll or playerChoices. "
                        + "If the narrative requires a dice roll, set pendingRoll. "
                        + "Otherwise, populate playerChoices with 2-3 concrete action options.",
                        REPROMPT_PROMPT);
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), narration);
        } catch (JsonProcessingException e) {
            return reprompt(REPROMPT_MESSAGE, e, REPROMPT_PROMPT);
        }
    }

    private static final java.util.regex.Pattern DC_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\bDC\\s*\\d+");

    private static final java.util.regex.Pattern FIELD_LABEL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(Reasoning|PendingRoll|PlayerChoices)\\s*:");

    private boolean containsFieldLabels(String narration) {
        return FIELD_LABEL_PATTERN.matcher(narration).find();
    }
}
