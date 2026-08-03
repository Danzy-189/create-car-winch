package dev.danzy.carwinch.content.item;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
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
 * Первый клик выбирает лебёдку, второй - вторую точку крепления.
 * Shift + ПКМ очищает выбор.
 *
 * Второй точкой может быть любой держатель троса Simulated: наш фаркоп,
 * rope connector, rope winch и всё, что реализует RopeStrandHolderBlockEntity.
 * Единственное жёсткое требование - один из двух концов должен быть нашей
 * лебёдкой: именно она владеет тросом, наматывает его и выдаёт стальной трос
 * обратно при снятии.
 *
 * Вся логика и все сообщения выполняются на сервере: дата-компонент с якорем
 * сам синхронизируется на клиент, поэтому раньше клиентская правка стека
 * могла разойтись с серверной.
 *
 * Про sub-level'ы: Sable не создаёт для них отдельные {@link Level}. Sub-level - это
 * LevelPlot внутри того же самого уровня, просто с другой позой. Поэтому обычного
 * level.getBlockEntity(pos) достаточно и для блоков на собранной конструкции.
 * Перевод точек в мировые координаты Simulated делает сам внутри createRope().
 */
public class IronRopeItem extends Item {

    private static final String ERROR_GENERIC = "carwinch.rope.failed";
    private static final String ERROR_NO_WINCH = "carwinch.rope.failed.no_winch";

    public IronRopeItem(final Properties properties) {
        super(properties);
    }

    /** Возвращает rope holder по позиции, либо null если там нет держателя троса. */
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

    private static void clearSelection(final ItemStack stack) {
        stack.remove(CWDataComponents.FIRST_CONNECTION.get());
    }

    private static void notify(@Nullable final Player player, final String key) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos clickedPos = context.getClickedPos();
        final ItemStack stack = context.getItemInHand();
        final Player player = context.getPlayer();

        final RopeStrandHolderBehavior clicked = getRopeHolder(level, clickedPos);
        final boolean sneaking = player != null && player.isShiftKeyDown();

        // Клик по блоку без держателя троса и без Shift обычному поведению не мешаем.
        if (!sneaking && clicked == null) {
            return super.useOn(context);
        }

        // Клиент только подтверждает взмах, всё решает сервер.
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Shift + ПКМ - сброс выбора.
        if (sneaking) {
            clearSelection(stack);
            notify(player, "carwinch.rope.cleared");
            return InteractionResult.SUCCESS;
        }

        if (clicked.isAttached()) {
            notify(player, "carwinch.rope.occupied");
            return InteractionResult.SUCCESS;
        }

        final BlockPos firstPos = stack.get(CWDataComponents.FIRST_CONNECTION.get());

        // Первый клик - запоминаем якорь.
        if (firstPos == null) {
            stack.set(CWDataComponents.FIRST_CONNECTION.get(), clickedPos);
            notify(player, isWinch(clicked)
                    ? "carwinch.rope.selected"
                    : "carwinch.rope.selected.other");
            return InteractionResult.SUCCESS;
        }

        // Повторный клик по той же точке - отмена.
        if (firstPos.equals(clickedPos)) {
            clearSelection(stack);
            notify(player, "carwinch.rope.cleared");
            return InteractionResult.SUCCESS;
        }

        final String error = this.hitch(level, firstPos, clickedPos);
        clearSelection(stack);

        if (error == null) {
            if (player != null && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
        } else {
            notify(player, error);
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Создаёт трос. Владельцем всегда становится лебёдка - именно она наматывает.
     *
     * @return null при успехе, иначе ключ сообщения об ошибке
     */
    @Nullable
    private String hitch(final Level level, final BlockPos posA, final BlockPos posB) {
        final RopeStrandHolderBehavior a = getRopeHolder(level, posA);
        final RopeStrandHolderBehavior b = getRopeHolder(level, posB);

        if (a == null || b == null || a == b || a.isAttached() || b.isAttached()) {
            return ERROR_GENERIC;
        }

        /*
         * Второй конец - любой держатель троса Simulated: фаркоп, rope connector,
         * rope winch. Проверяем только то, что лебёдка есть хотя бы с одной стороны.
         */
        final RopeStrandHolderBehavior winch;
        final RopeStrandHolderBehavior other;

        if (isWinch(a)) {
            winch = a;
            other = b;
        } else if (isWinch(b)) {
            winch = b;
            other = a;
        } else {
            return ERROR_NO_WINCH;
        }

        if (!winch.createRope(other, false)) {
            return ERROR_GENERIC;
        }

        level.playSound(null, posA, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
        level.playSound(null, posB, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
        return null;
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext context,
                               final List<Component> tooltip, final TooltipFlag flag) {
        tooltip.add(Component.translatable("carwinch.rope.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("carwinch.rope.tooltip.targets").withStyle(ChatFormatting.DARK_GRAY));

        final BlockPos firstPos = stack.get(CWDataComponents.FIRST_CONNECTION.get());
        if (firstPos != null) {
            tooltip.add(Component.translatable("carwinch.rope.tooltip.anchored",
                    firstPos.getX(), firstPos.getY(), firstPos.getZ()).withStyle(ChatFormatting.GOLD));
        }
    }
}
