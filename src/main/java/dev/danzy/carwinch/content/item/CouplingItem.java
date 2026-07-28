package dev.danzy.carwinch.content.item;

import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.registry.CWDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CouplingItem extends Item {

    public CouplingItem(final Properties properties) {
        super(properties);
    }

    @Nullable
    private static TowbarBlockEntity getTowbar(final Level level, final BlockPos pos) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof TowbarBlockEntity towbar ? towbar : null;
    }

    private static void clearSelection(final ItemStack stack) {
        stack.remove(CWDataComponents.COUPLING_FIRST_CONNECTION.get());
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos clickedPos = context.getClickedPos();
        final ItemStack stack = context.getItemInHand();
        final Player player = context.getPlayer();

        if (player != null && player.isShiftKeyDown()) {
            clearSelection(stack);

            if (level.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable("carwinch.coupling.cleared"),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        final TowbarBlockEntity clicked = getTowbar(level, clickedPos);

        if (clicked == null) {
            return super.useOn(context);
        }

        if (clicked.isCoupled()) {
            if (level.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable("carwinch.coupling.occupied"),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        final BlockPos firstPos =
                stack.get(CWDataComponents.COUPLING_FIRST_CONNECTION.get());

        if (firstPos == null) {
            stack.set(
                    CWDataComponents.COUPLING_FIRST_CONNECTION.get(),
                    clickedPos
            );

            if (level.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable("carwinch.coupling.selected"),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        if (firstPos.equals(clickedPos)) {
            clearSelection(stack);
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide) {
            final TowbarBlockEntity first = getTowbar(level, firstPos);

            final boolean created =
                    first != null
                            && TowbarBlockEntity.createCoupling(first, clicked);

            clearSelection(stack);

            if (created) {
                level.playSound(
                        null,
                        firstPos,
                        SoundEvents.IRON_TRAPDOOR_OPEN,
                        SoundSource.BLOCKS,
                        0.8F,
                        0.8F
                );

                level.playSound(
                        null,
                        clickedPos,
                        SoundEvents.IRON_TRAPDOOR_OPEN,
                        SoundSource.BLOCKS,
                        0.8F,
                        0.8F
                );

                if (player != null && !player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            } else if (player != null) {
                player.displayClientMessage(
                        Component.translatable("carwinch.coupling.failed"),
                        true
                );
            }
        } else {
            clearSelection(stack);
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(
            final ItemStack stack,
            final TooltipContext context,
            final List<Component> tooltip,
            final TooltipFlag flag
    ) {
        tooltip.add(
                Component.translatable("carwinch.coupling.tooltip")
        );
    }
}
