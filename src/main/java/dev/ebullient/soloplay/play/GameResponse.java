package dev.ebullient.soloplay.play;

import java.util.ArrayList;
import java.util.List;

public sealed interface GameResponse {

    static Reply reply(String assistantMarkdown, GameEffect... effects) {
        return new Reply(assistantMarkdown, effects == null ? List.of() : List.of(effects));
    }

    static Reply reply(String assistantMarkdown, List<GameEffect> effects) {
        return new Reply(assistantMarkdown, effects == null ? List.of() : effects);
    }

    static Error error(String message) {
        return new Error(message);
    }

    /**
     * @param assistantMarkdown The assistant's response text
     * @param effects Side effects to apply (UI updates, state round-trips, etc.)
     *        StatefulEffect entries carry round-trip state for server-stateless operation.
     */
    record Reply(String assistantMarkdown, List<GameEffect> effects) implements GameResponse {
        public Reply {
            if (effects == null) {
                effects = List.of();
            }
        }

        /**
         * Create a new Reply with additional effects appended.
         */
        public Reply withEffects(GameEffect... additionalEffects) {
            if (additionalEffects == null || additionalEffects.length == 0) {
                return this;
            }
            var combined = new ArrayList<>(this.effects);
            combined.addAll(List.of(additionalEffects));
            return new Reply(this.assistantMarkdown, combined);
        }
    }

    record Error(String message) implements GameResponse {
    }
}
