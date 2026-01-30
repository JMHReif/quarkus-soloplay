package dev.ebullient.soloplay.play;

import dev.ebullient.soloplay.play.model.Stash;

public sealed interface GameEffect {

    /**
     * Pre-rendered HTML fragment intended for a specific UI slot.
     * Prefer templated server HTML (Qute) over arbitrary model output.
     */
    record HtmlFragment(String slot, String html) implements GameEffect {
    }

    /**
     * Arbitrary JSON payload for client-side rendering.
     */
    record JsonPayload(String name, Object payload) implements GameEffect {
    }

    /**
     * Stateful effect that carries round-trip state for server-stateless operation.
     * The client stores this stash and sends it back with subsequent requests.
     *
     * @param slot UI slot identifier (e.g., "pending_roll", "actor_draft")
     * @param html Pre-rendered HTML for display (may be null for hidden state)
     * @param stash The state to round-trip through the client
     */
    record StatefulEffect(String slot, String html, Stash stash) implements GameEffect {

        /**
         * Create a stateful effect that clears the slot (null stash).
         */
        public static StatefulEffect clear(String slot) {
            return new StatefulEffect(slot, null, null);
        }
    }
}
