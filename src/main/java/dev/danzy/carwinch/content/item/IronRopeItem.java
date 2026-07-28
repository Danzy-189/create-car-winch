package dev.danzy.carwinch.content.item;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.content.winch.CarWinchBlockEntity;
import dev.danzy.carwinch.registry.CWDataComponents;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Steel rope. Right click a winch, then right click a tow bar to hitch them together.
 * Sneak + right click clears the pending selection.
 */
public class IronRopeItem extends Item {

    public IronRopeItem(final Properties properties) {
        super(properties);
    }

    @Nullable
    public static RopeStrandHolderBehavior getRopeHolder(final Level level, final BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof final SmartBlockEntity smart) {
            return smart.getBehaviour(RopeStrandHolderBehavior.TYPE);
        }
        return null;
    }

    private static boolean isWinch(final RopeStrandHolderBehavior holder) {
        return holder.blockEntity instanceof CarWinchBlockEntity;
    }

    private static boolean isTowbar(final RopeStrandHolderBehavior holder) {
        return holder.blockEntity instanceof TowbarBlockEntity;
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos clickedPos = context.getClickedPos();
        final ItemStack stack = context.getItemInHand();
        final Player player = context.getPlayer();

        if (player != null && player.isShiftKeyDown()) {
            stack.remove(CWDataComponents.FIRST_CONNECTION.get());
            if (level.isClientSide) {
                player.displayClientMessage(Component.translatable("carwinch.rope.cleared"), true);
            }
            return InteractionResult.SUCCESS;
        }

        final RopeStrandHolderBehavior clicked = getRopeHolder(level, clickedPos);
        if (clicked == null || (!isWinch(clicked) && !isTowbar(clicked))) {
            return super.useOn(context);
        }

        if (clicked.isAttached()) {
            if (level.isClientSide && player != null) {
                player.displayClientMessage(Component.translatable("carwinch.rope.occupied"), true);
            }
            return InteractionResult.SUCCESS;
        }

        final BlockPos first = stack.get(CWDataComponents.FIRST_CONNECTION.get());

        if (first == null) {
            stack.set(CWDataComponents.FIRST_CONNECTION.get(), clickedPos);
            if (level.isClientSide && player != null) {
                player.displayClientMessage(Component.translatable("carwinch.rope.selected"), true);
            }
            return InteractionResult.SUCCESS;
        }

        if (first.equals(clickedPos)) {
            stack.remove(CWDataComponents.FIRST_CONNECTION.get());
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide) {
            final boolean hitched = this.hitch(level, first, clickedPos, player == null || !player.hasInfiniteMaterials());
            stack.remove(CWDataComponents.FIRST_CONNECTION.get());

            if (hitched) {
                if (player != null && !player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            } else if (player != null) {
                player.displayClientMessage(Component.translatable("carwinch.rope.failed"), true);
            }
        } else {
            stack.remove(CWDataComponents.FIRST_CONNECTION.get());
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Creates the strand. The winch always owns the rope so it is the side that reels it in.
     */
    private boolean hitch(final Level level, final BlockPos posA, final BlockPos posB, final boolean dropItem) {
        RopeStrandHolderBehavior a = getRopeHolder(level, posA);
        RopeStrandHolderBehavior b = getRopeHolder(level, posB);

        if (a == null || b == null) {
            return false;
        }

        // make sure the winch is the owner
        if (isWinch(b) && !isWinch(a)) {
            final RopeStrandHolderBehavior temp = a;
            a = b;
            b = temp;
        }

        if (!isWinch(a) || !isTowbar(b)) {
            return false;
        }

        final Vec3 attachmentA =
        a.getAttachmentPoint();

        final Vec3 attachmentB =
        b.getAttachmentPoint();

        if (attachmentA.distanceTo(attachmentB)
        > CarWinchBlockEntity.MAX_RANGE) {
        return false;
        }
        
        if (a.createRope(b)) {
            level.playSound(null, posA, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
            level.playSound(null, posB, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
            return true;
        }
        return false;
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext context,
                                final List<Component> tooltip, final TooltipFlag flag) {
        tooltip.add(Component.translatable("carwinch.rope.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
        final BlockPos first = stack.get(CWDataComponents.FIRST_CONNECTION.get());
        if (first != null) {
            tooltip.add(Component.translatable("carwinch.rope.tooltip.anchored",
                    first.getX(), first.getY(), first.getZ()).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }
}
