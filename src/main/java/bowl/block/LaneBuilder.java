package bowl.block;

import bowl.Bowling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a full lane out from the foul line.
 *
 * <p>Eighteen blocks of surface is a lot to place by hand, and placing it
 * slightly crooked puts the pins somewhere the game does not expect - the rack
 * is measured from the foul line, so the lane has to agree with it. Building it
 * from the same block that owns the geometry means the two can never disagree.
 *
 * <p>The surface REPLACES the floor, because that is what laying a lane means -
 * an earlier version refused to touch solid blocks, which meant it politely
 * declined to build anything at all on ordinary ground and reported every
 * position as occupied.
 *
 * <p>Everything it replaces is broken with drops, so nothing is destroyed - the
 * blocks land at your feet. Block entities are the one hard stop: a chest or a
 * machine is never scenery, and popping one open to make room for a bowling
 * lane is not a trade anyone would accept. Those positions are skipped and
 * reported.
 */
public final class LaneBuilder {

    /** How far past the head pin to keep laying boards, so pins have a deck. */
    private static final int RUN_OUT = 4;

    /** Half-width of the boarded surface, in blocks either side of centre. */
    private static final int HALF_WIDTH = 1;

    /** Blocks of clear air above the surface. Pins are half a block tall. */
    private static final int HEADROOM = 3;

    private LaneBuilder() {
    }

    /** Result of a build attempt, for reporting back to the player. */
    public record Result(int placed, int blocked, int cleared, boolean outOfMaterials) {
        public Component message() {
            if (placed == 0 && outOfMaterials) {
                return Component.literal("You need lane boards to build a lane.");
            }
            if (placed == 0 && blocked > 0) {
                return Component.literal(
                        "Nothing to build here - " + blocked + " spots are protected.");
            }
            StringBuilder s = new StringBuilder("Lane built: " + placed + " blocks");
            if (cleared > 0) {
                s.append(", cleared ").append(cleared);
            }
            if (blocked > 0) {
                s.append(", skipped ").append(blocked).append(" protected");
            }
            if (outOfMaterials) {
                s.append(" - ran out of boards");
            }
            return Component.literal(s.append('.').toString());
        }
    }

    /**
     * Build the surface in front of {@code lane}.
     *
     * <p>The floor sits one block BELOW the foul line, because the foul line is
     * a lip you stand behind and the pins stand at its level - so the surface
     * the ball actually rolls on is the layer under both.
     */
    public static Result build(Level level, BlockPos lane, Direction facing, Player player) {
        BlockPos floor = lane.below();
        Direction right = facing.getClockWise();

        int length = (int) Math.ceil(BowlingLaneBlockEntity.LANE_LENGTH) + RUN_OUT;
        boolean creative = player.getAbilities().instabuild;

        List<BlockPos> boards = new ArrayList<>();
        List<BlockPos> gutters = new ArrayList<>();
        int blocked = 0;

        for (int f = 1; f <= length; f++) {
            for (int r = -(HALF_WIDTH + 1); r <= HALF_WIDTH + 1; r++) {
                BlockPos p = floor
                        .relative(facing, f)
                        .relative(right, r);
                boolean isGutter = Math.abs(r) > HALF_WIDTH;

                BlockState here = level.getBlockState(p);
                if (here.is(isGutter ? Bowling.GUTTER.get() : Bowling.LANE_BOARD.get())) {
                    continue;                       // already correct
                }
                if (isProtected(level, p)) {
                    blocked++;
                    continue;
                }
                (isGutter ? gutters : boards).add(p);
            }
        }

        int haveBoards = creative ? Integer.MAX_VALUE : count(player, Bowling.LANE_BOARD_ITEM.get());
        int haveGutters = creative ? Integer.MAX_VALUE : count(player, Bowling.GUTTER_ITEM.get());
        boolean short_ = boards.size() > haveBoards || gutters.size() > haveGutters;

        int placed = 0;
        placed += place(level, boards, Bowling.LANE_BOARD.get().defaultBlockState(),
                        haveBoards);
        placed += place(level, gutters, Bowling.GUTTER.get().defaultBlockState(),
                        haveGutters);

        if (!creative) {
            consume(player, Bowling.LANE_BOARD_ITEM.get(), Math.min(boards.size(), haveBoards));
            consume(player, Bowling.GUTTER_ITEM.get(), Math.min(gutters.size(), haveGutters));
        }

        // Clear headroom so the ball and pins have somewhere to be. Without
        // this a lane laid across sloping ground is a lane with a hill in it.
        int cleared = 0;
        for (int f = 1; f <= length; f++) {
            for (int r = -(HALF_WIDTH + 1); r <= HALF_WIDTH + 1; r++) {
                BlockPos column = floor.relative(facing, f).relative(right, r);
                for (int up = 1; up <= HEADROOM; up++) {
                    BlockPos over = column.above(up);
                    if (level.getBlockState(over).isAir() || isProtected(level, over)) {
                        continue;
                    }
                    level.destroyBlock(over, true);      // true = drop it
                    cleared++;
                }
            }
        }

        return new Result(placed, blocked, cleared, short_);
    }

    /**
     * Blocks the builder refuses to touch.
     *
     * <p>Anything holding a block entity - chests, machines, spawners - plus
     * anything indestructible. Everything else is fair game because it comes
     * back as an item.
     */
    private static boolean isProtected(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) {
            return false;
        }
        if (s.hasBlockEntity()) {
            return true;
        }
        return s.getDestroySpeed(level, p) < 0;      // bedrock, barrier, portal frame
    }

    private static int place(Level level, List<BlockPos> spots, BlockState state, int budget) {
        int n = 0;
        for (BlockPos p : spots) {
            if (n >= budget) {
                break;
            }
            // Break it first so the old block drops rather than vanishing.
            if (!level.getBlockState(p).isAir()) {
                level.destroyBlock(p, true);
            }
            level.setBlockAndUpdate(p, state);
            n++;
        }
        return n;
    }

    private static int count(Player player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void consume(Player player, net.minecraft.world.item.Item item, int amount) {
        int left = amount;
        for (ItemStack s : player.getInventory().items) {
            if (left <= 0) {
                break;
            }
            if (s.is(item)) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
    }
}
