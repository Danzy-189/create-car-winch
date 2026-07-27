package dev.danzy.carwinch.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.danzy.carwinch.CarWinch;
import dev.danzy.carwinch.content.item.IronRopeItem;
import dev.danzy.carwinch.registry.CWDataComponents;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The "connection interface" you get while holding the steel rope: highlights the anchor you
 * already picked, highlights the valid anchor you are looking at, and previews the rope run.
 */
@EventBusSubscriber(modid = CarWinch.ID, value = Dist.CLIENT)
public class IronRopeSelectionRenderer {

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof IronRopeItem)) {
            stack = player.getItemInHand(InteractionHand.OFF_HAND);
        }
        if (!(stack.getItem() instanceof IronRopeItem)) {
            return;
        }

        final BlockPos anchor = stack.get(CWDataComponents.FIRST_CONNECTION.get());
        final BlockPos looking = lookedAtAnchor(mc);

        if (anchor == null && looking == null) {
            return;
        }

        final Vec3 camera = event.getCamera().getPosition();
        final PoseStack ms = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        ms.pushPose();
        ms.translate(-camera.x, -camera.y, -camera.z);

        if (anchor != null) {
            box(ms, lines, anchor, 1.0F, 0.72F, 0.15F);
        }
        if (looking != null && !looking.equals(anchor)) {
            box(ms, lines, looking, 0.35F, 0.95F, 0.45F);
        }
        if (anchor != null && looking != null && !looking.equals(anchor)) {
            line(ms, lines, anchor.getCenter(), looking.getCenter());
        }

        ms.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static BlockPos lookedAtAnchor(final Minecraft mc) {
        final HitResult hit = mc.hitResult;
        if (!(hit instanceof final BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        final BlockPos pos = blockHit.getBlockPos();
        final RopeStrandHolderBehavior holder = IronRopeItem.getRopeHolder(mc.level, pos);
        return (holder != null && !holder.isAttached()) ? pos : null;
    }

    private static void box(final PoseStack ms, final VertexConsumer lines, final BlockPos pos,
                            final float r, final float g, final float b) {
        final AABB aabb = new AABB(pos).inflate(0.01);
        LevelRenderer.renderLineBox(ms, lines, aabb, r, g, b, 0.9F);
    }

    private static void line(final PoseStack ms, final VertexConsumer lines, final Vec3 from, final Vec3 to) {
        final PoseStack.Pose pose = ms.last();
        final Vec3 dir = to.subtract(from).normalize();
        lines.addVertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
                .setColor(1.0F, 0.85F, 0.35F, 0.9F)
                .setNormal(pose, (float) dir.x, (float) dir.y, (float) dir.z);
        lines.addVertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
                .setColor(1.0F, 0.85F, 0.35F, 0.9F)
                .setNormal(pose, (float) dir.x, (float) dir.y, (float) dir.z);
    }

    private IronRopeSelectionRenderer() {
    }
}
