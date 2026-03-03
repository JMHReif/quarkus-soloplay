package dev.ebullient.soloplay.play.agents;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.quarkus.logging.Log;

@ApplicationScoped
public class DiceDecisionGuardrail implements OutputGuardrail {

    private static final String REPROMPT_PROMPT = "Respond ONLY with a JSON object. "
            + "Required field: \"rollNeeded\" (boolean). "
            + "If true, also provide: \"type\", \"skill\" or \"ability\", \"dc\", \"target\", \"context\". "
            + "If false, all other fields should be null.";

    @Inject
    ObjectMapper objectMapper;

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        try {
            Log.debugf("DiceGuardrail AiMessage: %s", responseFromLLM.text());
            if (responseFromLLM.text() == null || responseFromLLM.text().isBlank()) {
                return reprompt("No text in response", REPROMPT_PROMPT);
            }

            DiceDecision decision = objectMapper.readValue(responseFromLLM.text(), DiceDecision.class);

            // If rollNeeded but no context, reprompt
            if (decision.rollNeeded() && (decision.context() == null || decision.context().isBlank())) {
                return reprompt("When rollNeeded is true, you must provide context explaining the roll.",
                        REPROMPT_PROMPT);
            }

            return OutputGuardrailResult.successWith(responseFromLLM.text(), decision);
        } catch (JsonProcessingException e) {
            return reprompt("Your response had a JSON formatting error.", e, REPROMPT_PROMPT);
        }
    }
}
