package bowl.game;

/**
 * Where the ten pins stand, relative to the head pin.
 *
 * <p>Real bowling uses a triangle with 12 inches between pin centres, four
 * rows deep, apex toward the bowler. That geometry is the whole reason a hook
 * into the "pocket" (between pins 1 and 3) scatters the rack while a flat hit
 * on the head pin leaves splits - so it is worth keeping the true shape rather
 * than eyeballing a triangle.
 *
 * <p>Scaled to Minecraft: 12 inches is about 0.3 blocks, which packs the pins
 * too tightly to see or hit distinctly. {@link #SPACING} widens that to 0.45
 * so the rack reads clearly and spans roughly two blocks - the same width the
 * lane is built to. Numbers are held here rather than scattered through the
 * entity code because this is one of the values that will want tuning by feel.
 *
 * <p>Pins are numbered as bowlers number them: 1 at the apex, then left to
 * right along each row back.
 */
public final class Rack {

    /** Distance between adjacent pin centres, in blocks. */
    public static final double SPACING = 0.45;

    /** Depth between rows. An equilateral triangle, so height = spacing * √3/2. */
    public static final double ROW_DEPTH = SPACING * 0.8660254;

    public static final int COUNT = 10;

    /**
     * Offsets from the head pin, as {right, forward} in blocks.
     *
     * <p>"forward" is away from the bowler, "right" is across the lane. The
     * caller rotates these into world space using the lane's facing, so the
     * rack lays out correctly whichever way the lane is built.
     */
    private static final double[][] OFFSETS = new double[COUNT][];

    static {
        int pin = 0;
        for (int row = 0; row < 4; row++) {
            int inRow = row + 1;
            // centre each row on the lane: a row of n pins spans (n-1) gaps
            double startRight = -SPACING * (inRow - 1) / 2.0;
            for (int i = 0; i < inRow; i++) {
                OFFSETS[pin++] = new double[]{
                        startRight + i * SPACING,
                        row * ROW_DEPTH
                };
            }
        }
    }

    private Rack() {
    }

    /** {right, forward} offset for pin 1..10. */
    public static double[] offset(int pinNumber) {
        if (pinNumber < 1 || pinNumber > COUNT) {
            throw new IllegalArgumentException("pin out of range: " + pinNumber);
        }
        double[] o = OFFSETS[pinNumber - 1];
        return new double[]{o[0], o[1]};
    }

    /** Total width the rack occupies, for sanity-checking lane builds. */
    public static double width() {
        return SPACING * 3;
    }

    /** Total depth from head pin to back row. */
    public static double depth() {
        return ROW_DEPTH * 3;
    }
}
