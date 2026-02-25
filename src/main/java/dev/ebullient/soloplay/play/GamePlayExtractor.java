package dev.ebullient.soloplay.play;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;

@SystemMessage(fromResource = "prompts/game-play-extract-system.txt")
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@OutputGuardrails(GamePlayResponseGuardrail.class)
public interface GamePlayExtractor {

    @UserMessage(fromResource = "prompts/game-play-extract-user.txt")
    GamePlayResponse extract(String narration, String reasoning);
}
