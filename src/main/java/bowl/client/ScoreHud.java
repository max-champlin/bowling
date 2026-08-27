package bowl.client;

import bowl.Bowling;
import bowl.game.ScoreCard;
import bowl.net.ClientScore;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.List;

/**
 * The scoresheet, drawn across the top of the screen.
 *
 * <p>A strip of ten frames with the roll marks above and the running total
 * below, which is exactly how a paper scoresheet reads and therefore needs no
 * explaining to anyone who has bowled.
 *
 * <p>Draws nothing unless a game is actually in progress and recently updated,
 * so it does not sit on screen while you are mining. The client never computes
 * any of these numbers - it renders what the lane sent.
 */
@EventBusSubscriber(modid = Bowling.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class ScoreHud {

    private static final int FRAME_W = 22;
    private static final int TOP = 4;

    private static final int BG = 0xB0000000;
    private static final int LINE = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF9A9A9A;
    private static final int LIVE = 0xFFFFD24A;      // the frame you are on

    private ScoreHud() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "scoreboard"),
                (LayeredDraw.Layer) ScoreHud::draw);
    }

    private static void draw(GuiGraphics g, DeltaTracker delta) {
        if (!ClientScore.visible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }

        List<String> marks = ClientScore.marks();
        List<Integer> totals = ClientScore.totals();
        int current = ClientScore.frame();

        int width = FRAME_W * ScoreCard.FRAMES;
        int x0 = (g.guiWidth() - width) / 2;

        g.fill(x0 - 2, TOP - 2, x0 + width + 2, TOP + 24, BG);

        // Marks are a flat list of rolls; walk them frame by frame so the
        // tenth can hold three and everything else holds two.
        int m = 0;
        for (int frame = 0; frame < ScoreCard.FRAMES; frame++) {
            int x = x0 + frame * FRAME_W;
            boolean tenth = frame == ScoreCard.FRAMES - 1;
            int slots = tenth ? 3 : 2;

            g.fill(x, TOP, x + 1, TOP + 22, LINE);

            StringBuilder row = new StringBuilder();
            for (int i = 0; i < slots && m < marks.size(); i++) {
                String mark = marks.get(m);
                // a strike closes a normal frame, so it occupies the box alone
                if (mark.equals("X") && !tenth && i == 0) {
                    row.append("X");
                    m++;
                    break;
                }
                row.append(mark);
                m++;
            }

            int colour = (frame + 1) == current ? LIVE : TEXT;
            g.drawString(mc.font, row.toString(), x + 4, TOP + 2, colour, false);

            String total = frame < totals.size() ? String.valueOf(totals.get(frame)) : "";
            g.drawString(mc.font, total, x + 4, TOP + 12, DIM, false);
        }
        g.fill(x0 + width, TOP, x0 + width + 1, TOP + 22, LINE);

        int spin = ClientScore.spinPercent();
        if (spin != 0) {
            String label = (spin > 0 ? "spin right " : "spin left ") + Math.abs(spin) + "%";
            g.drawString(mc.font, label, x0, TOP + 26, LIVE, false);
        }
    }
}
