package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.ryanhcode.sable.Sable;
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

public class TowbarRenderer
        extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    public TowbarRenderer(
            final BlockEntityRendererProvider.Context context
    ) {
    }

    @Override
    public boolean shouldRenderOffScreen(
            final TowbarBlockEntity blockEntity
    ) {
        return true;
    }

    @Override
    public boolean shouldRender(
            final TowbarBlockEntity blockEntity,
            final Vec3 cameraPos
    ) {
        return true;
    }

    @Override
    protected void renderSafe(
            final TowbarBlockEntity blockEntity,
            final float partialTicks,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int light,
            final int overlay
    ) {
        final Level level = blockEntity.getLevel();

        if (level == null
                || !blockEntity.isCouplingOwner()
                || blockEntity.getCouplingTarget() == null) {
            return;
        }

        final TowbarBlockEntity target =
                getTarget(
                        level,
                        blockEntity.getCouplingTarget()
                );

        if (target == null) {
            return;
        }

        final double fixedLength =
                blockEntity.getCouplingLength();

        if (fixedLength <= 0.0D) {
            return;
        }

        final Vec3 localStart =
                blockEntity.getAttachmentPoint();

        final Vec3 localEnd =
                target.getAttachmentPoint();

        final Vec3 start =
                Sable.HELPER.projectOutOfSubLevel(
                        level,
                        localStart
                );

        final Vec3 end =
                Sable.HELPER.projectOutOfSubLevel(
                        level,
                        localEnd
                );

        final Vec3 delta = end.subtract(start);

        if (delta.lengthSqr() < 0.0001D) {
            return;
        }

        /*
         * Направление берём от фактического положения фаркопов,
         * а длину только из сохранённого couplingLength.
         */
        final Vector3f direction =
                new Vector3f(
                        (float) delta.x,
                        (float) delta.y,
                        (float) delta.z
                ).normalize();

        final Quaternionf rotation =
                new Quaternionf().rotationTo(
                        new Vector3f(0.0F, 1.0F, 0.0F),
                        direction
                );

        final SuperByteBuffer shaft =
                CachedBuffers.partialFacing(
                        AllPartialModels.SHAFT,
                        Blocks.IRON_BLOCK.defaultBlockState(),
                        Direction.UP
                );

        final BlockPos lightPos =
                BlockPos.containing(
                        start.x,
                        start.y,
                        start.z
                );

        final int shaftLight =
                LevelRenderer.getLightColor(
                        level,
                        lightPos
                );

        poseStack.pushPose();

        poseStack.translate(
                start.x - blockEntity.getBlockPos().getX(),
                start.y - blockEntity.getBlockPos().getY(),
                start.z - blockEntity.getBlockPos().getZ()
        );

        poseStack.mulPose(rotation);
        poseStack.translate(-0.5D, 0.0D, -0.5D);

        /*
         * Поперечник постоянный, длина зафиксирована
         * в момент соединения.
         */
        poseStack.scale(
                0.22F,
                (float) fixedLength,
                0.22F
        );

        shaft.light(shaftLight).renderInto(
                poseStack,
                buffer.getBuffer(RenderType.solid())
        );

        poseStack.popPose();
    }

    private static TowbarBlockEntity getTarget(
            final Level level,
            final BlockPos pos
    ) {
        return level.getBlockEntity(pos)
                instanceof TowbarBlockEntity towbar
                ? towbar
                : null;
    }
}
