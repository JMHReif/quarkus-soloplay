package dev.ebullient.soloplay.play.model;

/**
 * Wrapper for PendingRoll that implements Stash for client round-trip.
 * This separates the Jackson polymorphic typing (Stash) from the LLM response parsing (PendingRoll).
 */
public record PendingRollStash(
        String type,
        String skill,
        String ability,
        Integer dc,
        String target,
        String context) implements Stash {

    public static PendingRollStash from(PendingRoll roll) {
        if (roll == null) {
            return null;
        }
        return new PendingRollStash(
                roll.type(),
                roll.skill(),
                roll.ability(),
                roll.dc(),
                roll.target(),
                roll.context());
    }

    public PendingRoll toPendingRoll() {
        return new PendingRoll(type, skill, ability, dc, target, context);
    }
}
