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
public class GamePlayResponseGuardrail implements OutputGuardrail {

    public static final String REPROMPT_MESSAGE = "Your response had a JSON formatting error.";

    public static final String REPROMPT_PROMPT = "Do NOT call any tools. Respond ONLY with a JSON object. "
            + "Required fields: \"reasoning\" (your thinking), \"narration\" (story text), "
            + "\"turnSummary\" (1-2 sentences), \"currentLocation\" (just the name). "
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
                Log.debugf("Guardrail: passing through tool execution request");
                return OutputGuardrailResult.success();
            }

            Log.debugf("Guardrail AiMessage: %s", responseFromLLM.text());
            if (responseFromLLM.text() == null || responseFromLLM.text().isBlank()) {
                return reprompt("No text in response",
                        "You MUST respond with a JSON object now. Do NOT call any more tools. "
                                + REPROMPT_PROMPT);
            }

            GamePlayResponse response = objectMapper.readValue(responseFromLLM.text(), GamePlayResponse.class);
            if (response.narration() == null || response.narration().isBlank()) {
                return reprompt("Missing narration", REPROMPT_PROMPT);
            }
            if (containsFieldLabels(response.narration())) {
                return reprompt(
                        "The narration field must contain ONLY story prose. Do not include field labels like "
                                + "'Reasoning:', 'Turn Summary:', 'PendingRoll:', or 'PlayerChoices:' "
                                + "inside the narration. Those belong in their own JSON fields.",
                        REPROMPT_PROMPT);
            }

            boolean hasRoll = response.pendingRoll() != null;
            boolean hasChoices = response.playerChoices() != null && !response.playerChoices().isEmpty();

            // Auto-fix: if both are set, use reasoning to decide which to keep.
            if (hasRoll && hasChoices) {
                boolean reasoningMentionsDC = response.reasoning() != null
                        && DC_PATTERN.matcher(response.reasoning()).find();
                if (reasoningMentionsDC) {
                    Log.debugf("Guardrail: both set, reasoning mentions DC — keeping roll, dropping choices");
                    response = withRollOnly(response);
                } else {
                    Log.debugf("Guardrail: both set, no DC in reasoning — keeping choices, dropping roll");
                    response = withChoicesOnly(response);
                }
                String fixed = objectMapper.writeValueAsString(response);
                return OutputGuardrailResult.successWith(fixed, response);
            }
            if (!hasRoll && !hasChoices) {
                return reprompt("Every response MUST include either a pendingRoll or playerChoices. "
                        + "If the narrative requires a dice roll, set pendingRoll. "
                        + "Otherwise, populate playerChoices with 2-3 concrete action options.",
                        REPROMPT_PROMPT);
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), response);
        } catch (JsonProcessingException e) {
            return reprompt(REPROMPT_MESSAGE, e, REPROMPT_PROMPT);
        }
    }

    private static GamePlayResponse withRollOnly(GamePlayResponse r) {
        return new GamePlayResponse(r.reasoning(), r.narration(), r.turnSummary(),
                r.pendingRoll(), null,
                r.currentLocation(), r.segmentComplete(), r.majorDecision(), r.checkpoint());
    }

    private static GamePlayResponse withChoicesOnly(GamePlayResponse r) {
        return new GamePlayResponse(r.reasoning(), r.narration(), r.turnSummary(),
                null, r.playerChoices(),
                r.currentLocation(), r.segmentComplete(), r.majorDecision(), r.checkpoint());
    }

    private static final java.util.regex.Pattern DC_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\bDC\\s*\\d+");

    private static final java.util.regex.Pattern FIELD_LABEL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(Reasoning|Turn Summary|PendingRoll|PlayerChoices|segmentComplete|majorDecision)\\s*:");

    private boolean containsFieldLabels(String narration) {
        return FIELD_LABEL_PATTERN.matcher(narration).find();
    }
}
