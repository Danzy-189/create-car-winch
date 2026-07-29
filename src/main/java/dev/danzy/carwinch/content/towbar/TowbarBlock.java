package dev.danzy.carwinch.content.towbar;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import dev.danzy.carwinch.content.rope.CarWinchRopeHelper;
import dev.danzy.carwinch.registry.CWBlockEntities;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.simulated_team.simulated.content.blocks.rope.RopeHolderBlock;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.item.TooltipFlag;
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

import java.util.List;

public class TowbarBlock extends Block
        implements RopeHolderBlock,
        BlockSubLevelAssemblyListener,
        BlockSubLevelCollisionShape,
        IWrenchable {

    public static final MapCodec<TowbarBlock> CODEC =
            simpleCodec(TowbarBlock::new);

    public static final DirectionProperty FACING =
            DirectionalBlock.FACING;

    public static final BooleanProperty HOOKED =
            BooleanProperty.create("hooked");

    private static final ResourceLocation WRENCH_ID =
            ResourceLocation.fromNamespaceAndPath("create", "wrench");

    /*
     * Хитбоксы повторяют новую модель (плита крепления + две направляющие + зажим),
     * геометрия которой взята у rope_connector. Модель направленная, поэтому
     * форма своя для каждого направления, иначе по блоку неудобно попадать.
     */
    private static final VoxelShape SHAPE_SOUTH =
            Shapes.box(0.125, 0.125, 0.25, 0.875, 0.875, 0.8125);

    private static final VoxelShape SHAPE_NORTH =
            Shapes.box(0.125, 0.125, 0.1875, 0.875, 0.875, 0.75);

    private static final VoxelShape SHAPE_EAST =
            Shapes.box(0.25, 0.125, 0.125, 0.8125, 0.875, 0.875);

    private static final VoxelShape SHAPE_WEST =
            Shapes.box(0.1875, 0.125, 0.125, 0.75, 0.875, 0.875);

    private static final VoxelShape SHAPE_VERTICAL =
            Shapes.box(0.125, 0.125, 0.125, 0.875, 0.875, 0.875);

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

    /**
     * Подсказка в инвентаре. Порядок кликов сцепкой важен: первый клик —
     * сторона шара, то есть ведущая машина, второй — прицеп. Из самой игры
     * это никак не следовало, поэтому пишем прямо в описании предмета.
     *
     * Важно: BlockBehaviour объявляет метод protected, но Block расширяет его до
     * public, поэтому переопределять его нужно именно public.
     */
    @Override
    public void appendHoverText(
            final ItemStack stack,
            final Item.TooltipContext context,
            final List<Component> tooltipComponents,
            final TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        tooltipComponents.add(
                Component.translatable("block.carwinch.towbar.tooltip.order")
                        .withStyle(ChatFormatting.GRAY)
        );

        tooltipComponents.add(
                Component.translatable("block.carwinch.towbar.tooltip.ball")
                        .withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    /**
     * Поворот гаечным ключом Create. Фаркоп всегда горизонтальный,
     * поэтому вращаем его по часовой вокруг вертикальной оси независимо
     * от того, по какой грани щёлкнули. Если сцепка собрана, сюда вообще
     * не дойдёт: клик перехватывает useItemOn и снимает вал.
     */
    @Override
    public BlockState getRotatedBlockState(
            final BlockState originalState,
            final Direction targetedFace
    ) {
        final Direction facing = originalState.getValue(FACING);

        if (facing.getAxis().isVertical()) {
            return originalState.setValue(FACING, Direction.NORTH);
        }

        return originalState.setValue(FACING, facing.getClockWise());
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
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case NORTH -> SHAPE_NORTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_VERTICAL;
        };
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
        final Item wrench = wrench();
        final boolean wrenchInHand = wrench != null && stack.is(wrench);

        /*
         * ПКМ гаечным ключом Create по фаркопу разбирает вал-сцепку.
         * Shift больше не нужен, но и с ним работает точно так же.
         *
         * Важно перехватить клик и на клиенте: если вернуть PASS, дальше
         * сработает сам ключ и провернёт блок вместо снятия сцепки.
         */
        if (wrenchInHand
                && level.getBlockEntity(pos) instanceof TowbarBlockEntity towbar
                && towbar.isCoupled()) {

            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }

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
         * Обычной верёвкой Simulated наши блоки соединять нельзя: только
         * стальным тросом. Клик гасим и на клиенте, иначе RopeItem успеет
         * запомнить точку в своём дата-компоненте.
         */
        if (CarWinchRopeHelper.isPlainRope(stack)) {
            if (!level.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable("carwinch.rope.wrong_item"),
                        true
                );
            }

            return ItemInteractionResult.SUCCESS;
        }

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

        /*
         * Ножницы и прочие режущие трос предметы. Своя реализация вместо
         * RopeHolderBlock.shearRope, чтобы выпадал стальной трос, а не верёвка.
         *
         * Раньше здесь был безусловный каст (ServerPlayer) player, из-за чего
         * взаимодействие от не-игрока (фейковые игроки автоматизации)
         * падало с ClassCastException.
         */
        if (stack.is(SimTags.Items.DESTROYS_ROPE)
                && player instanceof ServerPlayer serverPlayer) {

            return CarWinchRopeHelper.destroyRopeAndDropSteel(level, pos, serverPlayer)
                    ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.FAIL;
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

            /*
             * Снимаем трос до IBE.onRemove: иначе его разорвёт сам Simulated
             * и вернёт игроку свою верёвку вместо стального троса.
             */
            CarWinchRopeHelper.destroyRopeAndDropSteel(level, pos, null);
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
