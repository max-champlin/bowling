package bowl;

import bowl.block.BowlingLaneBlock;
import bowl.block.BowlingLaneBlockEntity;
import bowl.entity.BowlingBall;
import bowl.entity.BowlingPin;
import bowl.item.BowlingBallItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Bowling.MODID)
public final class Bowling {
    public static final String MODID = "bowling";
    public static final Logger LOG = LoggerFactory.getLogger("Bowling");

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    /**
     * The foul line. One block per lane, placed at the bowler's end facing
     * down the lane.
     *
     * <p>Everything about a game lives on this block: whose turn it is, each
     * player's card, and where the rack belongs. Putting the state here rather
     * than on the player means a game survives logging out, and several people
     * can share one lane without the mod tracking who is standing where.
     */
    public static final DeferredHolder<Block, BowlingLaneBlock> LANE =
            BLOCKS.register("lane", () -> new BowlingLaneBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)));

    public static final DeferredHolder<Item, Item> LANE_ITEM =
            ITEMS.register("lane", () -> new bowl.item.InfoBlockItem(
                    LANE.get(), new Item.Properties(), "item.bowling.lane.tip"));

    /**
     * The playing surface. Purely a marker block - the ball rolls on a fixed
     * resistance wherever it is, so boards are what make a lane LOOK and
     * measure like a lane rather than what makes it behave like one.
     */
    public static final DeferredHolder<Block, Block> LANE_BOARD =
            BLOCKS.register("lane_board", () -> new Block(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.SAND)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)));

    public static final DeferredHolder<Item, Item> LANE_BOARD_ITEM =
            ITEMS.register("lane_board", () -> new bowl.item.InfoBlockItem(
                    LANE_BOARD.get(), new Item.Properties(), "item.bowling.lane_board.tip"));

    /**
     * The gutter. The one surface the ball treats differently, and the only
     * place a ball is out of play.
     */
    public static final DeferredHolder<Block, Block> GUTTER =
            BLOCKS.register("gutter", () -> new Block(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(2.0f)
                            .sound(SoundType.STONE)));

    public static final DeferredHolder<Item, Item> GUTTER_ITEM =
            ITEMS.register("gutter", () ->
                    new BlockItem(GUTTER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BowlingLaneBlockEntity>>
            LANE_BE = BLOCK_ENTITIES.register("lane", () ->
            BlockEntityType.Builder.<BowlingLaneBlockEntity>of(
                    BowlingLaneBlockEntity::new, LANE.get()).build(null));

    /**
     * A pin. An ENTITY rather than a block, so pins can be knocked into each
     * other and carry momentum - the thing that makes a strike feel earned
     * instead of a lookup table.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BowlingPin>> PIN =
            ENTITIES.register("pin", () -> EntityType.Builder
                    .<BowlingPin>of(BowlingPin::new, MobCategory.MISC)
                    .sized(0.22f, 0.55f)          // slim and tall, like a pin
                    .clientTrackingRange(8)
                    .updateInterval(1)            // they move fast when hit
                    .build("pin"));

    /** The ball. Returns to hand after every roll, so there is exactly one. */
    public static final DeferredHolder<EntityType<?>, EntityType<BowlingBall>> BALL =
            ENTITIES.register("ball", () -> EntityType.Builder
                    .<BowlingBall>of(BowlingBall::new, MobCategory.MISC)
                    .sized(0.32f, 0.32f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("ball"));

    /** Never craftable and in no creative tab - it exists so the pin entity
     *  has an item model to render from, rather than needing a hand-built
     *  ModelPart hierarchy for what is essentially a silhouette. */
    public static final DeferredHolder<Item, Item> PIN_ITEM =
            ITEMS.register("pin", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> BALL_ITEM =
            ITEMS.register("ball", () -> new BowlingBallItem(
                    new Item.Properties().stacksTo(1)));

    public static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * A tab of our own.
     *
     * <p>Not decoration: JEI builds its ingredient list from the creative menu,
     * so an item that belongs to no tab is invisible to JEI and to recipe
     * lookup - which makes a craftable item effectively undiscoverable even
     * though its recipe exists and works.
     */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab,
            net.minecraft.world.item.CreativeModeTab> TAB =
            TABS.register("bowling", () -> net.minecraft.world.item.CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.bowling"))
                    .icon(() -> new net.minecraft.world.item.ItemStack(BALL_ITEM.get()))
                    .displayItems((params, out) -> {
                        out.accept(BALL_ITEM.get());
                        out.accept(LANE_ITEM.get());
                        out.accept(LANE_BOARD_ITEM.get());
                        out.accept(GUTTER_ITEM.get());
                    })
                    .build());

    public Bowling(IEventBus modBus) {
        TABS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(Bowling::registerPackets);

        // Measurement harness. Registered on the game bus rather than the mod
        // bus because commands and ticks are runtime events, not setup ones.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e)
                        -> SimCommand.register(e.getDispatcher()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e)
                        -> SimCommand.tick(e.getServer()));

        // Belt and braces for the renderers - see ClientRenderers. Guarded by
        // dist so a dedicated server never touches a client-only class.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            bowl.client.ClientRenderers.registerOn(modBus);
        }
    }

    private static void registerPackets(
            net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        bowl.net.BowlPackets.register(event.registrar("1"));
    }
}
