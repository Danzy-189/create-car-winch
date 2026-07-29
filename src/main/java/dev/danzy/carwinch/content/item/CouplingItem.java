package dev.danzy.carwinch.content.item;

import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.registry.CWDataComponents;
import net.minecraft.ChatFormatting;
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

/**
 * Сцепка: жёсткий горизонтальный вал Create между двумя фаркопами.
 *
 * Первый клик выбирает фаркоп, второй ставит вал. Shift + ПКМ сбрасывает выбор.
 * Длина замеряется в момент соединения и дальше держится жёстко.
 * Вся логика серверная, поэтому дата-компонент не расходится с клиентом,
 * а причина отказа всегда показывается игроку.
 */
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

    private static void notify(@Nullable final Player player, @Nullable final String key) {
        if (player != null && key != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos clickedPos = context.getClickedPos();
        final ItemStack stack = context.getItemInHand();
        final Player player = context.getPlayer();

        final boolean sneaking = player != null && player.isShiftKeyDown();
        final TowbarBlockEntity clicked = getTowbar(level, clickedPos);

        if (!sneaking && clicked == null) {
            return super.useOn(context);
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (sneaking) {
            clearSelection(stack);
            notify(player, "carwinch.coupling.cleared");
            return InteractionResult.SUCCESS;
        }

        if (clicked.isCoupled()) {
            notify(player, "carwinch.coupling.occupied");
            return InteractionResult.SUCCESS;
        }

        final BlockPos firstPos =
                stack.get(CWDataComponents.COUPLING_FIRST_CONNECTION.get());

        if (firstPos == null) {
            stack.set(
                    CWDataComponents.COUPLING_FIRST_CONNECTION.get(),
                    clickedPos
            );

            notify(player, "carwinch.coupling.selected");
            return InteractionResult.SUCCESS;
        }

        if (firstPos.equals(clickedPos)) {
            clearSelection(stack);
            notify(player, "carwinch.coupling.cleared");
            return InteractionResult.SUCCESS;
        }

        final TowbarBlockEntity first = getTowbar(level, firstPos);

        clearSelection(stack);

        if (first == null) {
            notify(player, "carwinch.coupling.failed");
            return InteractionResult.SUCCESS;
        }

        final TowbarBlockEntity.CouplingResult result =
                TowbarBlockEntity.createCoupling(first, clicked);

        if (!result.isSuccess()) {
            notify(player, result.translationKey());
            return InteractionResult.SUCCESS;
        }

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
                        .withStyle(ChatFormatting.GRAY)
        );

        tooltip.add(
                Component.translatable(
                        "carwinch.coupling.tooltip.length",
                        String.valueOf(TowbarBlockEntity.MAX_COUPLING_LENGTH)
                ).withStyle(ChatFormatting.DARK_GRAY)
        );

        final BlockPos firstPos =
                stack.get(CWDataComponents.COUPLING_FIRST_CONNECTION.get());

        if (firstPos != null) {
            tooltip.add(
                    Component.translatable(
                            "carwinch.coupling.tooltip.anchored",
                            firstPos.getX(),
                            firstPos.getY(),
                            firstPos.getZ()
                    ).withStyle(ChatFormatting.GOLD)
            );
        }
    }
}
