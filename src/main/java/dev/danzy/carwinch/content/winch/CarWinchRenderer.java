package dev.danzy.carwinch.content.winch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.danzy.carwinch.client.CarWinchRopeRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

public class CarWinchRenderer extends SafeBlockEntityRenderer {

    public CarWinchRenderer(
            final BlockEntityRendererProvider.Context context
    ) {
    }

    @Override
    protected void renderSafe(
            final BlockEntity blockEntity,
            final float partialTicks,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int packedLight,
            final int packedOverlay
    ) {
        if (!(blockEntity instanceof CarWinchBlockEntity winch)) {
            return;
        }

        CarWinchRopeRenderer.render(
                winch,
                winch.getRopeHolder(),
                partialTicks,
                poseStack,
                buffer
        );
    }
}
