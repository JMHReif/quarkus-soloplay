package dev.ebullient.soloplay.play;

/**
 * Per-request event sink for emitting incremental updates (e.g. status text)
 * while an engine is processing a request.
 */
public interface GameEventEmitter {
    void assistantDelta(String text);

    /** Emit an effect (e.g. roll result) immediately to the client. */
    default void emitEffect(GameEffect effect) {
        // no-op by default
    }

    static GameEventEmitter noop() {
        return text -> {
        };
    }
}
