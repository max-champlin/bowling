package bowl.client;

import bowl.Bowling;
import bowl.entity.BowlingBall;
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
 * Draws the ball, rolling.
 *
 * <p>The rotation is not decoration. Spin is invisible otherwise - the ball
 * curves and you cannot tell whether that was your hook or a bad line. Rolling
 * the texture at a rate tied to speed and spin makes the input legible, which
 * matters more here than it looks because the whole control scheme is "feel
 * how much you put on it".
 */
public class BallRenderer extends EntityRenderer<BowlingBall> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Bowling.MODID, "textures/item/ball.png");

    private final ItemRenderer items;

    public BallRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.items = ctx.getItemRenderer();
        this.shadowRadius = 0.2f;
    }

    @Override
    public void render(BowlingBall ball, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0, 0.16, 0.0);

        // face the camera, then roll about the horizontal axis so the holes
        // visibly tumble as it travels
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.ZP.rotationDegrees(ball.roll() * 360.0f));
        pose.scale(0.55f, 0.55f, 0.55f);

        ItemStack stack = new ItemStack(Bowling.BALL_ITEM.get());
        items.renderStatic(stack, ItemDisplayContext.FIXED, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, ball.level(), ball.getId());

        pose.popPose();
        super.render(ball, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(BowlingBall ball) {
        return TEXTURE;
    }
}
