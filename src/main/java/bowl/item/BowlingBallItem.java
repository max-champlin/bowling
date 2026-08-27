package bowl.item;

import bowl.Bowling;
import bowl.block.BowlingLaneBlock;
import bowl.block.BowlingLaneBlockEntity;
import bowl.entity.BowlingBall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Hold to wind up, release to throw.
 *
 * <p>Power comes from how long it is held, direction from where the player is
 * facing, and spin from a value carried on the stack that the arrow keys will
 * set. Aiming is therefore entirely the player's business - walk to your mark,
 * face the pocket, hold as long as you dare. No assist, by design.
 *
 * <p>Uses the vanilla "use item" charge rather than the jump key for now. The
 * agreed control is SPACE to release, but jump is bound at the client and
 * intercepting it needs a client-side keybind layer; that is a separate piece
 * and this works standalone until it lands.
 */
public class BowlingBallItem extends Item {

    /** Ticks of hold for full power. 20 ticks = one second. */
    public static final int FULL_CHARGE = 20;

    /** How far in front of the player the ball appears. */
    private static final double RELEASE_AHEAD = 0.6;

    /**
     * Widest angle off the lane axis the ball can be launched, in radians.
     *
     * <p>12 degrees. Deliberately generous against a real delivery, which is
     * only a few degrees, because the player is aiming with a mouse rather than
     * a practised arm swing. It still has to stay small: the hook only turns
     * the ball about 6.5 degrees, so anything aimed wider than that can never
     * come back and simply runs off into the gutter - which is a fair enough
     * punishment for over-aiming, and exactly what happens on a real lane.
     */
    private static final double MAX_AIM = Math.toRadians(12.0);

    public BowlingBallItem(Properties props) {
        super(props);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;      // held until released, like a bow
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (level.isClientSide() || !(entity instanceof Player player)) {
            return;
        }
        int held = getUseDuration(stack, entity) - remaining;
        double power = Math.min(1.0, held / (double) FULL_CHARGE);
        if (power < 0.1) {
            return;                       // a tap is not a throw
        }

        BowlingLaneBlockEntity lane = findLane(level, player);
        if (lane == null) {
            player.displayClientMessage(
                    Component.literal("Stand at a lane to bowl."), true);
            return;
        }

        // Direction: down the lane, ROTATED by where the player is looking.
        //
        // This used to be locked to the lane axis, on the theory that you aim
        // by standing in the right place. That was wrong, and it disabled the
        // technique the whole game is built on: a hooking bowler stands left,
        // rolls the ball OUT to the right, and lets the curve bring it back
        // into the pocket. With the launch pinned straight down the lane the
        // hook can only walk the ball off-line - it can never return it.
        //
        // Real hook deflection is only 0.37-0.68 m across an 18.3 m lane, so
        // the curve was never going to cross the lane on its own. The angle has
        // to come from the throw; the hook only has to beat it and turn the
        // ball back. That is why the aim range below is small: about 12 deg is
        // already far wider than any real delivery, and the useful band is the
        // few degrees where the hook can still overcome it.
        Direction facing = lane.getBlockState().getValue(BowlingLaneBlock.FACING);
        Vec3 axis = new Vec3(facing.getStepX(), 0, facing.getStepZ());

        // Release variation, same as the simulator uses. A human hand does
        // not repeat a delivery exactly, and without this a found line
        // strikes every single time.
        double aim = aimOffset(player, facing) + BowlingBall.jitterAim(level.random);
        Vec3 dir = axis.yRot((float) -aim).normalize();

        Vec3 start = player.position().add(dir.scale(RELEASE_AHEAD)).add(0, 0.15, 0);
        Vec3 motion = dir.scale(BowlingBall.MAX_POWER * power);

        // Snapshot the standing pins BEFORE the ball exists. Scoring is the
        // difference across the roll, so the "before" has to be taken here and
        // not when the ball comes to rest.
        lane.beginRoll();

        float spin = SpinState.get(player);
        BowlingBall ball = new BowlingBall(level, player, lane.getBlockPos(), start, motion, spin);
        level.addFreshEntity(ball);

        level.playSound(null, player.blockPosition(), SoundEvents.STONE_PLACE,
                SoundSource.PLAYERS, 0.7f, 0.6f);

        stack.shrink(1);        // the ball is now on the lane; it comes back after
    }

    /**
     * How far off the lane axis the player is aiming, in radians.
     *
     * <p>Taken from look direction and clamped hard. Unclamped, looking 90 deg
     * sideways would fire the ball into the gutter wall and looking backwards
     * would throw it at your own feet; clamped, glancing across the lane is a
     * deliberate few degrees of angle, which is what a delivery actually is.
     *
     * <p>Positive is clockwise from the lane axis - i.e. to the bowler's right.
     */
    private static double aimOffset(Player player, Direction facing) {
        Vec3 look = player.getLookAngle();
        if (look.horizontalDistanceSqr() < 1.0E-4) {
            return 0.0;                       // staring at the floor or sky
        }
        double laneYaw = Math.atan2(-facing.getStepX(), facing.getStepZ());
        double lookYaw = Math.atan2(-look.x, look.z);

        double delta = lookYaw - laneYaw;
        while (delta > Math.PI) {
            delta -= 2 * Math.PI;
        }
        while (delta < -Math.PI) {
            delta += 2 * Math.PI;
        }
        return Math.max(-MAX_AIM, Math.min(MAX_AIM, delta));
    }

    /** The nearest lane within a couple of blocks of the player. */
    private static BowlingLaneBlockEntity findLane(Level level, Player player) {
        BlockPos origin = player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-2, -2, -2),
                                                 origin.offset(2, 1, 2))) {
            if (level.getBlockEntity(p) instanceof BowlingLaneBlockEntity be) {
                return be;
            }
        }
        return null;
    }
}
