package bowl.block;

import bowl.Bowling;
import bowl.entity.BowlingPin;
import bowl.game.Rack;
import bowl.game.ScoreCard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a lane knows: whose turn it is, everyone's card, and the rack.
 *
 * <p>State lives on the BLOCK rather than on players, which buys three things
 * that would otherwise need solving separately: a game survives players
 * logging out, several players share one lane without the mod tracking who
 * stands where, and the scoreboard has one authoritative home so a client HUD
 * is only ever a view of it.
 *
 * <p>Pins are found by scanning for entities rather than remembered by UUID.
 * A pin can be despawned by a chunk unload, killed by a creeper, or shoved off
 * the lane, and a stored list would quietly rot. Scanning always reflects what
 * is actually there.
 */
public class BowlingLaneBlockEntity extends BlockEntity {

    /** Blocks from the foul line to the head pin. Standard lane, scaled down. */
    public static final double LANE_LENGTH = 18.0;

    /** How long to wait for pins to stop moving before counting the roll. */
    private static final int SETTLE_TICKS = 40;

    private final Map<UUID, ScoreCard> cards = new LinkedHashMap<>();
    private final List<UUID> order = new ArrayList<>();
    private int turn = 0;

    /** >0 while a roll is in flight and we are waiting for pins to settle. */
    private int settling = 0;
    private int standingAtRollStart = Rack.COUNT;

    public BowlingLaneBlockEntity(BlockPos pos, BlockState state) {
        super(Bowling.LANE_BE.get(), pos, state);
    }

    // ---------------------------------------------------------------- players

    public void join(Player player) {
        UUID id = player.getUUID();
        if (cards.containsKey(id)) {
            player.displayClientMessage(Component.literal(
                    "Already in this game - frame " + cards.get(id).currentFrame()), true);
            return;
        }
        cards.put(id, new ScoreCard());
        order.add(id);
        setChanged();
        player.displayClientMessage(Component.literal(
                "Joined the lane. Player " + order.size() + "."), true);
        if (order.size() == 1) {
            rackPins();
        }
        pushScores();
    }

    public ScoreCard cardFor(UUID id) {
        return cards.get(id);
    }

    public UUID whoseTurn() {
        return order.isEmpty() ? null : order.get(turn % order.size());
    }

    /** Advance to the next player who still has frames left. */
    private void nextTurn() {
        if (order.isEmpty()) {
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            turn = (turn + 1) % order.size();
            ScoreCard c = cards.get(order.get(turn));
            if (c != null && !c.isComplete()) {
                return;
            }
        }
        // everyone has finished
        announce(Component.literal("Game over."));
    }

    // ------------------------------------------------------------------- pins

    /** Where the head pin stands, in world space. */
    public Vec3 headPin() {
        Direction facing = getBlockState().getValue(BowlingLaneBlock.FACING);
        Vec3 centre = Vec3.atBottomCenterOf(worldPosition);
        return centre.add(facing.getStepX() * LANE_LENGTH, 0,
                          facing.getStepZ() * LANE_LENGTH);
    }

    /** World position of one pin, rotated into the lane's facing. */
    public Vec3 pinPos(int pinNumber) {
        Direction facing = getBlockState().getValue(BowlingLaneBlock.FACING);
        double[] o = Rack.offset(pinNumber);
        // "right" is the facing rotated clockwise; "forward" is the facing
        Direction right = facing.getClockWise();
        Vec3 head = headPin();
        return head.add(
                right.getStepX() * o[0] + facing.getStepX() * o[1],
                0,
                right.getStepZ() * o[0] + facing.getStepZ() * o[1]);
    }

    /** Every pin entity belonging to this lane. */
    public List<BowlingPin> pins() {
        if (level == null) {
            return List.of();
        }
        Vec3 head = headPin();
        AABB box = new AABB(head, head).inflate(Rack.width() + 2.0, 3.0, Rack.depth() + 2.0);
        return level.getEntitiesOfClass(BowlingPin.class, box);
    }

    public int standingPins() {
        return (int) pins().stream().filter(BowlingPin::isStanding).count();
    }

    /**
     * Remove every pin anywhere near this lane, in ANY direction.
     *
     * <p>Deliberately not {@link #pins()}, which looks only where the rack
     * belongs RIGHT NOW. Re-place the foul line facing a different way and the
     * previous rack falls outside that box, so it is never cleared - ten pins
     * stay standing in a direction nothing will ever roll, while the game racks
     * a fresh set somewhere else. Sweeping the full radius costs nothing at
     * rack time and cannot leave a straggler behind.
     */
    public void clearPins() {
        if (level == null) {
            return;
        }
        double reach = LANE_LENGTH + Rack.depth() + 6.0;
        AABB all = new AABB(Vec3.atBottomCenterOf(worldPosition),
                            Vec3.atBottomCenterOf(worldPosition)).inflate(reach, 6.0, reach);
        for (BowlingPin pin : level.getEntitiesOfClass(BowlingPin.class, all)) {
            pin.discard();
        }
    }

    /**
     * Remove every pin belonging to a lane, without needing the block entity.
     *
     * <p>Used when the lane is broken. Two things make this different from
     * {@link #clearPins()}: it takes the facing from the OLD block state rather
     * than reading it back off a block entity that is in the middle of being
     * torn down, and it sweeps the WHOLE lane rather than a tight box round the
     * rack - knocked pins slide, and a pin that skidded out of the rack box was
     * simply left lying in the world forever once its lane was gone.
     */
    public static void clearPinsAt(Level level, BlockPos pos, BlockState state) {
        if (level == null || !(state.getBlock() instanceof BowlingLaneBlock)) {
            return;
        }
        Direction facing = state.getValue(BowlingLaneBlock.FACING);
        Vec3 foul = Vec3.atBottomCenterOf(pos);
        Vec3 head = foul.add(facing.getStepX() * LANE_LENGTH, 0,
                             facing.getStepZ() * LANE_LENGTH);

        // everything between the foul line and well past the rack
        AABB box = new AABB(foul, head).inflate(Rack.width() + 6.0, 5.0,
                                                Rack.depth() + 6.0);
        for (BowlingPin pin : level.getEntitiesOfClass(BowlingPin.class, box)) {
            pin.discard();
        }
    }

    /**
     * Put out a fresh rack, or only the pins still standing.
     *
     * <p>Which pins to place comes from {@link ScoreCard#pinsStanding()} - the
     * scoring already knows whether this roll follows a strike, a spare or an
     * open frame, including the tenth-frame refills. Recomputing that here
     * would be a second source of truth waiting to disagree.
     */
    public void rackPins() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        clearPins();
        for (int p = 1; p <= Rack.COUNT; p++) {
            Vec3 pos = pinPos(p);
            BowlingPin pin = new BowlingPin(server, pos.x, pos.y, pos.z, p);
            // Tell it where the kickback walls are, measured from the head pin
            // along the lane's own axis.
            pin.setDeck(headPin(), getBlockState().getValue(BowlingLaneBlock.FACING));
            server.addFreshEntity(pin);
        }
        standingAtRollStart = Rack.COUNT;
    }

    // ------------------------------------------------------------------ rolls

    /**
     * Called when the ball LEAVES THE HAND, to snapshot what was standing.
     *
     * <p>This must happen at release and nowhere else. Scoring is the
     * difference between what stood before the roll and what stands after, so
     * taking the "before" count once the ball has already gone through the rack
     * makes every roll score zero - which is exactly what it did.
     */
    public void beginRoll() {
        standingAtRollStart = standingPins();
        setChanged();
    }

    /**
     * Called when the ball stops, to start the settle timer.
     *
     * <p>Deliberately separate from {@link #beginRoll()}: pins are still
     * toppling and shoving each other when the ball comes to rest, so the count
     * has to wait for them to finish falling.
     */
    public void ballStopped() {
        settling = SETTLE_TICKS;
        setChanged();
    }

    public void serverTick() {
        if (settling <= 0) {
            return;
        }
        settling--;
        if (settling > 0) {
            return;
        }
        scoreRoll();
    }

    /** Count what fell, record it, and set up the next roll. */
    private void scoreRoll() {
        UUID id = whoseTurn();
        if (id == null) {
            return;
        }
        ScoreCard card = cards.get(id);
        if (card == null || card.isComplete()) {
            return;
        }

        int felled = Math.max(0, standingAtRollStart - standingPins());
        int frameBefore = card.currentFrame();
        card.roll(felled);
        setChanged();

        announce(Component.literal("Knocked down " + felled
                + "  -  total " + card.total()));
        pushScores();

        if (card.isComplete() || card.currentFrame() != frameBefore) {
            nextTurn();
        }
        // The card knows whether the rack refills or the survivors stay.
        UUID next = whoseTurn();
        ScoreCard nextCard = next == null ? null : cards.get(next);
        if (nextCard != null && nextCard.pinsStanding() == Rack.COUNT) {
            rackPins();
        }
    }

    /** Send every participant their own scoreboard. */
    private void pushScores() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        for (UUID id : order) {
            if (server.getPlayerByUUID(id) instanceof net.minecraft.server.level.ServerPlayer sp) {
                bowl.net.BowlPackets.sendScore(sp, this);
            }
        }
    }

    private void announce(Component msg) {
        if (level instanceof ServerLevel server) {
            for (UUID id : order) {
                Player p = server.getPlayerByUUID(id);
                if (p != null) {
                    p.displayClientMessage(msg, true);
                }
            }
        }
    }

    public void resetGame() {
        cards.clear();
        order.clear();
        turn = 0;
        settling = 0;
        clearPins();
        setChanged();
    }

    // ------------------------------------------------------------------- save

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cards.clear();
        order.clear();
        ListTag list = tag.getList("Players", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            UUID id = t.getUUID("Id");
            ScoreCard card = new ScoreCard();
            for (int r : t.getIntArray("Rolls")) {
                card.roll(r);
            }
            cards.put(id, card);
            order.add(id);
        }
        turn = tag.getInt("Turn");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (UUID id : order) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            List<Integer> rolls = cards.get(id).rolls();
            int[] arr = new int[rolls.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = rolls.get(i);
            }
            t.putIntArray("Rolls", arr);
            list.add(t);
        }
        tag.put("Players", list);
        tag.putInt("Turn", turn);
    }
}
