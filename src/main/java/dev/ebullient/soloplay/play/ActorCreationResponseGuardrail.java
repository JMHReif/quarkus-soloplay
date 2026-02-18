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
    public static final String REPROMPT_MESSAGE = "Invalid JSON";

    /**
     * The default prompt to append to the LLM during a reprompt (JsonExtractorOutputGuardrail)
     */
    public static final String REPROMPT_PROMPT = "Make sure you return a valid JSON object following the specified format";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        try {
            Log.debugf("ActorCreation AiMessage: %s", responseFromLLM.text());
            ActorCreationResponse response = objectMapper.readValue(responseFromLLM.text(), ActorCreationResponse.class);
            Log.debugf("ActorCreation parsed response - messageMarkdown: %s",
                    response.messageMarkdown() == null ? "(null)"
                            : response.messageMarkdown().substring(0, Math.min(100, response.messageMarkdown().length())));

            // Validate required field is present
            if (response.messageMarkdown() == null || response.messageMarkdown().isBlank()) {
                Log.warnf("ActorCreation response missing messageMarkdown field");
                return reprompt("Missing messageMarkdown",
                        "Your response must include a 'messageMarkdown' field with your message to the player. " +
                                "Return JSON like: {\"messageMarkdown\": \"your message here\", \"patch\": {...}}");
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), response);
        } catch (JsonProcessingException e) {
            Log.warnf("ActorCreation JSON parse failed: %s", e.getMessage());
            return reprompt(REPROMPT_MESSAGE, e, REPROMPT_PROMPT);
        }
    }
}
