package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

public class TowbarRenderer extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    public TowbarRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(final TowbarBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(final TowbarBlockEntity be, final Vec3 cameraPos) {
        return true;
    }

    @Override
    protected void renderSafe(final TowbarBlockEntity be, final float partialTicks, final PoseStack ms,
                              final MultiBufferSource buffer, final int light, final int overlay) {
        // the towbar never owns the strand, but rendering here keeps the rope visible
        // when only the towbar end is in view
        RopeStrandRenderer.render(be, be.getRopeHolder(), partialTicks, ms, buffer);
    }
}
