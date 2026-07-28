package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class TowbarRenderer extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    public TowbarRenderer(
            final BlockEntityRendererProvider.Context context
    ) {
    }

    @Override
    public boolean shouldRenderOffScreen(final TowbarBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(
            final TowbarBlockEntity be,
            final Vec3 cameraPos
    ) {
        return true;
    }

    @Override
    protected void renderSafe(
            final TowbarBlockEntity be,
            final float partialTicks,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int light,
            final int overlay
    ) {
        final Level level = be.getLevel();

        if (level == null) {
            return;
        }

        /*
         * Рендерим сцепку только со стороны владельца,
         * чтобы она не рисовалась дважды.
         */
        if (!be.isCouplingOwner() || be.getCouplingTarget() == null) {
            return;
        }

        if (!(level.getBlockEntity(be.getCouplingTarget())
                instanceof TowbarBlockEntity target)) {
            return;
        }

        final Vec3 start = be.getAttachmentPoint(
                be.getBlockPos(),
                be.getBlockState()
        );

        final Vec3 end = target.getAttachmentPoint(
                target.getBlockPos(),
                target.getBlockState()
        );

        final Vec3 delta = end.subtract(start);
        final double length = delta.length();

        if (length < 0.05 || length > TowbarBlockEntity.MAX_COUPLING_DISTANCE + 0.25) {
            return;
        }

        final Vector3f direction = new Vector3f(
                (float) (delta.x / length),
                (float) (delta.y / length),
                (float) (delta.z / length)
        );

        final Quaternionf rotation =
                new Quaternionf().rotationTo(
                        new Vector3f(0.0F, 1.0F, 0.0F),
                        direction
                );

        final BlockPos lightPos = BlockPos.containing(
                start.x,
                start.y,
                start.z
        );

        final int shaftLight =
                LevelRenderer.getLightColor(level, lightPos);

        /*
         * Slim-сборка Create не тащит Registrate, поэтому AllBlocks.BlockEntry
         * недоступен. Для источника BlockState берём ванильный блок.
         */
        final SuperByteBuffer shaft =
                CachedBuffers.partialFacing(
                        AllPartialModels.SHAFT,
                        Blocks.IRON_BLOCK.defaultBlockState(),
                        Direction.UP
                );

        poseStack.pushPose();

        poseStack.translate(
                start.x - be.getBlockPos().getX(),
                start.y - be.getBlockPos().getY(),
                start.z - be.getBlockPos().getZ()
        );

        poseStack.mulPose(rotation);
        poseStack.translate(-0.5, 0.0, -0.5);
        poseStack.scale(0.22F, (float) length, 0.22F);

        shaft.light(shaftLight).renderInto(
                poseStack,
                buffer.getBuffer(RenderType.solid())
        );

        poseStack.popPose();
    }
}
