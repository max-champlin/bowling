package bowl.block;

import bowl.Bowling;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The foul line: one block per lane, placed at the bowler's end.
 *
 * <p>Facing matters. The block is placed facing DOWN the lane, and everything
 * else is derived from that - where the rack sits, which way a ball is thrown,
 * which direction "right" means for spin. Deriving it from one property keeps
 * a lane working whichever way it is built, instead of only north-south.
 *
 * <p>A slab-height block so you can stand behind it and walk up to it, rather
 * than a full cube you have to jump.
 */
public class BowlingLaneBlock extends BaseEntityBlock {

    public static final MapCodec<BowlingLaneBlock> CODEC = simpleCodec(BowlingLaneBlock::new);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    public BowlingLaneBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Face the way the player is looking, so you place it standing where
        // you intend to bowl from and it points down the lane.
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    /**
     * Say which way the lane points, out loud, at the moment of placing.
     *
     * <p>Facing decides where the rack spawns, and getting it backwards puts
     * ten pins 18 blocks behind the bowler where nothing will ever hit them.
     * That failure is invisible - the ball rolls down an empty lane and scores
     * zero, which reads as a broken mod rather than a block placed the wrong
     * way round. One line at placement time costs nothing and removes the
     * entire class of confusion.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || !(placer instanceof Player player)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "Lane points " + facing.getName().toUpperCase()
                + " - pins rack " + (int) BowlingLaneBlockEntity.LANE_LENGTH
                + " blocks that way. Follow the arrow."), false);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BowlingLaneBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, Bowling.LANE_BE.get(),
                (l, p, s, be) -> be.serverTick());
    }

    /**
     * Right-click to join the game, or to rack the pins if none are out.
     *
     * <p>Sneak-click resets the whole game rather than needing a separate
     * tool, since a lane that cannot be reset is worse than one that resets by
     * accident.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof BowlingLaneBlockEntity lane) {
            if (player.isShiftKeyDown()) {
                lane.resetGame();
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("Lane reset."), true);
            } else {
                lane.join(player);
            }
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Right-click the foul line holding lane boards to lay the whole lane.
     *
     * <p>Placing eighteen blocks of surface by hand and getting it straight is
     * tedious and easy to get wrong, and a crooked lane puts the pins somewhere
     * the scoring does not expect.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack held, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
            BlockHitResult hit) {

        if (!held.is(Bowling.LANE_BOARD_ITEM.get())) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        LaneBuilder.Result result = LaneBuilder.build(
                level, pos, state.getValue(FACING), player);
        player.displayClientMessage(result.message(), true);
        return net.minecraft.world.ItemInteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            // Driven off the OLD state, not the block entity: by this point the
            // entity is being torn down, and a pin left behind has no owner and
            // never disappears.
            BowlingLaneBlockEntity.clearPinsAt(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
