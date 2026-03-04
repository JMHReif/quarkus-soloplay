package dev.ebullient.soloplay.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.ebullient.soloplay.GameRepository;
import dev.ebullient.soloplay.play.GameEffect.HtmlFragment;
import dev.ebullient.soloplay.play.agents.AgentOrchestrator;
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
    AgentOrchestrator orchestrator;

    @Test
    void choicesCreateHtmlFragmentEffect() {
        String gameId = "test-game";
        GameState game = new GameState();
        game.setGameId(gameId);
        game.setAdventureName("Test Adventure");
        game.setCurrentLocation("Somewhere");

        Mockito.when(gameRepository.findTheParty(gameId)).thenReturn(List.of());
        Mockito.when(gameRepository.getCheckpoints(gameId)).thenReturn(List.of());

        Mockito.when(orchestrator.turn(
                Mockito.eq(gameId),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(new GamePlayResponse(
                        "Player wants to explore. No roll needed.",
                        "narration",
                        "summary",
                        null,
                        List.of("Choice A", "Choice B"),
                        "Somewhere",
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
