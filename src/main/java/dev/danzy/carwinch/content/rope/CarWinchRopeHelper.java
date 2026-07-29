package dev.danzy.carwinch.content.rope;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.danzy.carwinch.registry.CWItems;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachment;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Общая логика троса для лебёдки и фаркопа.
 * <p>
 * Зачем нужен: Simulated сам решает, что вернуть игроку при разрушении
 * троса, и возвращает свою верёвку (SimItems.ROPE_COUPLING). Нам нужен
 * свой стальной трос, поэтому рвём трос с returnItem = false и выдаём
 * предмет сами.
 */
public final class CarWinchRopeHelper {

    private CarWinchRopeHelper() {
    }

    /**
     * Обычная верёвка Simulated (и любые её варианты).
     * Проверяем класс предмета, а не его id: так не развалится, если
     * Simulated переименует или добавит ещё одну верёвку.
     */
    public static boolean isPlainRope(final ItemStack stack) {
        return stack.getItem() instanceof RopeItem;
    }

    /** Rope holder на позиции, либо null. */
    @Nullable
    public static RopeStrandHolderBehavior holderAt(
            @Nullable final Level level,
            @Nullable final BlockPos pos
    ) {
        if (level == null || pos == null) {
            return null;
        }

        if (!(level.getBlockEntity(pos) instanceof final SmartBlockEntity smartBlockEntity)) {
            return null;
        }

        return smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
    }

    /**
     * Владелец троса. Рвать трос умеет только владелец (у нас это всегда
     * лебёдка), поэтому при клике по фаркопу надо найти второй конец.
     */
    @Nullable
    private static RopeStrandHolderBehavior ownerOf(
            final Level level,
            final RopeStrandHolderBehavior holder
    ) {
        if (holder.ownsRope()) {
            return holder;
        }

        final ServerRopeStrand strand = holder.getAttachedStrand();
        if (strand == null) {
            return null;
        }

        final RopeAttachment start = strand.getAttachment(RopeAttachmentPoint.START);
        if (start == null) {
            return null;
        }

        return holderAt(level, start.blockAttachment());
    }

    /**
     * Снимает трос и выдаёт именно стальной трос.
     *
     * @param player игрок, если трос режут инструментом; null при сломе блока
     * @return true, если трос был и его сняли
     */
    public static boolean destroyRopeAndDropSteel(
            final Level level,
            final BlockPos pos,
            @Nullable final ServerPlayer player
    ) {
        if (level.isClientSide()) {
            return false;
        }

        final RopeStrandHolderBehavior holder = holderAt(level, pos);
        if (holder == null || !holder.isAttached()) {
            return false;
        }

        final RopeStrandHolderBehavior owner = ownerOf(level, holder);
        if (owner == null) {
            return false;
        }

        // returnItem = false: иначе Simulated вернёт свою верёвку.
        owner.destroyRope(player, pos.getCenter(), false);

        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return true;
        }

        if (player != null && player.hasInfiniteMaterials()) {
            return true;
        }

        final ItemStack drop = new ItemStack(CWItems.IRON_ROPE.get());

        if (player != null) {
            player.getInventory().placeItemBackInInventory(drop);
            return true;
        }

        final Vec3 center = pos.getCenter();
        level.addFreshEntity(new ItemEntity(level, center.x, center.y, center.z, drop));

        return true;
    }
}
