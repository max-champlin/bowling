package bowl.item;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How much spin each player has dialled in, from -1 (full left) to +1 (right).
 *
 * <p>Server-side only and deliberately NOT saved. Spin is a per-throw choice,
 * not a setting - carrying it across a logout would mean picking the ball up
 * one day with a hook you no longer remember asking for.
 *
 * <p>Held here rather than on the item stack so it survives the ball leaving
 * your hand and coming back as a fresh stack, and so two players sharing a
 * lane cannot end up reading each other's spin.
 *
 * <p>Driven by the arrow keys: BowlingClient polls them each client tick and
 * sends a Spin packet, which BowlPackets.onSpin turns into an adjust() here.
 * That path IS wired - an earlier version of this comment claimed it was not,
 * long after it had been, and cost a debugging session.
 */
public final class SpinState {

    private static final Map<UUID, Float> SPIN = new HashMap<>();

    /** How much one keypress moves the dial. Ten presses covers the range. */
    public static final float STEP = 0.2f;

    private SpinState() {
    }

    public static float get(Player player) {
        return SPIN.getOrDefault(player.getUUID(), 0.0f);
    }

    public static void set(Player player, float value) {
        SPIN.put(player.getUUID(), clamp(value));
    }

    /** Nudge spin by one step. Positive is right-hand hook. */
    public static float adjust(Player player, float delta) {
        float next = clamp(get(player) + delta);
        SPIN.put(player.getUUID(), next);
        return next;
    }

    public static void clear(Player player) {
        SPIN.remove(player.getUUID());
    }

    private static float clamp(float v) {
        return Math.max(-1.0f, Math.min(1.0f, v));
    }
}
