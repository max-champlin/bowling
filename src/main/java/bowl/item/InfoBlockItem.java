package bowl.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * A block item that explains itself in its tooltip.
 *
 * <p>Exists because "Bowling Lane" and "Lane Board" are easy to confuse, and
 * getting them the wrong way round fails SILENTLY - hold the wrong one at the
 * foul line and it simply places a block instead of building the lane, with no
 * indication you asked for anything. A mod that needs its author standing next
 * to you to explain the difference is a broken mod.
 */
public class InfoBlockItem extends BlockItem {

    private final String key;

    /**
     * @param key translation key for the hint line, shown greyed under the name
     */
    public InfoBlockItem(Block block, Properties props, String key) {
        super(block, props);
        this.key = key;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        for (String line : Component.translatable(key).getString().split("\n")) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
