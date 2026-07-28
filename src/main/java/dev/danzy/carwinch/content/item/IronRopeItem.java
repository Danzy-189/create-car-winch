package dev.danzy.carwinch.content.item;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.content.winch.CarWinchBlockEntity;
import dev.danzy.carwinch.registry.CWDataComponents;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
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
 * Стальной трос.
 *
 * Первый клик выбирает лебёдку или буксир, второй соединяет их через Simulated.
 * Shift + ПКМ очищает выбор.
 *
 * Про sub-level'ы: Sable не создаёт для них отдельные {@link Level}. Sub-level - это
 * LevelPlot внутри того же самого уровня, просто с другой позой. Поэтому обычного
 * level.getBlockEntity(pos) достаточно и для блоков на собранной конструкции.
 * Перевод точек в мировые координаты Simulated делает сам внутри createRope().
 */
public class IronRopeItem extends Item {

    public IronRopeItem(final Properties properties) {
        super(properties);
    }

    /** Возвращает rope holder по позиции, либо null если там не наш блок. */
    @Nullable
    public static RopeStrandHolderBehavior getRopeHolder(@Nullable final Level level,
                                                        @Nullable final BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }

        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof final SmartBlockEntity smartBlockEntity)) {
            return null;
        }

        return smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
    }

    private static boolean isWinch(@Nullable final RopeStrandHolderBehavior holder) {
        return holder != null && holder.blockEntity instanceof CarWinchBlockEntity;
    }

    private static boolean isTowbar(@Nullable final RopeStrandHolderBehavior holder) {
        return holder != null && holder.blockEntity instanceof TowbarBlockEntity;
    }

    private static void clearSelection(final ItemStack stack) {
        stack.remove(CWDataComponents.FIRST_CONNECTION.get());
    }

    private static void notify(final Level level, @Nullable final Player player, final String key) {
        if (level.isClientSide && player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos clickedPos = context.getClickedPos();
        final ItemStack stack = context.getItemInHand();
        final Player player = context.getPlayer();

        // Shift + ПКМ - сброс выбора.
        if (player != null && player.isShiftKeyDown()) {
            clearSelection(stack);
            notify(level, player, "carwinch.rope.cleared");
            return InteractionResult.SUCCESS;
        }

        final RopeStrandHolderBehavior clicked = getRopeHolder(level, clickedPos);
        if (!isWinch(clicked) && !isTowbar(clicked)) {
            return super.useOn(context);
        }

        if (clicked.isAttached()) {
            notify(level, player, "carwinch.rope.occupied");
            return InteractionResult.SUCCESS;
        }

        final BlockPos firstPos = stack.get(CWDataComponents.FIRST_CONNECTION.get());

        // Первый клик - запоминаем якорь.
        if (firstPos == null) {
            stack.set(CWDataComponents.FIRST_CONNECTION.get(), clickedPos);
            notify(level, player, "carwinch.rope.selected");
            return InteractionResult.SUCCESS;
        }

        // Повторный клик по той же точке - отмена.
        if (firstPos.equals(clickedPos)) {
            clearSelection(stack);
            notify(level, player, "carwinch.rope.cleared");
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide) {
            clearSelection(stack);
            return InteractionResult.SUCCESS;
        }

        final boolean hitched = this.hitch(level, firstPos, clickedPos);
        clearSelection(stack);

        if (hitched) {
            if (player != null && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
        } else if (player != null) {
            player.displayClientMessage(Component.translatable("carwinch.rope.failed"), true);
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Создаёт трос. Владельцем всегда становится лебёдка - именно она наматывает.
     */
    private boolean hitch(final Level level, final BlockPos posA, final BlockPos posB) {
        final RopeStrandHolderBehavior a = getRopeHolder(level, posA);
        final RopeStrandHolderBehavior b = getRopeHolder(level, posB);

        if (a == null || b == null || a.isAttached() || b.isAttached()) {
            return false;
        }

        final RopeStrandHolderBehavior winch;
        final RopeStrandHolderBehavior towbar;

        if (isWinch(a) && isTowbar(b)) {
            winch = a;
            towbar = b;
        } else if (isTowbar(a) && isWinch(b)) {
            winch = b;
            towbar = a;
        } else {
            return false;
        }

        if (!winch.createRope(towbar)) {
            return false;
        }

        level.playSound(null, posA, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
        level.playSound(null, posB, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
        return true;
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext context,
                               final List<Component> tooltip, final TooltipFlag flag) {
        tooltip.add(Component.translatable("carwinch.rope.tooltip").withStyle(ChatFormatting.GRAY));

        final BlockPos firstPos = stack.get(CWDataComponents.FIRST_CONNECTION.get());
        if (firstPos != null) {
            tooltip.add(Component.translatable("carwinch.rope.tooltip.anchored",
                    firstPos.getX(), firstPos.getY(), firstPos.getZ()).withStyle(ChatFormatting.GOLD));
        }
    }
}
