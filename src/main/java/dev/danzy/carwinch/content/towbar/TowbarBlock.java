package dev.danzy.carwinch.content.towbar;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import dev.danzy.carwinch.registry.CWBlockEntities;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.simulated_team.simulated.content.blocks.rope.RopeHolderBlock;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class TowbarBlock extends Block
        implements RopeHolderBlock,
        BlockSubLevelAssemblyListener,
        BlockSubLevelCollisionShape {

    public static final MapCodec<TowbarBlock> CODEC =
            simpleCodec(TowbarBlock::new);

    public static final DirectionProperty FACING =
            DirectionalBlock.FACING;

    public static final BooleanProperty HOOKED =
            BooleanProperty.create("hooked");

    private static final ResourceLocation WRENCH_ID =
            ResourceLocation.fromNamespaceAndPath("create", "wrench");

    private static final VoxelShape SHAPE =
            Shapes.box(
                    0.1875,
                    0.0,
                    0.1875,
                    0.8125,
                    0.625,
                    0.8125
            );

    public TowbarBlock(final Properties properties) {
        super(properties);

        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(FACING, Direction.NORTH)
                        .setValue(HOOKED, false)
        );
    }

    @Override
    protected MapCodec<TowbarBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(
            final StateDefinition.Builder<Block, BlockState> builder
    ) {
        builder.add(FACING, HOOKED);
    }

    @Override
    public BlockState getStateForPlacement(
            final BlockPlaceContext context
    ) {
        return this.defaultBlockState()
                .setValue(
                        FACING,
                        context.getHorizontalDirection().getOpposite()
                )
                .setValue(HOOKED, false);
    }

    @Override
    protected BlockState rotate(
            final BlockState state,
            final Rotation rotation
    ) {
        return state.setValue(
                FACING,
                rotation.rotate(state.getValue(FACING))
        );
    }

    @Override
    protected BlockState mirror(
            final BlockState state,
            final Mirror mirror
    ) {
        return state.rotate(
                mirror.getRotation(state.getValue(FACING))
        );
    }

    @Override
    public VoxelShape getShape(
            final BlockState state,
            final BlockGetter level,
            final BlockPos pos,
            final CollisionContext context
    ) {
        return SHAPE;
    }

    @Override
    public VoxelShape getSubLevelCollisionShape(
            final BlockGetter level,
            final BlockState state
    ) {
        return Shapes.empty();
    }

    /**
     * Гаечный ключ Create. Если Create по какой-то причине не зарегистрировал ключ,
     * регистр вернёт AIR, а не null, поэтому проверяем именно это.
     */
    @Nullable
    private static Item wrench() {
        final Item item = BuiltInRegistries.ITEM.get(WRENCH_ID);
        return item == Items.AIR ? null : item;
    }

    @Override
    protected ItemInteractionResult useItemOn(
            final ItemStack stack,
            final BlockState state,
            final Level level,
            final BlockPos pos,
            final Player player,
            final InteractionHand hand,
            final BlockHitResult hitResult
    ) {
        if (level.isClientSide) {
            return super.useItemOn(
                    stack,
                    state,
                    level,
                    pos,
                    player,
                    hand,
                    hitResult
            );
        }

        final Item wrench = wrench();

        // Shift + гаечный ключ - разобрать вал-сцепку.
        if (wrench != null
                && player.isShiftKeyDown()
                && stack.is(wrench)
                && level.getBlockEntity(pos) instanceof TowbarBlockEntity towbar
                && towbar.isCoupled()) {

            TowbarBlockEntity.detachCoupling(level, pos);

            level.playSound(
                    null,
                    pos,
                    SoundEvents.IRON_TRAPDOOR_CLOSE,
                    SoundSource.BLOCKS,
                    0.8F,
                    0.8F
            );

            return ItemInteractionResult.SUCCESS;
        }

        /*
         * Раньше здесь был безусловный каст (ServerPlayer) player, из-за чего
         * взаимодействие от не-игрока (фейковые игроки автоматизации)
         * падало с ClassCastException.
         */
        if (stack.is(SimTags.Items.DESTROYS_ROPE)
                && player instanceof ServerPlayer serverPlayer) {

            return RopeHolderBlock.shearRope(
                    this,
                    level,
                    pos,
                    serverPlayer
            );
        }

        return super.useItemOn(
                stack,
                state,
                level,
                pos,
                player,
                hand,
                hitResult
        );
    }

    @Override
    protected void onRemove(
            final BlockState state,
            final Level level,
            final BlockPos pos,
            final BlockState newState,
            final boolean movedByPiston
    ) {
        if (!state.is(newState.getBlock())) {
            TowbarBlockEntity.detachCoupling(level, pos);
        }

        IBE.onRemove(state, level, pos, newState);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<TowbarBlockEntity> getBlockEntityClass() {
        return TowbarBlockEntity.class;
    }

    @Override
    public BlockEntityType<TowbarBlockEntity> getBlockEntityType() {
        return CWBlockEntities.TOWBAR.get();
    }
}
