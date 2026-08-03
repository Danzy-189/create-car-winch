package dev.danzy.carwinch.content.rope;

import dev.danzy.carwinch.CarWinch;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Перехват чужих способов снять наш трос.
 * <p>
 * Проблема: трос всегда принадлежит лебёдке, но разорвать его можно и со
 * второго конца - ножницами по rope connector или сломав сам коннектор.
 * В обоих случаях внутрь уходит код Simulated, который возвращает свою
 * верёвку вместо стального троса.
 * <p>
 * Решение: не переписывать чужой код, а опередить его. Если трос на этой
 * позиции принадлежит нашей лебёдке, снимаем его своим способом (с выдачей
 * стального троса) и гасим взаимодействие. Дальше Simulated уже нечего рвать,
 * поэтому и лишней верёвки не появится.
 */
@EventBusSubscriber(modid = CarWinch.ID)
public final class RopeInterceptEvents {

    private RopeInterceptEvents() {
    }

    /**
     * Ножницы (и всё, что в теге DESTROYS_ROPE) по любому концу троса.
     */
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        final Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }

        final ItemStack stack = event.getItemStack();
        if (!stack.is(SimTags.Items.DESTROYS_ROPE)) {
            return;
        }

        if (!(event.getEntity() instanceof final ServerPlayer player)) {
            return;
        }

        final BlockPos pos = event.getPos();
        if (!CarWinchRopeHelper.isRopeOwnedByWinch(level, pos)) {
            return;
        }

        if (CarWinchRopeHelper.destroyRopeAndDropSteel(level, pos, player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * Слом любого блока, к которому привязан наш трос: коннектор, фаркоп,
     * сама лебёдка. Событие не отменяем - блок ломается как обычно, просто
     * трос снимаем заранее и сами.
     */
    @SubscribeEvent
    public static void onBlockBreak(final BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof final Level level) || level.isClientSide()) {
            return;
        }

        final BlockPos pos = event.getPos();
        if (!CarWinchRopeHelper.isRopeOwnedByWinch(level, pos)) {
            return;
        }

        final Player player = event.getPlayer();
        final ServerPlayer serverPlayer = player instanceof final ServerPlayer casted ? casted : null;

        CarWinchRopeHelper.destroyRopeAndDropSteel(level, pos, serverPlayer);
    }
}
