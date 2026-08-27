package bowl.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The client's copy of the scoreboard.
 *
 * <p>Plain static state, because there is exactly one local player and exactly
 * one scoreboard they can be looking at. The HUD reads it; nothing else writes
 * it.
 *
 * <p>Marked client-only so a dedicated server never loads it - the handler is
 * registered for the play-to-client direction, which a server never receives.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientScore {

    private static List<String> marks = List.of();
    private static List<Integer> totals = List.of();
    private static int frame = 0;
    private static int spinPercent = 0;
    private static long lastUpdate = 0;

    /** How long the HUD stays up after the last update, in milliseconds. */
    private static final long SHOW_FOR = 20_000;

    private ClientScore() {
    }

    static void accept(BowlPackets.Score msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            marks = msg.marks();
            totals = msg.totals();
            frame = msg.frame();
            spinPercent = msg.spinPercent();
            lastUpdate = System.currentTimeMillis();
        });
    }

    public static boolean visible() {
        return frame > 0 && System.currentTimeMillis() - lastUpdate < SHOW_FOR;
    }

    public static List<String> marks() {
        return marks;
    }

    public static List<Integer> totals() {
        return totals;
    }

    public static int frame() {
        return frame;
    }

    public static int spinPercent() {
        return spinPercent;
    }
}
