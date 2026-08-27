package bowl.entity;

import bowl.Bowling;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One pin.
 *
 * <p>An entity, not a block, for one reason: pins have to knock each other
 * over. A block can be replaced when hit but cannot carry momentum into its
 * neighbour, and pin-to-pin transfer is what separates a strike from a lucky
 * head-on hit.
 *
 * <p>Deliberately NOT a LivingEntity - pins have no health, no AI and no
 * pathfinding, and inheriting all that would mean fighting it. This is a bare
 * Entity with position, velocity and one flag.
 *
 * <p>Standing versus fallen is synced to clients because the model rotates on
 * it, and the lane reads it to count the roll. The count is taken from the
 * SERVER's view; a client that mispredicts a topple corrects on the next sync
 * rather than scoring a phantom pin.
 */
public class BowlingPin extends Entity {

    private static final EntityDataAccessor<Boolean> STANDING =
            SynchedEntityData.defineId(BowlingPin.class, EntityDataSerializers.BOOLEAN);

    /**
     * Speed below which a hit will not topple a pin.
     *
     * <p>THE tuning number of the whole mod. Too low and pins fall from a
     * glancing nudge so every throw is a strike; too high and they shrug off
     * real hits and the game reads as broken.
     *
     * <p>Lowered from 0.08 when the ball was made realistic. This is an ABSOLUTE
     * speed, but the ball that has to beat it went from 1.4 blocks/tick to 0.65
     * at release - so the same number quietly became a much higher bar, and
     * everything downstream of contact got quieter. 0.04 restores the balance
     * the 0.08 originally struck against a faster ball.
     */
    public static double TOPPLE_SPEED = 0.04;

    /**
     * Collision constants, derived rather than invented.
     *
     * <p>Real bowling: ball 7.26 kg, pin 1.53 kg, coefficient of restitution
     * about 0.5 for ball-on-pin and 0.6 for pin-on-pin. For a collision along
     * the line of centres those give
     *
     * <pre>
     *   struck speed = (1+e)·m_a/(m_a+m_b) × approach
     *   striker keeps = (m_a − e·m_b)/(m_a+m_b) × approach
     * </pre>
     *
     * <p>Pins leave FASTER than the ball arrives - that is what real pin action
     * is, and why a strike is mostly pins hitting pins rather than the ball
     * hitting ten things. Earlier numbers here (0.6 transfer, ball losing 60%)
     * had it backwards on both counts: sluggish pins and a ball that died in
     * the rack.
     *
     * <p>1.41 corresponds to restitution 0.71, the top of the defensible range
     * rather than the middle. Chosen because the realistic ball is SLOW - 0.56
     * blocks/tick at the pins against the 1.4 it used to carry - so contact was
     * reading as soft. Simulating the rack showed the coefficient barely moves
     * pin COUNTS (7.7-7.8 across 1.24 to 1.50) while it does change how hard
     * pins are thrown, 0.69 to 0.79 blocks/tick. Free punch, no balance cost.
     */
    public static double BALL_TO_PIN = 1.41;

    /**
     * Equal masses, e=0.7: the struck pin takes (1+e)/2 of the approach.
     *
     * <p>Restitution anywhere in 0.5-0.8 is defensible for pin on pin, so this
     * is the one place there was room to tune, and it was picked by measuring
     * the real deck in game rather than by feel.
     *
     * <p>0.95 implies e=0.9, which is at the very top of that range. It earns
     * it: measured against 0.85 at the pocket, carry rose and the leaves became
     * single corner pins rather than pairs. Pushing on to 1.05 made things
     * WORSE - pins left so hard they flew past their neighbours instead of
     * ploughing into them, the same tunnelling failure that {@link
     * #FALLEN_CONTACT} exists to fix, arriving from the other end of the dial.
     *
     * <p>Final measured behaviour, 40 jittered throws at the pocket:
     * <b>22% strikes, mean 8.45 pins</b>, mode of 9, most common leaves a lone
     * 6 or 10. Club-bowler numbers.
     */
    public static double PIN_TO_PIN = 0.95;

    /**
     * ...and the striker keeps (1−e)/2 of it.
     *
     * <p>NOTE: this no longer pairs with {@link #PIN_TO_PIN}. 0.95 implies
     * e=0.9 and therefore 0.05 here, not 0.15. The pair was derived together
     * from equal masses, then only the transfer was tuned by measurement, so
     * the striker now keeps more than the physics strictly allows. Left alone
     * deliberately: the measured result is good, and changing this would move
     * the balance without a single throw to justify it. Worth testing as a pair
     * if the deck is ever revisited.
     */
    public static double PIN_RETAINED = 0.15;

    /**
     * Centre-to-centre distance at which two pins touch.
     *
     * <p>A real pin is 4.77" across against 12" spacing, so at our scale two
     * pins meet at 0.18 while their neighbours sit 0.225 apart along a row.
     * That gap is not a detail - it is the whole reason a straight ball fails.
     * Pin 1, driven dead straight back, passes 2 and 3 and MISSES them, so
     * nothing drives the outside pins into 4/6/7/10 and the corners survive.
     *
     * <p>This was previously an AABB inflated by 0.14, i.e. a reach of 0.36,
     * so pin 1 flattened its neighbours from a distance it could never actually
     * touch them - which cleared the deck on every straight throw.
     */
    public static double PIN_CONTACT = 0.18;

    /**
     * Contact reach of a pin that has already gone over.
     *
     * <p>{@link #PIN_CONTACT} is the distance at which two UPRIGHT pins touch -
     * one pin diameter, correct for a standing rack. But a struck pin is not
     * standing: it is lying down, sweeping a body 0.55 blocks long across the
     * deck. Treating it as a slim upright cylinder let it thread between its
     * neighbours through the gaps, which is legal for a pin on its feet and
     * nonsense for one on its side.
     *
     * <p>Measured: a struck pin travelled <b>1.496 blocks</b> - three whole pin
     * spacings - and knocked over nothing at all. Every delivery in a 61-line
     * sweep left at least four pins standing, because the cascade that fells
     * six of the ten in real bowling simply did not exist.
     *
     * <p>Half the fallen pin's length plus the standing pin's radius:
     * 0.275 + 0.11. That is what a pin on its side can actually reach.
     */
    public static double FALLEN_CONTACT = 0.44;

    /**
     * Floor on the push a glancing hit delivers, as a fraction of the hitter's
     * speed.
     *
     * <p>Without this, whether a pin falls depends on how SQUARE the hit was:
     * catch one on the shoulder and the along-the-normal component is nearly
     * zero, so a ball visibly ploughing through it left it standing. The angle
     * should decide which WAY a pin goes, not WHETHER it goes.
     *
     * <p>An earlier version of this note said pins are top-heavy. They are not.
     * A 15" pin carries its centre of gravity about 5.5" up - the bottom third.
     * The reason a pin goes over from almost any solid contact is that the ball
     * strikes BELOW that balance point (a ball on the lane presents its centre
     * at about 4.3"), so even a glancing hit tips it. Being bottom-weighted
     * also means most of the energy goes into sliding rather than spinning,
     * which is why real pin action stays low and scrabbly across the deck
     * instead of pinwheeling through the air.
     *
     * <p>Not yet modelled: the yaw an off-centre hit imparts. A pin spinning
     * about its vertical axis while it slides sweeps a far wider path than its
     * diameter, and that is a real part of how the deck clears.
     */
    public static double GRAZE = 0.35;

    /**
     * How much of its speed a struck pin keeps each tick, horizontally.
     *
     * <p>This was 0.98, and that one digit is why pins behaved like curling
     * stones. A pin leaves the ball at about 0.8 blocks per tick; at 0.98 it
     * coasts <b>39 blocks - 87 real feet</b> - and takes 274 ticks to stop. A
     * struck pin genuinely travels three to eight feet.
     *
     * <p>It was not only a visual problem. {@link #chain()} fires every tick a
     * fallen pin is still above {@link #TOPPLE_SPEED}, which at 0.98 lasted 113
     * ticks, so one pin stayed lethal for five and a half seconds and mowed
     * through everything in its path long after leaving the deck. And the lane
     * counts the roll 40 ticks after the ball stops, so the score was being
     * taken while pins were still travelling.
     *
     * <p>0.90 rather than 0.85: at 0.85 a struck pin stopped so quickly that the
     * cascade died before reaching the back row, which is where pin action is
     * supposed to do its work. Simulating the rack showed 0.90 lifting 9-or-
     * better results from 4 aim lines in 40 to 8, for the same average. It still
     * settles well inside the 40-tick window, so the count is taken on a still
     * deck.
     */
    public static double DRAG = 0.90;

    /**
     * Half-width of the pin deck, from the centre line to a kickback wall.
     *
     * <p>A real deck is 42 inches across, walled on both sides. Those walls are
     * not decoration: the 6 pin thrown off the 3 rebounds off the right kickback
     * and takes out the 10, and the 4 does the same on the left for the 7. That
     * is how the CORNER pins fall, and it is the difference between a nine and
     * a strike.
     *
     * <p>Without walls a pin thrown wide simply leaves and never comes back,
     * which is exactly what a 77-line sweep showed: a best of nine, every time,
     * with the last pin standing in a corner.
     *
     * <p>21 inches at our scale, where 12 inches of pin spacing is 0.45.
     */
    public static double DECK_HALF = 0.72;

    /** How much speed a pin keeps off the kickback. Padded wood, not a trampoline. */
    public static double KICKBACK = 0.85;

    /** Which pin of the rack this is, 1..10. Kept so the lane can rebuild. */
    private int pinNumber = 1;

    /**
     * The deck's centre line, so a pin can tell how far sideways it has gone.
     *
     * <p>Set when the rack is laid; a pin that never receives it (an old one
     * loaded from disk) simply has no walls rather than bouncing off nothing.
     */
    private double deckCx, deckCz, latX, latZ;
    private boolean hasDeck;

    public void setDeck(Vec3 centre, net.minecraft.core.Direction facing) {
        this.deckCx = centre.x;
        this.deckCz = centre.z;
        net.minecraft.core.Direction side = facing.getClockWise();
        this.latX = side.getStepX();
        this.latZ = side.getStepZ();
        this.hasDeck = true;
    }

    /** Exact type parameter, not {@code ? extends} - EntityType.Builder's
     *  factory is EntityFactory&lt;T&gt; and a wildcard here breaks its
     *  inference with a fairly opaque "cannot infer type-variable T". */
    public BowlingPin(EntityType<BowlingPin> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public BowlingPin(Level level, double x, double y, double z, int pinNumber) {
        this(Bowling.PIN.get(), level);
        this.setPos(x, y, z);
        this.pinNumber = pinNumber;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STANDING, true);
    }

    public boolean isStanding() {
        return this.entityData.get(STANDING);
    }

    public void setStanding(boolean standing) {
        this.entityData.set(STANDING, standing);
    }

    public int pinNumber() {
        return pinNumber;
    }

    /**
     * Knock this pin down, passing on part of the impact.
     *
     * @param motion the motion of whatever hit it
     * @return true if this actually toppled it (it was standing and the hit
     *         was hard enough), so the caller can chain to neighbours
     */
    /**
     * @param allowGraze true for the BALL, false for pin-on-pin.
     *
     * <p>The ball gets the benefit of the doubt because a bowling ball fells
     * anything it touches, at any angle. A pin does not - it only takes its
     * neighbour down if it genuinely drives INTO it. Giving pins the same
     * generosity made every straight throw a strike: the ball felled the centre
     * line and the graze floor let that cascade sideways into the back corners,
     * which are precisely the pins a flush hit is supposed to leave standing.
     */
    public boolean topple(Vec3 motion, Vec3 fromPos, boolean allowGraze) {
        if (!isStanding()) {
            return false;
        }

        // Push along the LINE OF CENTRES, not along the direction of travel.
        // This is the difference between a bowling game and a lawnmower: a pin
        // clipped on its left edge should fly off to the right, not continue
        // straight ahead. It is also why a dead-centre hit is a bad line in
        // real bowling - the pins scatter sideways away from the ball instead
        // of being driven into their neighbours.
        Vec3 normal = new Vec3(getX() - fromPos.x, 0, getZ() - fromPos.z);
        if (normal.lengthSqr() < 1.0E-6) {
            normal = new Vec3(motion.x, 0, motion.z);      // exactly stacked
        }
        if (normal.lengthSqr() < 1.0E-6) {
            return false;
        }
        normal = normal.normalize();

        Vec3 flat = new Vec3(motion.x, 0, motion.z);
        double speed = flat.length();
        double along = flat.dot(normal);

        // Threshold on the hitter's SPEED, not on how square the hit was - a
        // fast ball tips a pin however it catches it. Moving away from the pin
        // still does nothing, which is what keeps a ball that has already gone
        // past from dragging pins along behind it.
        if (speed < TOPPLE_SPEED || along <= 0) {
            return false;
        }

        // Pin-on-pin has to earn it: without the graze floor, a shoulder-clip
        // simply is not enough along the line of centres to tip anything.
        if (!allowGraze && along < TOPPLE_SPEED) {
            return false;
        }

        setStanding(false);
        // The ball gets a graze floor because 7 kg fells a pin however it
        // catches it; pin-on-pin gets only what it actually drove down the line
        // of centres. Coefficients come from the real mass ratio, so pins leave
        // faster than the ball arrived and the deck clears by pin action.
        double push = allowGraze ? Math.max(along, speed * GRAZE) : along;
        double coefficient = allowGraze ? BALL_TO_PIN : PIN_TO_PIN;
        this.setDeltaMovement(normal.scale(push * coefficient));
        this.hasImpulse = true;
        return true;
    }

    @Override
    public void tick() {
        super.tick();

        Vec3 m = getDeltaMovement();
        if (!onGround()) {
            m = m.add(0, -0.04, 0);
        }
        // DRAG is horizontal only. Putting it on Y as well would have pins
        // drifting down like feathers; that axis keeps the vanilla air value.
        setDeltaMovement(m.multiply(DRAG, 0.98, DRAG));
        move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
        kickback();

        if (!level().isClientSide() && !isStanding()) {
            chain();
        }

        if (getDeltaMovement().lengthSqr() < 1.0E-5) {
            setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * A moving pin knocks over its neighbours.
     *
     * <p>This is the single mechanic that decides whether the game feels like
     * bowling. Without it only pins the ball physically touches ever fall, and
     * a perfect pocket hit leaves seven standing - which reads as broken
     * rather than difficult. With it too generous, every ball is a strike and
     * there is no game.
     *
     * <p>Only FALLEN pins chain, and only while they still carry motion, so a
     * pin does not shove its neighbour simply by standing next to it. The
     * transferred motion is scaled down each hop, which is what stops one hit
     * cascading through the whole rack for free.
     */
    /**
     * Bounce a pin off the side wall of the deck.
     *
     * <p>Only reflects a pin that is still heading OUTWARD - otherwise one
     * already on its way back in gets flipped again and rattles against the wall
     * forever. The pin is also pushed back inside the boundary, so it cannot sit
     * past the wall re-triggering every tick.
     */
    private void kickback() {
        if (!hasDeck || level().isClientSide()) {
            return;
        }
        double lateral = (getX() - deckCx) * latX + (getZ() - deckCz) * latZ;
        double over = Math.abs(lateral) - DECK_HALF;
        if (over <= 0) {
            return;
        }
        Vec3 m = getDeltaMovement();
        double vLat = m.x * latX + m.z * latZ;
        if (Math.signum(vLat) != Math.signum(lateral)) {
            return;                       // already coming back in
        }
        // Reverse the outward component and keep the rest of the line.
        double delta = -vLat * (1.0 + KICKBACK);
        setDeltaMovement(m.add(latX * delta, 0, latZ * delta));
        double push = Math.signum(lateral) * over;
        setPos(getX() - latX * push, getY(), getZ() - latZ * push);
    }

    private void chain() {
        Vec3 m = getDeltaMovement();
        if (m.length() < TOPPLE_SPEED) {
            return;
        }
        // SWEPT contact, not a snapshot of where the pin happens to be sitting
        // at the end of a tick.
        //
        // A struck pin leaves at about 0.75 blocks per tick and PIN_CONTACT is
        // 0.18 - so the pin covers four times the whole contact diameter in a
        // single step and jumps clean over its neighbour without the test ever
        // seeing them touch. Pins were tunnelling through each other, so only
        // the pins the BALL hit directly ever fell and the cascade never
        // happened. Measured over 61 deliveries: no line anywhere took more
        // than six pins, because the other four were never actually contacted.
        //
        // Testing the closest approach of this tick's path against the other
        // pin is the same fix the ball already uses, for the same reason.
        // This pin is on its side - chain() only runs on fallen pins - so it
        // reaches as far as a body lying down, not as far as one standing up.
        final double contact = FALLEN_CONTACT;

        Vec3 from = new Vec3(xOld, getY(), zOld);
        Vec3 to = position();
        Vec3 step = to.subtract(from);
        double stepLenSq = step.x * step.x + step.z * step.z;
        double reach = contact + Math.sqrt(stepLenSq);

        for (BowlingPin other : level().getEntitiesOfClass(
                BowlingPin.class, getBoundingBox().inflate(reach + 0.3))) {
            if (other == this || !other.isStanding()) {
                continue;
            }
            // Closest approach of the segment from->to to the other pin's
            // centre, clamped to the segment so a pin that stopped short does
            // not reach forward and a pin that overshot still registers.
            double ox = other.getX() - from.x;
            double oz = other.getZ() - from.z;
            double t = stepLenSq < 1.0E-9 ? 0.0
                    : Math.max(0.0, Math.min(1.0, (ox * step.x + oz * step.z) / stepLenSq));
            double dx = other.getX() - (from.x + step.x * t);
            double dz = other.getZ() - (from.z + step.z * t);
            if (dx * dx + dz * dz > contact * contact) {
                continue;                   // close, but genuinely not touching
            }
            // topple() applies its own coefficient; pass the raw motion.
            // No graze allowance between pins - see topple().
            if (other.topple(m, position(), false)) {
                // Give up what was transferred rather than scaling the whole
                // vector: a pin that clips another off-centre should keep most
                // of its own line and carry on, not stop dead.
                Vec3 normal = new Vec3(dx, 0, dz).normalize();
                double along = new Vec3(m.x, 0, m.z).dot(normal);
                setDeltaMovement(getDeltaMovement()
                        .subtract(normal.scale(along * (1.0 - PIN_RETAINED))));
                m = getDeltaMovement();
            }
        }
    }

    /** Pins are scenery for collision purposes - the ball pushes through. */
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        pinNumber = tag.getInt("Pin");
        setStanding(tag.getBoolean("Standing"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Pin", pinNumber);
        tag.putBoolean("Standing", isStanding());
    }
}
