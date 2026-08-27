package bowl.net;

import bowl.Bowling;
import bowl.block.BowlingLaneBlockEntity;
import bowl.game.ScoreCard;
import bowl.item.SpinState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * The client/server conversation, which is three short sentences.
 *
 * <p>Spin and release travel client to server because they are inputs, and the
 * server must not trust the client for anything but "the player pressed a
 * key". The scoreboard travels server to client because the lane block owns
 * the game and the HUD is only ever a view of it - the client never computes a
 * score, it is told one.
 */
public final class BowlPackets {

    private BowlPackets() {
    }

    // ------------------------------------------------------------ client -> server

    /** Nudge the spin dial. Sent on each arrow-key press. */
    public record Spin(float delta) implements CustomPacketPayload {
        public static final Type<Spin> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "spin"));
        public static final StreamCodec<ByteBuf, Spin> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Spin::delta, Spin::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Space was pressed while holding the ball. */
    public record Release() implements CustomPacketPayload {
        public static final Type<Release> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "release"));
        public static final StreamCodec<ByteBuf, Release> CODEC =
                StreamCodec.unit(new Release());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------ server -> client

    /**
     * The scoreboard, as the HUD needs it.
     *
     * <p>Sent whole rather than as deltas. It is a handful of small strings and
     * ints once per roll; a diffing protocol would be more code and more ways
     * for the display to drift out of step with the lane.
     */
    public record Score(List<String> marks, List<Integer> totals,
                        int frame, int spinPercent) implements CustomPacketPayload {
        public static final Type<Score> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "score"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Score> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Score::marks,
                        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Score::totals,
                        ByteBufCodecs.VAR_INT, Score::frame,
                        ByteBufCodecs.VAR_INT, Score::spinPercent,
                        Score::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- wiring

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Spin.TYPE, Spin.CODEC, BowlPackets::onSpin);
        registrar.playToServer(Release.TYPE, Release.CODEC, BowlPackets::onRelease);
        registrar.playToClient(Score.TYPE, Score.CODEC, ClientScore::accept);
    }

    private static void onSpin(Spin msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            float now = SpinState.adjust(p, msg.delta());
            p.displayClientMessage(Component.literal(
                    "Spin " + Math.round(now * 100) + "%"), true);
        });
    }

    /**
     * Space pressed. The item's own charge-and-release is still what throws
     * the ball; this exists so the agreed control works, and it simply asks
     * the held item to finish its use.
     */
    private static void onRelease(Release msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.isUsingItem()) {
                sp.releaseUsingItem();
            }
        });
    }

    /** Push the current scoreboard to one player. */
    public static void sendScore(ServerPlayer player, BowlingLaneBlockEntity lane) {
        ScoreCard card = lane.cardFor(player.getUUID());
        if (card == null) {
            return;
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new Score(card.marks(), card.frameTotals(), card.currentFrame(),
                        Math.round(SpinState.get(player) * 100))));
    }

    /** Where a lane can be found from, for the HUD's benefit. */
    public static BlockPos noLane() {
        return BlockPos.ZERO;
    }
}
