package bowl.client;

import bowl.Bowling;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Hooks the two entity renderers up.
 *
 * <p>Without this both entities render as nothing at all - not an error, just
 * an invisible ball knocking over invisible pins, which is a genuinely
 * confusing way to discover the renderer is missing.
 *
 * <p>Registered TWO ways on purpose. The {@link EventBusSubscriber} annotation
 * is the tidy way, but {@code bus()} is deprecated and marked for removal in
 * this NeoForge version, and an annotation that quietly stops being honoured
 * fails exactly like a missing renderer - invisible entities, no error. So the
 * mod also attaches the listener explicitly. {@link #done} makes the second one
 * a no-op if the first already worked, and the log line says which fired.
 */
@EventBusSubscriber(modid = Bowling.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class ClientRenderers {

    private static boolean done = false;

    private ClientRenderers() {
    }

    /** Explicit hook, called from the mod constructor on the client only. */
    public static void registerOn(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                ClientRenderers::register);
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        if (done) {
            return;
        }
        done = true;
        event.registerEntityRenderer(Bowling.PIN.get(), PinRenderer::new);
        event.registerEntityRenderer(Bowling.BALL.get(), BallRenderer::new);
    }
}
