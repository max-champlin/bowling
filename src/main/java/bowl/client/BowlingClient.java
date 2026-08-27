package bowl.client;

import bowl.Bowling;
import bowl.item.SpinState;
import bowl.net.BowlPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Keys, and turning them into packets.
 *
 * <p>Up and down arrows for spin. They are chosen because nothing else in the
 * pack binds them - checked against all 616 installed mods - so there is no
 * conflict to resolve and no existing habit to break.
 *
 * <p>Space is NOT rebound. Jump is too fundamental to intercept globally, and
 * a mod that eats your jump key is a mod you uninstall. Instead the throw
 * listens for space only while the ball is actually being wound up, which is
 * the one moment the player means "release" rather than "jump".
 */
@EventBusSubscriber(modid = Bowling.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class BowlingClient {

    public static final String CATEGORY = "key.categories.bowling";

    public static final KeyMapping SPIN_LEFT = new KeyMapping(
            "key.bowling.spin_left", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);

    public static final KeyMapping SPIN_RIGHT = new KeyMapping(
            "key.bowling.spin_right", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);

    public static final KeyMapping SPIN_UP = new KeyMapping(
            "key.bowling.spin_more", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);

    public static final KeyMapping SPIN_DOWN = new KeyMapping(
            "key.bowling.spin_less", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);

    private BowlingClient() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SPIN_UP);
        event.register(SPIN_DOWN);
        event.register(SPIN_LEFT);
        event.register(SPIN_RIGHT);
    }

    /**
     * Poll the keys once a tick.
     *
     * <p>{@code consumeClick} drains the press queue, so a held key does not
     * spin the dial to the stop in a fifth of a second - one press is one
     * step, which is what makes it feel like a dial rather than a slider.
     */
    @EventBusSubscriber(modid = Bowling.MODID, value = Dist.CLIENT)
    public static final class Ticks {
        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (SPIN_UP.consumeClick() || SPIN_RIGHT.consumeClick()) {
                send(SpinState.STEP);
            }
            while (SPIN_DOWN.consumeClick() || SPIN_LEFT.consumeClick()) {
                send(-SpinState.STEP);
            }
            // Space while winding up means "throw", not "jump". Only while the
            // ball is being held, so jumping is untouched the rest of the time.
            if (mc.player.isUsingItem()
                    && mc.player.getUseItem().is(Bowling.BALL_ITEM.get())
                    && mc.options.keyJump.consumeClick()) {
                net.neoforged.neoforge.network.PacketDistributor
                        .sendToServer(new BowlPackets.Release());
            }
        }

        private static void send(float delta) {
            net.neoforged.neoforge.network.PacketDistributor
                    .sendToServer(new BowlPackets.Spin(delta));
        }
    }
}
