package dev.ebullient.soloplay.play.agents;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.quarkus.logging.Log;

@ApplicationScoped
public class NarrationResponseGuardrail implements OutputGuardrail {

    private static final String REPROMPT_PROMPT = "Respond ONLY with JSON: "
            + "{\"reasoning\":\"...\", \"narration\":\"...\", \"currentLocation\":\"...\"}. "
            + "Keep narration UNDER 100 words.";

    private static final String TRUNCATION_PROMPT = "Your response was truncated. "
            + "Write a MUCH SHORTER response. Narration must be under 80 words. "
            + "Respond ONLY with compact JSON, no extra whitespace.";

    private static final java.util.regex.Pattern FIELD_LABEL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(Reasoning|Turn Summary|PendingRoll|PlayerChoices|segmentComplete|majorDecision)\\s*:");

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        try {
            if (responseFromLLM.hasToolExecutionRequests()) {
                Log.debugf("NarrationGuardrail: passing through tool execution request");
                return OutputGuardrailResult.success();
            }

            Log.debugf("NarrationGuardrail AiMessage: %s", responseFromLLM.text());
            if (responseFromLLM.text() == null || responseFromLLM.text().isBlank()) {
                return reprompt("No text in response", REPROMPT_PROMPT);
            }

            NarrationResponse response = objectMapper.readValue(responseFromLLM.text(), NarrationResponse.class);
            if (response.narration() == null || response.narration().isBlank()) {
                return reprompt("Missing narration", REPROMPT_PROMPT);
            }
            if (FIELD_LABEL_PATTERN.matcher(response.narration()).find()) {
                return reprompt(
                        "The narration field must contain ONLY story prose. No field labels.",
                        REPROMPT_PROMPT);
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), response);
        } catch (JsonEOFException e) {
            // Truncated output — ask for a much shorter response
            Log.warnf("NarrationGuardrail: response truncated (JsonEOFException)");
            return reprompt("Response was truncated.", e, TRUNCATION_PROMPT);
        } catch (JsonProcessingException e) {
            return reprompt("JSON formatting error.", e, REPROMPT_PROMPT);
        }
    }
}
