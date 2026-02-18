package dev.ebullient.soloplay.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;

import dev.ebullient.soloplay.play.GameEffect.HtmlFragment;
import dev.ebullient.soloplay.play.GameEffect.StatefulEffect;
import dev.ebullient.soloplay.play.model.GameState;
import dev.ebullient.soloplay.play.model.PendingRoll;
import dev.ebullient.soloplay.play.model.PendingRollStash;
import dev.ebullient.soloplay.play.model.RollResult;
import dev.ebullient.soloplay.play.model.Stash;
import io.quarkus.logging.Log;

@ApplicationScoped
public class RollHandler {
    public static final String SLOT = "pending_roll";

    // Matches a single dice term: NdX (e.g., "2d6", "d20", "1d8")
    private static final Pattern DICE_TERM = Pattern.compile(
            "(\\d*)d(\\d+)", Pattern.CASE_INSENSITIVE);
    // Matches a numeric modifier: +5, -3
    private static final Pattern MODIFIER_TERM = Pattern.compile(
            "([+-])(\\d+)");

    /**
     * Handle a roll command using client-provided pending roll state.
     *
     * @param clientStash The stash round-tripped from the client (may contain PendingRollStash)
     * @param trimmed The roll input (e.g., "/roll 1d20+5" or "15")
     * @return The roll result, or null if no pending roll or invalid input
     */
    public RollResult handleRollCommand(Stash clientStash, String trimmed) {
        PendingRoll pending = (clientStash instanceof PendingRollStash prs) ? prs.toPendingRoll() : null;
        if (pending == null) {
            return null;
        }
        Log.debugf("Pending roll: %s", pending.render());

        // Strip /roll prefix and normalize whitespace
        String input = trimmed.replaceFirst("^/roll\\s*", "").replaceAll("\\s+", "").trim();

        int total;
        String breakdown;

        if (input.matches("\\d+")) {
            // Plain number - player rolled physical dice
            total = Integer.parseInt(input);
            breakdown = input + " (player roll)";
        } else {
            // Dice notation: supports d12+d6+5, 2d6-1, d20+d4+2, etc.
            Matcher diceMatcher = DICE_TERM.matcher(input);
            Matcher modMatcher = MODIFIER_TERM.matcher(input);

            List<String> breakdownParts = new ArrayList<>();
            total = 0;
            boolean foundDice = false;

            // Find all dice terms
            while (diceMatcher.find()) {
                foundDice = true;
                String numDiceStr = diceMatcher.group(1);
                int numDice = numDiceStr.isEmpty() ? 1 : Integer.parseInt(numDiceStr);
                int dieSize = Integer.parseInt(diceMatcher.group(2));

                // Roll the dice
                int[] rolls = IntStream.range(0, numDice)
                        .map(i -> ThreadLocalRandom.current().nextInt(1, dieSize + 1))
                        .toArray();

                int diceSum = IntStream.of(rolls).sum();
                total += diceSum;

                // Build breakdown for this dice term
                String diceNotation = (numDice == 1 ? "" : numDice) + "d" + dieSize;
                String rollsDisplay = numDice == 1
                        ? String.valueOf(rolls[0])
                        : IntStream.of(rolls).mapToObj(String::valueOf).collect(Collectors.joining(", "));
                breakdownParts.add(diceNotation + " (" + rollsDisplay + ")");
            }

            // Find numeric modifier (only the last +N or -N that isn't part of dice)
            // We need to find modifiers that aren't followed by 'd'
            String remaining = DICE_TERM.matcher(input).replaceAll("");
            modMatcher = MODIFIER_TERM.matcher(remaining);
            while (modMatcher.find()) {
                String sign = modMatcher.group(1);
                int mod = Integer.parseInt(modMatcher.group(2));
                if (sign.equals("-")) {
                    mod = -mod;
                }
                total += mod;
                String modSign = mod > 0 ? " + " : " - ";
                breakdownParts.add(modSign.trim() + " " + Math.abs(mod));
            }

            if (!foundDice) {
                Log.warnf("Invalid dice notation: %s", input);
                total = 0;
                breakdown = "invalid input";
            } else {
                breakdown = String.join(" + ", breakdownParts).replace(" + - ", " - ") + " = " + total;
            }
        }

        boolean success = pending.dc() == null || total >= pending.dc();

        return new RollResult(
                pending.type(),
                total,
                breakdown,
                success,
                pending.context());
    }

    /**
     * Create a StatefulEffect for a pending roll (for round-trip through client).
     * Returns null if pendingRoll is null.
     */
    public StatefulEffect createPendingRollEffect(PendingRoll pendingRoll) {
        if (pendingRoll == null) {
            return null;
        }
        PendingRollStash stash = PendingRollStash.from(pendingRoll);
        return new StatefulEffect(SLOT, pendingRoll.render(), stash);
    }

    /**
     * Create a StatefulEffect that clears the pending roll state.
     */
    public StatefulEffect clearPendingRollEffect() {
        return StatefulEffect.clear(SLOT);
    }

    /**
     * Check if the input looks like a roll command.
     */
    public boolean isRollInput(String input) {
        return input.startsWith("/roll") || input.matches("\\d+");
    }

    /**
     * Get the pending roll from GameState stash.
     */
    public PendingRoll getPendingRoll(GameState game) {
        PendingRollStash prs = game.getStash(SLOT, PendingRollStash.class);
        return prs != null ? prs.toPendingRoll() : null;
    }

    /**
     * Clear the pending roll from GameState stash.
     */
    public void clearPendingRoll(GameState game) {
        game.removeStash(SLOT);
    }

    /**
     * Store a pending roll in GameState stash and return an HtmlFragment effect.
     */
    public Optional<HtmlFragment> setPendingRoll(GameState game, PendingRoll roll) {
        if (roll == null) {
            return Optional.empty();
        }
        PendingRollStash stash = PendingRollStash.from(roll);
        game.putStash(SLOT, stash);
        return Optional.of(new HtmlFragment(SLOT, roll.render()));
    }

    /**
     * Handle a roll command using GameState-stored pending roll.
     */
    public RollResult handleRollCommand(GameState game, String trimmed) {
        PendingRollStash prs = game.getStash(SLOT, PendingRollStash.class);
        return handleRollCommand(prs, trimmed);
    }
}
