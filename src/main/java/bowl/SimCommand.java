package bowl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import bowl.block.BowlingLaneBlock;
import bowl.block.BowlingLaneBlockEntity;
import bowl.entity.BowlingBall;
import bowl.entity.BowlingPin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A repeatable throw, so the physics can be measured instead of felt.
 *
 * <p>Tuning this by hand meant Max bowling a frame, describing the result in
 * words, and a constant being nudged - a loop with one sample per shutdown. This
 * throws the same delivery a hundred times and counts pins, which is the only
 * way to tell "hooks nicely" from "got lucky twice".
 *
 * <p>Balls are constructed directly rather than through the item, deliberately:
 * the item takes its angle from where a player happens to be looking and its
 * power from how long a button was held, and neither is reproducible. Here both
 * are numbers.
 */
public final class SimCommand {

    private SimCommand() {
    }

    // ---- one run ---------------------------------------------------------

    private static ServerLevel level;
    private static BlockPos lanePos;
    private static ServerPlayer owner;
    private static double aimDeg, power;
    private static float spin;
    /** Ticks to let the deck finish after the ball is gone; the lane uses 40. */
    private static final int PIN_SETTLE = 60;

    private static int remaining, settleTicks, afterBall;
    /** Lowest standing count seen this roll; survives the lane re-racking. */
    private static int minStanding = 10;
    private static boolean waiting;
    private static JsonArray throwsOut;
    private static int pinsBefore;

    /** Where each pin stood at release, so displacement can be measured after. */
    private static final java.util.Map<java.util.UUID, Vec3> startPositions =
            new java.util.HashMap<>();

    public static boolean running() {
        return remaining > 0 || waiting;
    }

    /** name -> (read, write) for every constant worth sweeping. */
    private record Knob(java.util.function.DoubleSupplier get,
                        java.util.function.DoubleConsumer set) {
    }

    private static final java.util.LinkedHashMap<String, Knob> TUNABLES = new java.util.LinkedHashMap<>();

    static {
        TUNABLES.put("toppleSpeed", new Knob(() -> BowlingPin.TOPPLE_SPEED, v -> BowlingPin.TOPPLE_SPEED = v));
        TUNABLES.put("ballToPin", new Knob(() -> BowlingPin.BALL_TO_PIN, v -> BowlingPin.BALL_TO_PIN = v));
        TUNABLES.put("pinToPin", new Knob(() -> BowlingPin.PIN_TO_PIN, v -> BowlingPin.PIN_TO_PIN = v));
        TUNABLES.put("pinRetained", new Knob(() -> BowlingPin.PIN_RETAINED, v -> BowlingPin.PIN_RETAINED = v));
        TUNABLES.put("pinContact", new Knob(() -> BowlingPin.PIN_CONTACT, v -> BowlingPin.PIN_CONTACT = v));
        TUNABLES.put("fallenContact", new Knob(() -> BowlingPin.FALLEN_CONTACT, v -> BowlingPin.FALLEN_CONTACT = v));
        TUNABLES.put("graze", new Knob(() -> BowlingPin.GRAZE, v -> BowlingPin.GRAZE = v));
        TUNABLES.put("drag", new Knob(() -> BowlingPin.DRAG, v -> BowlingPin.DRAG = v));
        TUNABLES.put("deckHalf", new Knob(() -> BowlingPin.DECK_HALF, v -> BowlingPin.DECK_HALF = v));
        TUNABLES.put("kickback", new Knob(() -> BowlingPin.KICKBACK, v -> BowlingPin.KICKBACK = v));
        TUNABLES.put("hookForce", new Knob(() -> BowlingBall.HOOK_FORCE, v -> BowlingBall.HOOK_FORCE = v));
        TUNABLES.put("oilEnd", new Knob(() -> BowlingBall.OIL_END, v -> BowlingBall.OIL_END = v));
        TUNABLES.put("oilGrip", new Knob(() -> BowlingBall.OIL_GRIP, v -> BowlingBall.OIL_GRIP = v));
        TUNABLES.put("ballDeflect", new Knob(() -> BowlingBall.BALL_DEFLECT, v -> BowlingBall.BALL_DEFLECT = v));
        TUNABLES.put("jitterAim", new Knob(() -> BowlingBall.JITTER_AIM, v -> BowlingBall.JITTER_AIM = v));
        TUNABLES.put("jitterSpin", new Knob(() -> BowlingBall.JITTER_SPIN, v -> BowlingBall.JITTER_SPIN = v));
        TUNABLES.put("laneFriction", new Knob(() -> BowlingBall.LANE_FRICTION, v -> BowlingBall.LANE_FRICTION = v));
    }

    private static int setTunable(CommandSourceStack src, String name, double value) {
        Knob k = TUNABLES.get(name);
        if (k == null) {
            src.sendFailure(Component.literal("unknown: " + name + " (try /bowlsim show)"));
            return 0;
        }
        double old = k.get().getAsDouble();
        k.set().accept(value);
        src.sendSuccess(() -> Component.literal(name + ": " + old + " -> " + value), false);
        return 1;
    }

    /** Included in every result line, so a sweep records what it was run under. */
    private static JsonObject tunableSnapshot() {
        JsonObject o = new JsonObject();
        TUNABLES.forEach((k, v) -> o.addProperty(k, v.get().getAsDouble()));
        return o;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("bowlsim")
                // Permission 2 (ops). This is not a spectator tool: it retunes the
        // physics for EVERY player on the server, live, and throws balls down
        // a lane that may not be yours. Single player hosts are op by default,
        // so this costs a solo player nothing.
        .requires(s -> s.hasPermission(2))
                .then(Commands.argument("throws", IntegerArgumentType.integer(1, 200))
                        .then(Commands.argument("aimDeg", DoubleArgumentType.doubleArg(-12, 12))
                                .then(Commands.argument("power", DoubleArgumentType.doubleArg(0.1, 1.0))
                                        .then(Commands.argument("spin", DoubleArgumentType.doubleArg(-1, 1))
                                                .executes(c -> start(c.getSource(),
                                                        IntegerArgumentType.getInteger(c, "throws"),
                                                        DoubleArgumentType.getDouble(c, "aimDeg"),
                                                        DoubleArgumentType.getDouble(c, "power"),
                                                        (float) DoubleArgumentType.getDouble(c, "spin")))))))
                // Physics constants, settable at runtime.
                //
                // Every one of these used to cost a shutdown, a rebuild and a
                // relaunch to try a single value. Made settable so the bridge
                // can sweep the CONSTANTS the same way it sweeps aim and spin -
                // dozens of combinations in one session instead of one per
                // restart. Values are not persisted: a restart returns to the
                // compiled defaults, which is what makes experimenting safe.
                .then(Commands.literal("set")
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> {
                                    TUNABLES.keySet().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(c -> setTunable(c.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"),
                                                DoubleArgumentType.getDouble(c, "value"))))))
                .then(Commands.literal("show").executes(c -> {
                    TUNABLES.forEach((k, v) -> c.getSource().sendSuccess(
                            () -> Component.literal("  " + k + " = " + v.get()), false));
                    return 1;
                }))
                .then(Commands.literal("stop").executes(c -> {
                    remaining = 0;
                    waiting = false;
                    c.getSource().sendSuccess(() -> Component.literal("bowlsim stopped"), false);
                    return 1;
                })));
    }

    private static int start(CommandSourceStack src, int count, double aim, double pow, float sp) {
        if (running()) {
            src.sendFailure(Component.literal("bowlsim already running"));
            return 0;
        }
        ServerLevel lvl = src.getLevel();
        // The bridge runs commands as the server console, which has no player
        // and sits at world spawn - so searching around the source position
        // finds nothing and every throw fails silently. Fall back to a real
        // player: the lane is wherever the bowler is standing.
        ServerPlayer who = src.getPlayer();
        if (who == null) {
            who = src.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
        }
        BlockPos origin = who != null ? who.blockPosition()
                : BlockPos.containing(src.getPosition());
        BlockPos found = findLane(lvl, origin);
        if (found == null) {
            src.sendFailure(Component.literal("no lane within 8 blocks - stand at the foul line"));
            return 0;
        }
        level = lvl;
        lanePos = found;
        owner = who;
        aimDeg = aim;
        power = pow;
        spin = sp;
        remaining = count;
        waiting = false;
        settleTicks = 0;
        afterBall = 0;
        minStanding = 10;
        throwsOut = new JsonArray();
        src.sendSuccess(() -> Component.literal(
                "bowlsim: " + count + " throws, aim " + aim + " deg, power " + pow
                        + ", spin " + sp), false);
        return 1;
    }

    private static BlockPos findLane(ServerLevel lvl, BlockPos origin) {
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -4, -8),
                                                 origin.offset(8, 4, 8))) {
            if (lvl.getBlockEntity(p) instanceof BowlingLaneBlockEntity) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Drives one throw at a time; a batch fired at once would be a pile-up. */
    public static void tick(MinecraftServer server) {
        if (!running() || level == null) {
            return;
        }
        if (!(level.getBlockEntity(lanePos) instanceof BowlingLaneBlockEntity lane)) {
            remaining = 0;
            return;
        }

        if (waiting) {
            settleTicks++;
            boolean ballsGone = level.getEntitiesOfClass(BowlingBall.class,
                    new net.minecraft.world.phys.AABB(lanePos).inflate(48.0)).isEmpty();
            if (ballsGone) {
                afterBall++;
            }
            // Track the LOWEST standing count seen, not the count at the end.
            //
            // A strike ends the frame on one ball, so the lane re-racks while
            // the harness is still waiting - and by the time it counts, all ten
            // are back up and "10 - 10 = 0". A strike and a gutter ball
            // produced byte-identical output. Watching the minimum catches the
            // empty deck before it is reset.
            minStanding = Math.min(minStanding, lane.standingPins());
            // The ball leaving is NOT the end of the roll. Struck pins keep
            // travelling and knocking others down after it has gone - the lane
            // itself waits 40 ticks before scoring, for exactly this reason.
            // Counting the deck the moment the ball vanished caught the cascade
            // mid-flight and capped every result at six pins.
            if ((ballsGone && afterBall >= PIN_SETTLE) || settleTicks > 400) {
                int down = pinsBefore - minStanding;
                boolean reRacked = lane.standingPins() > minStanding;
                JsonObject t = new JsonObject();
                t.addProperty("throwNo", throwsOut.size() + 1);
                t.addProperty("pinsDown", down);

                // How far the pins actually travelled.
                //
                // The cascade depends on struck pins crossing the 0.27 blocks
                // to their neighbour. If they barely move, no collision test -
                // swept or otherwise - can help, and the fault is upstream in
                // the impulse or in move() being blocked. This turns that from
                // a guess into a number.
                double maxMove = 0.0, sumMove = 0.0;
                int moved = 0;
                for (var pin : lane.pins()) {
                    var startPos = startPositions.get(pin.getUUID());
                    if (startPos == null) {
                        continue;
                    }
                    double d = Math.hypot(pin.getX() - startPos.x, pin.getZ() - startPos.z);
                    maxMove = Math.max(maxMove, d);
                    sumMove += d;
                    if (d > 0.05) {
                        moved++;
                    }
                }
                t.addProperty("pinMaxMove", Math.round(maxMove * 1000) / 1000.0);
                t.addProperty("pinMeanMove", lane.pins().isEmpty() ? 0
                        : Math.round(sumMove / lane.pins().size() * 1000) / 1000.0);
                t.addProperty("pinsThatMoved", moved);

                // WHICH pins lived, by number. "Nine down" says nothing about
                // whether the survivor is a corner the cascade cannot reach or
                // a front pin the ball missed - and those need opposite fixes.
                JsonArray left = new JsonArray();
                for (var pin : lane.pins()) {
                    if (pin.isStanding()) {
                        left.add(pin.pinNumber());
                    }
                }
                t.add("standingPins", left);
                t.addProperty("standing", minStanding);
                t.addProperty("reRacked", reRacked);
                t.addProperty("strike", down == 10);
                t.addProperty("settleTicks", settleTicks);
                t.addProperty("timedOut", !ballsGone);
                throwsOut.add(t);
                waiting = false;
                settleTicks = 0;
        afterBall = 0;
        minStanding = 10;
                if (remaining == 0) {
                    finish(server);
                }
            }
            return;
        }

        // Wipe the card first. The lane is running a real ten-frame game
        // underneath, and it only re-racks when a frame ADVANCES - so on ball
        // two of a frame it expects the remainder, not the ten pins this
        // harness just forced back up. Reset to frame 1 ball 1 and every throw
        // is genuinely a first ball.
        if (owner != null) {
            var card = lane.cardFor(owner.getUUID());
            if (card != null) {
                card.reset();
            }
        }
        lane.clearPins();
        lane.rackPins();
        pinsBefore = lane.standingPins();

        Direction facing = level.getBlockState(lanePos).getValue(BowlingLaneBlock.FACING);
        Vec3 axis = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        // Seeded from the throw index: the sim samples the SAME release
        // variation a player gets, but a re-run of a sweep reproduces its
        // numbers exactly. Without jitter this measured a game nobody plays;
        // without the seed a sweep could never be repeated.
        net.minecraft.util.RandomSource rng =
                net.minecraft.util.RandomSource.create(throwsOut.size() * 7919L + 104729L);
        double aimRad = Math.toRadians(aimDeg) + BowlingBall.jitterAim(rng);
        float thrownSpin = spin + BowlingBall.jitterSpin(rng);
        Vec3 dir = axis.yRot((float) -aimRad).normalize();

        Vec3 start = Vec3.atCenterOf(lanePos).add(dir.scale(0.6)).add(0, 0.15, 0);
        Vec3 motion = dir.scale(BowlingBall.MAX_POWER * power);

        // Take a ball out of the bowler's hands, exactly as the item does.
        //
        // A bowling game uses ONE ball: it goes down the lane and the gutter
        // brings it back, which is what returnToThrower models. Spawning a ball
        // without consuming one made every throw a net +1 - sixty-five throws
        // filled the hotbar and then spilled onto the floor. Shrinking here
        // balances the return and keeps the count constant all run.
        if (owner != null) {
            var inv = owner.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(Bowling.BALL_ITEM.get())) {
                    inv.getItem(i).shrink(1);
                    break;
                }
            }
        }

        startPositions.clear();
        for (var pin : lane.pins()) {
            startPositions.put(pin.getUUID(), pin.position());
        }

        lane.beginRoll();
        level.addFreshEntity(new BowlingBall(level, owner, lanePos, start, motion, thrownSpin));

        remaining--;
        waiting = true;
        settleTicks = 0;
        afterBall = 0;
        minStanding = 10;
    }

    private static void finish(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("time", java.time.OffsetDateTime.now().toString());
        out.addProperty("aimDeg", aimDeg);
        out.addProperty("power", power);
        out.addProperty("spin", spin);
        out.addProperty("throws", throwsOut.size());
        out.add("tunables", tunableSnapshot());

        int strikes = 0, total = 0;
        for (var e : throwsOut) {
            JsonObject t = e.getAsJsonObject();
            if (t.get("strike").getAsBoolean()) {
                strikes++;
            }
            total += t.get("pinsDown").getAsInt();
        }
        out.addProperty("strikes", strikes);
        out.addProperty("strikePct", throwsOut.size() == 0 ? 0
                : Math.round(1000.0 * strikes / throwsOut.size()) / 10.0);
        out.addProperty("meanPins", throwsOut.size() == 0 ? 0
                : Math.round(100.0 * total / throwsOut.size()) / 100.0);
        out.add("detail", throwsOut);

        try {
            Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("bowling");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("bowl-sim.jsonl"),
                    new Gson().toJson(out) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Bowling.LOG.error("could not write bowl-sim.jsonl", e);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[bowlsim] " + throwsOut.size()
                    + " throws, " + strikes + " strikes, mean "
                    + out.get("meanPins").getAsDouble() + " pins"));
        }
    }
}
