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
public class ActorCreationResponseGuardrail implements OutputGuardrail {

    /**
     * The default message to use when reprompting (JsonExtractorOutputGuardrail)
     */
    public static final String REPROMPT_MESSAGE = "Your response had a JSON formatting error.";

    /**
     * The default prompt to append to the LLM during a reprompt (JsonExtractorOutputGuardrail)
     */
    public static final String REPROMPT_PROMPT = "Please try again. Remember: respond ONLY with a JSON object containing \"message\" (your response to the player) and \"patch\" (character updates or null). Do not acknowledge this correction - just provide the correct JSON response to the player's last message.";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        Log.debugf("Guardrail validating: %s", text);
        try {
            ActorCreationResponse response = objectMapper.readValue(text, ActorCreationResponse.class);
            if (response.message() == null || response.message().isBlank()) {
                Log.warn("Guardrail: missing message field");
                return reprompt("Missing message to ", REPROMPT_PROMPT);
            }
            Log.debugf("Guardrail passed: message=%s, patch=%s", response.message(), response.patch());
            return OutputGuardrailResult.successWith(text, response);
        } catch (JsonProcessingException e) {
            Log.warnf("Guardrail JSON parse error: %s\nInput was: %s", e.getMessage(), text);
            return reprompt(REPROMPT_MESSAGE, e, REPROMPT_PROMPT);
        }
    }
}
