package dev.danzy.carwinch.content.item;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.content.winch.CarWinchBlockEntity;
import dev.danzy.carwinch.registry.CWDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Стальной трос.
 *
 * Первый клик выбирает лебёдку или буксир.
 * Второй клик соединяет их через Simulated.
 *
 * Поддерживает:
 * обычный мир -> обычный мир;
 * sub-level -> обычный мир;
 * обычный мир -> sub-level;
 * sub-level -> sub-level.
 *
 * Shift + ПКМ очищает первый выбор.
 */
public class IronRopeItem extends Item {

    public IronRopeItem(final Properties properties) {
        super(properties);
    }

    /**
     * Возвращает holder по конкретному уровню и позиции.
     */
    @Nullable
    public static RopeStrandHolderBehavior getRopeHolder(
            @Nullable final Level level,
            @Nullable final BlockPos pos
    ) {
        if (level == null || pos == null) {
            return null;
        }

        final BlockEntity blockEntity =
                level.getBlockEntity(pos);

        if (!(blockEntity instanceof SmartBlockEntity smartBlockEntity)) {
            return null;
        }

        return smartBlockEntity.getBehaviour(
                RopeStrandHolderBehavior.TYPE
        );
    }

    private static boolean isWinch(
            @Nullable final RopeStrandHolderBehavior holder
    ) {
        return holder != null
                && holder.blockEntity instanceof CarWinchBlockEntity;
    }

    private static boolean isTowbar(
            @Nullable final RopeStrandHolderBehavior holder
    ) {
        return holder != null
                && holder.blockEntity instanceof TowbarBlockEntity;
    }

    /**
     * Возвращает контейнер sub-level для данного уровня.
     */
    @Nullable
    private static SubLevelContainer getContainer(
            @Nullable final Level level
    ) {
        if (level == null) {
            return null;
        }

        return SubLevelContainer.getContainer(level);
    }

    /**
     * Находит уровень первого конца.
     *
     * Если UUID sub-level отсутствует, используется обычный мир.
     */
    @Nullable
    private static Level resolveFirstLevel(
            final Level currentLevel,
            @Nullable final UUID subLevelId
    ) {
        if (subLevelId == null) {
            return currentLevel;
        }

        final SubLevelContainer container =
                getContainer(currentLevel);

        if (container == null) {
            return null;
        }

        final SubLevel subLevel =
                container.getSubLevel(subLevelId);

        return SubLevelContainer.getContainer(subLevel);
    }

    /**
     * Определяет sub-level для выбранного блока.
     */
    @Nullable
    private static UUID findSubLevelId(
            final Level level,
            final RopeStrandHolderBehavior holder
    ) {
        final Vec3Attachment attachment =
                new Vec3Attachment(holder.getAttachmentPoint());

        final SubLevel subLevel =
                Sable.HELPER.getContaining(
                        level,
                        attachment.position()
                );

        return subLevel == null
                ? null
                : subLevel.getUniqueId();
    }

    @Override
    public InteractionResult useOn(
            final UseOnContext context
    ) {
        final Level currentLevel =
                context.getLevel();

        final BlockPos clickedPos =
                context.getClickedPos();

        final ItemStack stack =
                context.getItemInHand();

        final Player player =
                context.getPlayer();

        /*
         * Shift + ПКМ очищает оба компонента.
         */
        if (player != null && player.isShiftKeyDown()) {
            clearSelection(stack);

            if (currentLevel.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable(
                                "carwinch.rope.cleared"
                        ),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        /*
         * Находим holder второго конца в текущем уровне.
         */
        final RopeStrandHolderBehavior clicked =
                getRopeHolder(
                        currentLevel,
                        clickedPos
                );

        if (!isWinch(clicked) && !isTowbar(clicked)) {
            return super.useOn(context);
        }

        if (clicked.isAttached()) {
            if (currentLevel.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable(
                                "carwinch.rope.occupied"
                        ),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        final BlockPos firstPos =
                stack.get(
                        CWDataComponents.FIRST_CONNECTION.get()
                );

        final UUID firstSubLevelId =
                stack.get(
                        CWDataComponents.FIRST_CONNECTION_SUBLEVEL.get()
                );

        /*
         * Первый клик.
         */
        if (firstPos == null) {
            final UUID clickedSubLevelId =
                    findSubLevelId(
                            currentLevel,
                            clicked
                    );

            stack.set(
                    CWDataComponents.FIRST_CONNECTION.get(),
                    clickedPos
            );

            if (clickedSubLevelId != null) {
                stack.set(
                        CWDataComponents
                                .FIRST_CONNECTION_SUBLEVEL
                                .get(),
                        clickedSubLevelId
                );
            } else {
                stack.remove(
                        CWDataComponents
                                .FIRST_CONNECTION_SUBLEVEL
                                .get()
                );
            }

            if (currentLevel.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable(
                                "carwinch.rope.selected"
                        ),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        /*
         * Повторный клик по той же точке отменяет выбор.
         */
        if (firstPos.equals(clickedPos)
                && java.util.Objects.equals(
                        firstSubLevelId,
                        findSubLevelId(
                                currentLevel,
                                clicked
                        )
                )) {
            clearSelection(stack);

            if (currentLevel.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable(
                                "carwinch.rope.cleared"
                        ),
                        true
                );
            }

            return InteractionResult.SUCCESS;
        }

        /*
         * На сервере разрешаем первый уровень и создаём трос.
         */
        if (!currentLevel.isClientSide) {
            final Level firstLevel =
                    resolveFirstLevel(
                            currentLevel,
                            firstSubLevelId
                    );

            final boolean dropItem =
                    player == null
                            || !player.hasInfiniteMaterials();

            final boolean hitched =
                    firstLevel != null
                            && hitch(
                                    firstLevel,
                                    firstPos,
                                    currentLevel,
                                    clickedPos,
                                    dropItem
                            );

            clearSelection(stack);

            if (hitched) {
                if (player != null
                        && !player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            } else if (player != null) {
                player.displayClientMessage(
                        Component.translatable(
                                "carwinch.rope.failed"
                        ),
                        true
                );
            }
        } else {
            clearSelection(stack);
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Очищает позицию и UUID sub-level.
     */
    private static void clearSelection(
            final ItemStack stack
    ) {
        stack.remove(
                CWDataComponents.FIRST_CONNECTION.get()
        );

        stack.remove(
                CWDataComponents.FIRST_CONNECTION_SUBLEVEL.get()
        );
    }

    /**
     * Создаёт трос между двумя уровнями.
     *
     * Simulated внутри createRope() сам:
     * - переводит точки из sub-level;
     * - вычисляет мировую дистанцию;
     * - создаёт RopeAttachment;
     * - сохраняет UUID sub-level у attachment.
     */
    private boolean hitch(
            final Level firstLevel,
            final BlockPos firstPos,
            final Level secondLevel,
            final BlockPos secondPos,
            final boolean dropItem
    ) {
        final RopeStrandHolderBehavior first =
                getRopeHolder(
                        firstLevel,
                        firstPos
                );

        final RopeStrandHolderBehavior second =
                getRopeHolder(
                        secondLevel,
                        secondPos
                );

        if (first == null || second == null) {
            return false;
        }

        if (first.isAttached() || second.isAttached()) {
            return false;
        }

        final boolean firstIsWinch =
                isWinch(first);

        final boolean secondIsWinch =
                isWinch(second);

        final boolean firstIsTowbar =
                isTowbar(first);

        final boolean secondIsTowbar =
                isTowbar(second);

        /*
         * Разрешена только пара:
         * winch + towbar.
         */
        if (!(
                (firstIsWinch && secondIsTowbar)
                        || (firstIsTowbar && secondIsWinch)
        )) {
            return false;
        }

        final RopeStrandHolderBehavior winch;
        final RopeStrandHolderBehavior towbar;

        if (firstIsWinch) {
            winch = first;
            towbar = second;
        } else {
            winch = second;
            towbar = first;
        }

        /*
         * Важно:
         * createRope() вызывается на holder лебёдки.
         * Simulated использует его уровень и знает оба sub-level.
         */
        if (!winch.createRope(towbar)) {
            return false;
        }

        /*
         * Звук играем в обоих уровнях, если это разные Level.
         */
        firstLevel.playSound(
                null,
                firstPos,
                SoundEvents.CHAIN_PLACE,
                SoundSource.BLOCKS,
                0.7F,
                0.8F
        );

        if (secondLevel != firstLevel) {
            secondLevel.playSound(
                    null,
                    secondPos,
                    SoundEvents.CHAIN_PLACE,
                    SoundSource.BLOCKS,
                    0.7F,
                    0.8F
            );
        } else {
            secondLevel.playSound(
                    null,
                    secondPos,
                    SoundEvents.CHAIN_PLACE,
                    SoundSource.BLOCKS,
                    0.7F,
                    0.8F
            );
        }

        return true;
    }

    @Override
    public void appendHoverText(
            final ItemStack stack,
            final TooltipContext context,
            final List<Component> tooltip,
            final TooltipFlag flag
    ) {
        tooltip.add(
                Component.translatable(
                        "carwinch.rope.tooltip"
                ).withStyle(
                        net.minecraft.ChatFormatting.GRAY
                )
        );

        final BlockPos firstPos =
                stack.get(
                        CWDataComponents.FIRST_CONNECTION.get()
                );

        final UUID subLevelId =
                stack.get(
                        CWDataComponents
                                .FIRST_CONNECTION_SUBLEVEL
                                .get()
                );

        if (firstPos != null) {
            tooltip.add(
                    Component.translatable(
                            "carwinch.rope.tooltip.anchored",
                            firstPos.getX(),
                            firstPos.getY(),
                            firstPos.getZ()
                    ).withStyle(
                            net.minecraft.ChatFormatting.GOLD
                    )
            );
        }

        if (subLevelId != null) {
            tooltip.add(
                    Component.literal(
                            "Sub-level: " + subLevelId
                    ).withStyle(
                            net.minecraft.ChatFormatting.DARK_AQUA
                    )
            );
        }
    }

    /**
     * Маленькая обёртка, чтобы не использовать Vec3
     * в сигнатурах вспомогательного метода.
     */
    private record Vec3Attachment(
            net.minecraft.world.phys.Vec3 position
    ) {
    }
}
