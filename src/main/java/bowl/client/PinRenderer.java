package bowl.client;

import bowl.Bowling;
import bowl.entity.BowlingPin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a pin as its flat item texture, standing or fallen.
 *
 * <p>Rendered from the ITEM model rather than a hand-built ModelPart
 * hierarchy. A pin is a silhouette; a billboard of the texture reads correctly
 * from any angle a bowler actually stands at, and it sidesteps writing and
 * texturing a cuboid model for something the size of a bottle.
 *
 * <p>The one thing it must communicate is standing versus fallen, since that
 * is what the score depends on - so a fallen pin lies flat rather than just
 * changing colour, and is legible from the foul line eighteen blocks away.
 */
public class PinRenderer extends EntityRenderer<BowlingPin> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "textures/entity/pin.png");

    /**
     * Model is 13 units tall, so 13/16 of a block before scaling; the pin's
     * hitbox is 0.55 high. 0.68 lands the visual just under the collision box,
     * which is the right way round - a pin that looks bigger than it is feels
     * like the game is cheating.
     */
    private static final float SCALE = 0.68f;

    private final ItemRenderer items;

    public PinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.items = ctx.getItemRenderer();
        this.shadowRadius = 0.15f;
    }

    @Override
    public void render(BowlingPin pin, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();

        if (pin.isStanding()) {
            // Upright, and NOT billboarded. The model is real geometry now, so
            // turning it to face the camera is what made it read as a flat
            // cutout leaning at an angle.
            pose.scale(SCALE, SCALE, SCALE);
            pose.translate(0.0, 0.5, 0.0);
        } else {
            // Tipped over, lying the way it was knocked. Rotating about the
            // base rather than the centre keeps it on the floor instead of
            // half-sunk into it.
            pose.translate(0.0, 0.11, 0.0);
            pose.mulPose(Axis.YP.rotationDegrees(-yaw));
            pose.mulPose(Axis.XP.rotationDegrees(90.0f));
            pose.scale(SCALE, SCALE, SCALE);
            pose.translate(0.0, 0.5, 0.0);
        }

        ItemStack stack = new ItemStack(Bowling.PIN_ITEM.get());
        items.renderStatic(stack, ItemDisplayContext.FIXED, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, pin.level(), pin.getId());

        pose.popPose();
        super.render(pin, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(BowlingPin pin) {
        return TEXTURE;
    }
}
