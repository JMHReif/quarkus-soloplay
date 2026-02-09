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
    /**
     * The default message to use when reprompting (JsonExtractorOutputGuardrail)
     */
    public static final String REPROMPT_MESSAGE = "Your response had a JSON formatting error.";

    /**
     * The default prompt to append to the LLM during a reprompt (JsonExtractorOutputGuardrail)
     */
    public static final String REPROMPT_PROMPT = "Please try again. Respond ONLY with a JSON object. Required fields: \"narration\" (story text), \"turnSummary\" (1-2 sentences), \"currentLocation\" (just the name), \"actorsPresent\" (array of name strings), \"locationsPresent\" (array of name strings). Do not acknowledge this correction.";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        try {
            Log.debugf("AiMessage: %s", objectMapper.writeValueAsString(responseFromLLM));
            GamePlayResponse response = objectMapper.readValue(responseFromLLM.text(), GamePlayResponse.class);
            if (response.narration() == null) {
                return reprompt("Missing narration", REPROMPT_PROMPT);
            }
            if (response.pendingRoll() != null && response.playerChoices() != null && !response.playerChoices().isEmpty()) {
                // The LLM violated the constraint - force correction
                return reprompt("Offer only a roll or a choice of actions", REPROMPT_PROMPT);
            }
            if (response.patches() != null) {
                for (var patch : response.patches()) {
                    if (patch.type() == null || patch.name() == null) {
                        return reprompt("Each patch must have a \"type\" (\"actor\" or \"location\") and a \"name\"",
                                REPROMPT_PROMPT);
                    }
                }
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), response);
        } catch (JsonProcessingException e) {
            return reprompt(REPROMPT_MESSAGE, e, REPROMPT_PROMPT);
        }
    }
}
