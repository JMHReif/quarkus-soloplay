package dev.ebullient.soloplay.play;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActorCreationEngineTest {

    @Test
    void helpMentionsCommands() {
        ActorCreationEngine engine = new ActorCreationEngine();
        GameResponse response = engine.help(null);

        assertTrue(response instanceof GameResponse.Reply);
        String markdown = ((GameResponse.Reply) response).assistantMarkdown();
        assertTrue(markdown.contains("/cancel"));
        assertTrue(markdown.contains("/done"));
        assertTrue(markdown.contains("/back"));
        assertTrue(markdown.contains("/status"));
        assertTrue(markdown.contains("/help"));
    }
}
