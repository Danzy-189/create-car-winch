package dev.danzy.carwinch.content.winch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

/** Draws the physics rope strand owned by the winch. */
public class CarWinchRenderer extends SafeBlockEntityRenderer<CarWinchBlockEntity> {

    public CarWinchRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(final CarWinchBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(final CarWinchBlockEntity be, final Vec3 cameraPos) {
        return true;
    }

    @Override
    protected void renderSafe(final CarWinchBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        RopeStrandRenderer.render(be, be.getRopeHolder(), partialTicks, ms, buffer);
    }
}
