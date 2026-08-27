package bowl.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Ten-pin scoring for one player.
 *
 * <p>Deliberately has no idea Minecraft exists. It takes a sequence of "pins
 * knocked down by this roll" and produces frame totals, so it can be unit
 * reasoned about and got right BEFORE any ball physics exist - which is the
 * whole point of building it first. If the physics later turns out to feel
 * wrong, the scoring is still correct and unaffected.
 *
 * <p>The rules people get wrong, all handled here:
 * <ul>
 *   <li>A strike scores 10 plus the next TWO rolls, which may span two later
 *       frames (three strikes in a row = 30).</li>
 *   <li>A spare scores 10 plus the next ONE roll.</li>
 *   <li>The tenth frame allows a third roll if the first two make a strike or
 *       a spare, and those bonus rolls are NOT themselves bonus-scored - they
 *       only count once. Getting this wrong is the classic bug.</li>
 *   <li>Frames that do not yet have their bonus rolls are not scored at all,
 *       rather than scored low and corrected later. A running total that
 *       jumps backwards looks broken.</li>
 * </ul>
 */
public final class ScoreCard {

    public static final int FRAMES = 10;
    public static final int PINS = 10;

    /** Every roll in order, as pins felled. */
    private final List<Integer> rolls = new ArrayList<>();

    /** Record one roll. Returns false if the game is already over. */
    public boolean roll(int pins) {
        if (pins < 0 || pins > PINS) {
            throw new IllegalArgumentException("pins out of range: " + pins);
        }
        if (isComplete()) {
            return false;
        }
        rolls.add(pins);
        return true;
    }

    public List<Integer> rolls() {
        return List.copyOf(rolls);
    }


    /**
     * Running totals, one per frame, only for frames that can be finalised.
     *
     * <p>A frame awaiting its bonus rolls is omitted rather than guessed, so
     * the HUD shows a blank box until the score is actually known - the way a
     * paper scoresheet works.
     */
    public List<Integer> frameTotals() {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        int running = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            if (i >= rolls.size()) {
                break;
            }
            int first = rolls.get(i);

            if (first == PINS) {                       // strike
                if (i + 2 >= rolls.size()) {
                    break;                             // bonus not thrown yet
                }
                running += PINS + rolls.get(i + 1) + rolls.get(i + 2);
                i += 1;
            } else {
                if (i + 1 >= rolls.size()) {
                    break;                             // frame incomplete
                }
                int second = rolls.get(i + 1);
                if (first + second == PINS) {          // spare
                    if (i + 2 >= rolls.size()) {
                        break;
                    }
                    running += PINS + rolls.get(i + 2);
                } else {
                    running += first + second;
                }
                i += 2;
            }
            out.add(running);
        }
        return out;
    }

    /** Total so far, counting only finalised frames. */
    public int total() {
        List<Integer> t = frameTotals();
        return t.isEmpty() ? 0 : t.get(t.size() - 1);
    }

    /**
     * Which frame the player is currently throwing in, 1..10.
     *
     * <p>Must not advance past a frame that has only had its FIRST ball. An
     * earlier version stepped forward by two rolls unconditionally, so after a
     * single 7 it reported frame 2 while the player still had their second
     * ball to throw - which would have driven the HUD to highlight the wrong
     * box and, worse, told the lane to reset a rack mid-frame.
     */
    public int currentFrame() {
        int i = 0;
        for (int frame = 1; frame < FRAMES; frame++) {
            if (i >= rolls.size()) {
                return frame;               // frame not started
            }
            if (rolls.get(i) == PINS) {
                i += 1;                     // strike closes the frame
                continue;
            }
            if (i + 1 >= rolls.size()) {
                return frame;               // first ball thrown, second pending
            }
            i += 2;
        }
        return FRAMES;
    }

    /**
     * Pins still standing for the CURRENT roll.
     *
     * <p>Drives pin reset: after a strike or a spare the rack is refilled,
     * otherwise the survivors stay put. The tenth frame refills mid-frame,
     * which is why this is computed rather than tracked.
     */
    public int pinsStanding() {
        int i = 0;
        for (int frame = 1; frame < FRAMES; frame++) {
            if (i >= rolls.size()) {
                return PINS;
            }
            if (rolls.get(i) == PINS) {
                i += 1;
            } else {
                if (i + 1 >= rolls.size()) {
                    return PINS - rolls.get(i);        // mid-frame
                }
                i += 2;
            }
        }
        // tenth frame: rack refills after a strike, or after a spare on roll 2
        int thrown = rolls.size() - i;
        if (thrown <= 0) {
            return PINS;
        }
        int a = rolls.get(i);
        if (thrown == 1) {
            return a == PINS ? PINS : PINS - a;
        }
        int b = rolls.get(i + 1);
        if (a == PINS) {
            return b == PINS ? PINS : PINS - b;
        }
        return (a + b == PINS) ? PINS : 0;
    }

    /** True once the game is over and no further rolls are accepted. */
    public boolean isComplete() {
        int i = 0;
        for (int frame = 1; frame < FRAMES; frame++) {
            if (i >= rolls.size()) {
                return false;
            }
            i += (rolls.get(i) == PINS) ? 1 : 2;
        }
        int thrown = rolls.size() - i;
        if (thrown < 2) {
            return false;
        }
        int a = rolls.get(i);
        int b = rolls.get(i + 1);
        // a strike or spare in the tenth earns a third roll
        boolean bonus = (a == PINS) || (a + b == PINS);
        return bonus ? thrown >= 3 : thrown >= 2;
    }

    /** Scoresheet marks for the HUD: "X", "/", "-" or the digit. */
    public List<String> marks() {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (int frame = 1; frame <= FRAMES && i < rolls.size(); frame++) {
            int a = rolls.get(i);
            boolean tenth = (frame == FRAMES);
            if (a == PINS && !tenth) {
                out.add("X");
                i += 1;
                continue;
            }
            out.add(mark(a, false));
            i++;
            if (i < rolls.size()) {
                int b = rolls.get(i);
                out.add(mark(b, a + b == PINS && a != PINS));
                i++;
            }
            if (tenth && i < rolls.size()) {
                out.add(mark(rolls.get(i), false));
                i++;
            }
        }
        return out;
    }

    private static String mark(int pins, boolean spare) {
        if (spare) {
            return "/";
        }
        if (pins == PINS) {
            return "X";
        }
        return pins == 0 ? "-" : Integer.toString(pins);
    }

    /**
     * Wipe the card back to frame 1, ball 1.
     *
     * <p>Also used by the measurement harness, which needs every throw to be a
     * fresh first ball. Without it a run rides a real game: the lane only
     * re-racks when a frame ADVANCES, so every second ball is bowled at a rack
     * the harness had forced back to ten - and once the tenth frame is done
     * {@link #roll(int)} stops recording entirely. Thirty throws then measure a
     * game and a half, not thirty first balls.
     */
    public void reset() {
        rolls.clear();
    }
}
