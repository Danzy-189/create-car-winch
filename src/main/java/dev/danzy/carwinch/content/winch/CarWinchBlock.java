package dev.danzy.carwinch.content.winch;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import dev.danzy.carwinch.registry.CWBlockEntities;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.simulated_team.simulated.content.blocks.rope.RopeHolderBlock;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The car winch. Holds a steel rope strand (owner side) and reels it in on redstone power.
 */
public class CarWinchBlock extends Block
        implements RopeHolderBlock<CarWinchBlockEntity>, BlockSubLevelAssemblyListener, BlockSubLevelCollisionShape {

    public static final MapCodec<CarWinchBlock> CODEC = simpleCodec(CarWinchBlock::new);

    public static final DirectionProperty FACING = DirectionalBlock.FACING;
    /** false -> winch.bbmodel (empty), true -> winch_1.bbmodel (rope spooled) */
    public static final BooleanProperty ROPED = BooleanProperty.create("roped");
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    private static final VoxelShape SHAPE = Shapes.box(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375);

    public CarWinchBlock(final Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ROPED, false)
                .setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROPED, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(ROPED, false)
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()))
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getSubLevelCollisionShape(final BlockGetter blockGetter, final BlockState state) {
        return SHAPE;
    }

    @Override
    protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos,
                                   final Block neighborBlock, final BlockPos neighborPos, final boolean movedByPiston) {
        if (level.isClientSide()) {
            return;
        }
        final boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level,
                                              final BlockPos pos, final Player player, final InteractionHand hand,
                                              final BlockHitResult hitResult) {
        if (!level.isClientSide() && stack.is(SimTags.Items.DESTROYS_ROPE)) {
            return RopeHolderBlock.shearRope(this, level, pos, (ServerPlayer) player);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected void onRemove(final BlockState state, final Level level, final BlockPos pos,
                            final BlockState newState, final boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<CarWinchBlockEntity> getBlockEntityClass() {
        return CarWinchBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CarWinchBlockEntity> getBlockEntityType() {
        return CWBlockEntities.WINCH.get();
    }
}
