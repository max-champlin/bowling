package bowl.entity;

import bowl.Bowling;
import bowl.block.BowlingLaneBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The ball.
 *
 * <p>Rolls on a FIXED lane resistance, not vanilla block friction. Reading
 * friction from whatever block happened to be underneath was clever and wrong:
 * it made every lane play differently, so a throw you had dialled in stopped
 * working the moment you built the next lane out of something else. A bowling
 * lane is a calibrated surface in real life and has to be one here, or there is
 * no skill to learn - only a surface to memorise.
 *
 * <p>So the surface is constant everywhere ({@link #LANE_FRICTION}) and the one
 * deliberate exception is the gutter, which is supposed to punish you. That
 * also means a lane built out of anything at all still plays correctly.
 */
public class BowlingBall extends Entity {

    private static final EntityDataAccessor<Float> SPIN =
            SynchedEntityData.defineId(BowlingBall.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL =
            SynchedEntityData.defineId(BowlingBall.class, EntityDataSerializers.FLOAT);

    // ---- tuning. All invented, all expected to move once it is playable. ----

    /** Rotations per tick at full spin. 0.15 per 0.1s = 1.5 rev/s = 0.075/tick. */
    public static final double SPIN_RATE = 0.075;

    /**
     * Sideways acceleration per tick at full spin, while the ball is gripping.
     *
     * <p>Separate from {@link #SPIN_RATE} on purpose: one is what you SEE, the
     * other is what you FEEL. Tying them together would mean every visual tweak
     * silently changed the handling.
     *
     * <p>Sized against the shot rather than against realism, because our lane is
     * not a real one - at {@link bowl.game.Rack}'s scale of 12" to 0.45 blocks it
     * is two thirds the length of a real lane with nearly double the board width.
     *
     * <p>With the oil pattern below, this gives 0.72 blocks of draw and a -7.5
     * degree entry at full spin, and the dial is close to linear: 25% spin turns
     * about 1.9 degrees, 50% about 3.8, which is the measured optimum of 3.3-3.6.
     * So the useful part of the dial is the middle, and full spin overcooks it -
     * which is how it should read.
     *
     * <p>Verified line: stand 0.40 left, aim 4 degrees right, full spin, and the
     * ball arrives in the 1-3 pocket at -3.5 degrees.
     */
    public static double HOOK_FORCE = 0.003;

    /**
     * Where the oil ends and the ball starts gripping, as a fraction of lane.
     *
     * <p>Taken from a real oil pattern: 40 feet of oil on a 60 foot lane, so
     * the ball SKIDS for the first two thirds and only bites in the last third.
     * That late bite is the whole character of a hook - it is why a real ball
     * looks like it goes straight and then turns hard, rather than drawing a
     * gentle arc the whole way.
     *
     * <p>This was 0.30 with the hook ending at 0.72, i.e. the turn happened in
     * the MIDDLE and the ball straightened before the pins. Same entry angle,
     * but nearly twice the sideways drift and none of the late snap - which is
     * the "hook is better but still lacking" this fixes.
     */
    public static double OIL_END = 0.67;

    /**
     * Sideways grip while still on the oil, as a fraction of dry grip.
     *
     * <p>Real coefficients are about 0.04 in oil against 0.20 dry, so a fifth.
     * Not zero: an oiled lane still turns the ball a little, which is what
     * keeps the early part of the path from being a dead straight line.
     */
    public static double OIL_GRIP = 0.20;

    /**
     * Fraction of the along-normal component the ball gives up per pin.
     *
     * <p>Derived, not chosen: with ball 7.26 kg, pin 1.53 kg and restitution
     * 0.5, the ball keeps {@code (m_b − e·m_p)/(m_b+m_p)} = 0.74 of its
     * approach speed, so it loses 0.26. A real ball drives THROUGH the rack;
     * the 0.6 that used to be here killed it on the head pin, and the sluggish
     * deck that produced had to be propped up by letting pins clobber each
     * other from impossible distances.
     *
     * <p>It still only removes the component pointing INTO the pin, which is
     * what makes the straight ball the weak one without any special case: a
     * flush hit is entirely along the normal and pays the whole toll, while a
     * pocket hit is mostly sideways to it and keeps driving.
     */
    public static double BALL_DEFLECT = 0.26;

    /**
     * Release variation, in degrees of aim (one standard deviation).
     *
     * <p>The physics here is entirely deterministic: identical release,
     * identical result, forever. Measured over 61 lines, a found strike line
     * struck EVERY time and everything either side failed every time. That is
     * not a game, it is a lookup table - and it is why "straight on is repeated
     * strikes" was true.
     *
     * <p>Real bowlers do not repeat a delivery exactly. A professional hits
     * their mark perhaps 60-70% of the time, which is the whole reason a strike
     * feels earned. The variation belongs in the BOWLER, not in the rack: the
     * pins stay honest and the hand is human.
     *
     * <p>0.30 against a strike window measured at roughly half a degree, so a
     * good line strikes often, a great line strikes more, and a sloppy one
     * rarely - a strike ZONE rather than a strike line.
     */
    public static double JITTER_AIM = 0.30;

    /** Release variation in spin, same reasoning. */
    public static double JITTER_SPIN = 0.08;

    /**
     * Perturb an aim angle at release.
     *
     * <p>Shared by the hand throw and the simulator on purpose. A simulator
     * that skipped the jitter would be measuring a game nobody plays.
     */
    public static double jitterAim(net.minecraft.util.RandomSource random) {
        return random.nextGaussian() * Math.toRadians(JITTER_AIM);
    }

    public static float jitterSpin(net.minecraft.util.RandomSource random) {
        return (float) (random.nextGaussian() * JITTER_SPIN);
    }

    /**
     * Centre-to-centre distance at which the ball touches a pin.
     *
     * <p>Real ball 8.5" and pin 4.77" meet at 6.63" centre to centre, which at
     * our scale (0.45 = 12") is 0.249. Note this is LARGER than the 0.225 gap
     * between two pins in a row - a real ball cannot pass between pins without
     * touching them, and neither can this one. The old 0.35 let it hit pins it
     * should have missed entirely.
     */
    public static double CONTACT = 0.25;

    /**
     * Throw speed at full charge, blocks per tick.
     *
     * <p>Pinned to the real thing via the rack's own scale. {@link bowl.game.Rack}
     * puts 12 inches at 0.45 blocks, so a block is 2.22 feet and this works out
     * at 19.7 mph - inside the 17-21 mph a real bowler releases at.
     *
     * <p>It was 1.4, which on the same scale is 42 mph. That is not a tuning
     * choice, it is a ball fired out of a cannon, and it is why the hook never
     * read as a curve: the whole roll was over in 19 ticks. At this speed the
     * ball takes 30 ticks - a second and a half - so there is time to watch it
     * bend.
     */
    public static double MAX_POWER = 0.65;

    /**
     * The lane surface. Constant, everywhere, whatever the lane is built from.
     *
     * <p>A real ball loses about a tenth of its speed over a full lane. This
     * loses 14% over the 18 blocks, arriving at 16.9 mph from a 19.7 mph
     * release, which is as close as a per-tick multiplier gets.
     *
     * <p>THIS IS THE FIX for the ball that went straight and then turned left.
     * The old 0.96 bled 54% of the speed away, and because a constant sideways
     * force turns a ball at a rate of force-over-speed, halving the speed
     * doubled the turn rate. The path was not an arc, it was a spiral tightening
     * all the way to the pins. Nothing was ever wrong with the hook code.
     *
     * <p>What this costs: the old friction doubled as a skill gate, killing any
     * throw under about 55% power before it reached the pins. That gate is gone
     * - a weak throw now reaches, but spends so long on the lane that the hook
     * walks it into the gutter. Real bowling punishes a soft ball the same way,
     * by losing the line rather than by stopping short.
     */
    public static double LANE_FRICTION = 0.995;

    /** The gutter. Kills a ball in a little over four blocks. */
    public static final double GUTTER_FRICTION = 0.88;

    /** Air. Only matters for the moment between release and landing. */
    private static final double AIR_FRICTION = 0.99;

    /**
     * Below this the ball is considered stopped and the roll is over.
     *
     * <p>Sits between two hard limits. It has to be BELOW the weakest legal
     * throw - the item refuses anything under 10% charge, which is 0.065 - or
     * that throw would be declared over on its first tick. And it wants to be
     * as close as possible to {@link BowlingPin#TOPPLE_SPEED} (0.08), because
     * under that the ball cannot knock anything over however long it is left,
     * so waiting on it only makes the player watch a creeping ball.
     *
     * <p>0.02 was fine on a 0.96 lane where balls actually came to rest. On
     * 0.995 a ball never gets there - it runs off the end long before - so most
     * rolls now end on the run-off check instead, and this only catches gutter
     * balls and throws too soft to make the deck.
     */
    private static final double STOP_SPEED = 0.05;

    /** Give up on a roll after this long, so a stuck ball cannot hang a game. */
    private static final int MAX_LIFE = 400;

    private UUID thrower;
    private BlockPos lane;
    private int life = 0;
    private boolean finished = false;

    /**
     * Side rotation still left to spend, server side only.
     *
     * <p>Kept off the synched SPIN because that one is the throw the player
     * asked for and the renderer's wobble reads from it; this is what is left of
     * it after the lane has taken its cut. Syncing a value that changes every
     * single tick would put a packet on the wire per tick for no visible gain.
     *
     * <p>NaN until first use so both construction paths - thrown fresh, or
     * loaded back off disk - pick up the right starting value without the
     * constructor and the NBT reader having to agree about ordering.
     */
    private double spinLeft = Double.NaN;

    /** Horizontal distance rolled since release, for the skid/hook/roll phase. */
    private double travelled = 0.0;

    /**
     * Sideways-friction multiplier at this fraction of the way down the lane.
     *
     * <p>Ramps in across the oil, holds through the mid-lane where the ball is
     * the dry back end. Distance-based rather than time-based on purpose: a soft
     * throw spends longer on the lane but hooks over the same STRETCH of it,
     * which is how a real lane behaves - the oil is a property of the lane, not
     * of how hard you threw.
     */
    private static double gripAt(double progress) {
        return progress < OIL_END ? OIL_GRIP : 1.0;
    }

    /**
     * Set once the ball has actually been over the lane.
     *
     * <p>The ball is released from wherever the player is standing, which may
     * be behind the foul line and off the surface entirely. Without this the
     * run-off check below would fire on tick one and end every roll instantly.
     */
    private boolean reachedLane = false;

    public BowlingBall(EntityType<BowlingBall> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public BowlingBall(Level level, Player thrower, BlockPos lane, Vec3 pos,
                       Vec3 motion, float spin) {
        this(Bowling.BALL.get(), level);
        this.thrower = thrower.getUUID();
        this.lane = lane;
        setPos(pos.x, pos.y, pos.z);
        setDeltaMovement(motion);
        this.entityData.set(SPIN, spin);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SPIN, 0.0f);
        builder.define(ROLL, 0.0f);
    }

    public float spin() {
        return entityData.get(SPIN);
    }

    /** Accumulated rotation, for the renderer. */
    public float roll() {
        return entityData.get(ROLL);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            spinVisual();
            return;
        }
        if (finished) {
            return;
        }
        if (++life > MAX_LIFE) {
            endRoll();
            return;
        }

        Vec3 m = getDeltaMovement();

        // Gravity, but the ball lives on the floor so this mostly just keeps
        // it pinned to the lane rather than doing real work.
        if (!onGround()) {
            m = m.add(0, -0.04, 0);
        }

        // Spin pushes sideways, perpendicular to travel. Because it is applied
        // per TICK rather than per block travelled, a slow ball hooks harder
        // than a fast one - which is how real bowling behaves, and falls out
        // of the model rather than being special-cased.
        // Applied whether or not the ball is technically grounded. Gating this
        // on onGround() meant a ball skipping over a seam stopped curving for
        // those ticks, which is invisible to the player and reads as "spin does
        // nothing" rather than as a physics detail.
        if (Double.isNaN(spinLeft)) {
            spinLeft = spin();
        }
        double grip = gripAt(travelled / BowlingLaneBlockEntity.LANE_LENGTH);
        if (spinLeft != 0 && grip > 0) {
            Vec3 flat = new Vec3(m.x, 0, m.z);
            if (flat.lengthSqr() > 1.0E-6) {
                Vec3 side = new Vec3(-flat.z, 0, flat.x).normalize();
                m = m.add(side.scale(spinLeft * HOOK_FORCE * grip));
            }
        }

        boolean gutter = overGutter();
        double friction = !onGround() ? AIR_FRICTION
                : gutter ? GUTTER_FRICTION : LANE_FRICTION;
        m = new Vec3(m.x * friction, m.y, m.z * friction);

        setDeltaMovement(m);
        Vec3 from = position();
        move(MoverType.SELF, getDeltaMovement());
        travelled += Math.sqrt(Math.pow(getX() - from.x, 2)
                             + Math.pow(getZ() - from.z, 2));
        spinVisual();

        // A ball in the gutter is out of play and must not clip a pin on its
        // way past - that is the entire point of a gutter.
        if (!gutter) {
            hitPins(from, position());
        }

        // Run-off. On a realistic lane surface the ball does NOT come to rest
        // between the foul line and the pit - it leaves the end still rolling,
        // exactly as a real one does. Without this the roll would sit there
        // until MAX_LIFE and hold up scoring for twenty seconds.
        if (onLaneSurface()) {
            reachedLane = true;
        } else if (reachedLane) {
            endRoll();
            return;
        }

        Vec3 now = getDeltaMovement();
        if (Math.sqrt(now.x * now.x + now.z * now.z) < STOP_SPEED) {
            endRoll();
        }
    }

    /**
     * True while the ball is still over lane, board or gutter.
     *
     * <p>Checked by block rather than by measuring distance down the lane so it
     * does not need to know which way the lane faces, and so a lane the player
     * has extended or cut short still ends the roll in the right place.
     */
    private boolean onLaneSurface() {
        BlockPos below = BlockPos.containing(getX(), getY() - 0.08, getZ());
        BlockState ground = level().getBlockState(below);
        return ground.is(Bowling.LANE.get())
                || ground.is(Bowling.LANE_BOARD.get())
                || ground.is(Bowling.GUTTER.get());
    }

    /** One pin met by the sweep: how far along it happened, and where. */
    private record Contact(double t, BowlingPin pin, Vec3 at) {
    }

    /**
     * How far along this tick's path the ball comes closest to a point, 0..1.
     *
     * <p>Flattened to the XZ plane deliberately: pins and ball both sit on the
     * lane, and including Y would let a pin standing on a board count as "far"
     * from a ball rolling past it at the same height.
     */
    private static double segmentT(Vec3 point, Vec3 from, Vec3 to) {
        double px = point.x - from.x;
        double pz = point.z - from.z;
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1.0E-9) {
            return 0.0;                                // did not move this tick
        }
        return Math.max(0.0, Math.min(1.0, (px * dx + pz * dz) / lenSq));
    }

    /** True when the block underneath is a gutter channel. */
    private boolean overGutter() {
        BlockPos below = BlockPos.containing(getX(), getY() - 0.08, getZ());
        BlockState ground = level().getBlockState(below);
        return ground.is(Bowling.GUTTER.get());
    }

    private void spinVisual() {
        double speed = getDeltaMovement().horizontalDistance();
        // Rolling rotation tracks speed; spin adds the visible wobble on top.
        float delta = (float) (speed * 0.5 + Math.abs(spin()) * SPIN_RATE);
        entityData.set(ROLL, (roll() + delta) % 1.0f);
    }

    /**
     * Topple anything in reach, and get pushed around by it in return.
     *
     * <p>The ball DEFLECTS off each pin rather than just slowing down. That one
     * change is what stops a straight ball being a guaranteed strike: hit the
     * head pin flush and the ball loses its forward drive and carries on down
     * the middle, so the back corners never get touched and you leave a split.
     * Come in off-line - which is what hook is for - and the ball stays in the
     * pocket, driving pins sideways into each other. Earned, rather than free.
     */
    private void hitPins(Vec3 from, Vec3 to) {
        // SWEPT along the path travelled this tick rather than a snapshot of
        // where the ball ended up. At full power the ball covers 1.4 blocks per
        // tick and a pin is 0.22 wide, so a per-tick overlap test lets a fast
        // ball skip clean through the head pin without ever sampling a frame
        // inside it - and it fails worst on precisely the hard, straight throws
        // that ought to hit hardest.
        AABB sweep = new AABB(from, to).inflate(CONTACT + 0.5);

        // Work out WHERE along the sweep each pin is actually met, and deal
        // with them in that order. Both matter, and neither did before:
        //
        //  - the contact POINT is where the ball was when it touched the pin,
        //    not where it finished the tick. Using the end position meant the
        //    pin was pushed along one normal while the ball deflected off a
        //    different one, up to a block and a half apart. On a straight ball
        //    that error is symmetric and invisible; on a hook it is neither,
        //    which is exactly the sloppiness that shows up on spin.
        //
        //  - the ORDER was whatever the entity list happened to return, so
        //    which pin the ball "hit first" was arbitrary. Sorting by distance
        //    along the path makes a multi-pin tick resolve the way it happened.
        List<Contact> contacts = new ArrayList<>();
        for (BowlingPin pin : level().getEntitiesOfClass(BowlingPin.class, sweep)) {
            if (!pin.isStanding()) {
                continue;
            }
            double t = segmentT(pin.position(), from, to);
            Vec3 at = from.add(to.subtract(from).scale(t));
            double dx = pin.getX() - at.x;
            double dz = pin.getZ() - at.z;
            if (dx * dx + dz * dz > CONTACT * CONTACT) {
                continue;
            }
            contacts.add(new Contact(t, pin, at));
        }
        contacts.sort(Comparator.comparingDouble(Contact::t));

        for (Contact c : contacts) {
            Vec3 before = getDeltaMovement();
            if (!c.pin().topple(before, c.at(), true)) {   // ball fells at any angle
                continue;
            }
            Vec3 normal = new Vec3(c.pin().getX() - c.at().x, 0,
                                   c.pin().getZ() - c.at().z);
            if (normal.lengthSqr() < 1.0E-6) {
                setDeltaMovement(before.scale(0.94));
                continue;
            }
            normal = normal.normalize();
            double along = new Vec3(before.x, 0, before.z).dot(normal);
            // Lose the part of the motion that went INTO the pin; whatever was
            // sideways to it survives, and that is what turns the ball.
            setDeltaMovement(before.subtract(normal.scale(along * BALL_DEFLECT)));
        }
    }

    private void endRoll() {
        if (finished) {
            return;
        }
        finished = true;
        returnToThrower();
        if (lane != null && level().getBlockEntity(lane)
                instanceof BowlingLaneBlockEntity be) {
            be.ballStopped();        // starts the settle timer, then scores
        }
        discard();
    }

    /**
     * Straight back to the thrower's hand.
     *
     * <p>No return mechanism to build, and more usefully no way to lose the
     * ball in a gutter or have it despawn mid-game. If the thrower has gone,
     * the ball simply vanishes rather than dropping on the lane for someone to
     * trip over.
     */
    private void returnToThrower() {
        if (thrower == null || !(level() instanceof net.minecraft.server.level.ServerLevel s)) {
            return;
        }
        Player p = s.getPlayerByUUID(thrower);
        if (p == null) {
            return;
        }
        ItemStack ball = new ItemStack(Bowling.BALL_ITEM.get());
        if (!p.getInventory().add(ball)) {
            p.drop(ball, false);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Thrower")) {
            thrower = tag.getUUID("Thrower");
        }
        if (tag.contains("Lane")) {
            lane = BlockPos.of(tag.getLong("Lane"));
        }
        entityData.set(SPIN, tag.getFloat("Spin"));
        travelled = tag.getDouble("Travelled");     // 0 if absent, which is right
        // Absent on a ball saved before spin decay existed - leaving it NaN
        // makes the next tick seed it from Spin, which is the right answer for
        // an old ball anyway.
        if (tag.contains("SpinLeft")) {
            spinLeft = tag.getDouble("SpinLeft");
        }
        reachedLane = tag.getBoolean("OnLane");
        life = tag.getInt("Life");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (thrower != null) {
            tag.putUUID("Thrower", thrower);
        }
        if (lane != null) {
            tag.putLong("Lane", lane.asLong());
        }
        tag.putFloat("Spin", spin());
        if (!Double.isNaN(spinLeft)) {
            tag.putDouble("SpinLeft", spinLeft);
        }
        // Which phase the ball is in is a function of how far it has ROLLED,
        // so losing this to a chunk reload would drop a mid-lane ball back into
        // the skid phase and hand it a second hook it never earned.
        tag.putDouble("Travelled", travelled);
        tag.putBoolean("OnLane", reachedLane);
        tag.putInt("Life", life);
    }
}
