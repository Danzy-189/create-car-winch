package dev.danzy.carwinch.content.winch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.danzy.carwinch.client.CarWinchRopeRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

public class CarWinchRenderer
        extends SafeBlockEntityRenderer {

    public CarWinchRenderer(
            final BlockEntityRendererProvider.Context context
    ) {
    }

    @Override
    public boolean shouldRenderOffScreen(
            final CarWinchBlockEntity blockEntity
    ) {
        return true;
    }

    @Override
    public boolean shouldRender(
            final CarWinchBlockEntity blockEntity,
            final Vec3 cameraPosition
    ) {
        return true;
    }

    @Override
    protected void renderSafe(
            final CarWinchBlockEntity blockEntity,
            final float partialTicks,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int packedLight,
            final int packedOverlay
    ) {
        CarWinchRopeRenderer.render(
                blockEntity,
                blockEntity.getRopeHolder(),
                partialTicks,
                poseStack,
                buffer
        );
    }
}
