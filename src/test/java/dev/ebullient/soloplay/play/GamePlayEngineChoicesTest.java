package dev.ebullient.soloplay.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.GameEffect.HtmlFragment;
import dev.ebullient.soloplay.play.model.GameState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GamePlayEngineChoicesTest {

    @Inject
    GamePlayEngine engine;

    @InjectMock
    GameRepository gameRepository;

    @InjectMock
    GamePlayAssistant assistant;

    @Test
    void choicesCreateHtmlFragmentEffect() {
        String gameId = "test-game";
        GameState game = new GameState();
        game.setGameId(gameId);
        game.setAdventureName("Test Adventure");
        game.setCurrentLocation("Somewhere");

        Mockito.when(gameRepository.findTheParty(gameId)).thenReturn(List.of());

        Mockito.when(assistant.turn(
                Mockito.eq(gameId),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any()))
                .thenReturn(new GamePlayResponse(
                        "narration",
                        "summary",
                        null,
                        List.of("Choice A", "Choice B"),
                        null,
                        "Somewhere",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null));

        GameResponse response = engine.processRequest(game, "hello", text -> {
        });
        assertTrue(response instanceof GameResponse.Reply);

        var reply = (GameResponse.Reply) response;
        var fragments = reply.effects().stream()
                .filter(e -> e instanceof HtmlFragment)
                .map(e -> (HtmlFragment) e)
                .toList();

        assertEquals(1, fragments.size());
        assertEquals("player_choices", fragments.getFirst().slot());
        assertTrue(fragments.getFirst().html().contains("player-choices"));
        assertTrue(fragments.getFirst().html().contains("Choice A"));
        assertTrue(fragments.getFirst().html().contains("Choice B"));
    }
}
