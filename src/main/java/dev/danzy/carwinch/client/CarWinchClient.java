package dev.danzy.carwinch.client;
import dev.danzy.carwinch.client.CarWinchPartialModels;
import dev.danzy.carwinch.CarWinch;
import dev.danzy.carwinch.content.towbar.TowbarRenderer;
import dev.danzy.carwinch.content.winch.CarWinchRenderer;
import dev.danzy.carwinch.registry.CWBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = CarWinch.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CarWinchClient {
    
    @SubscribeEvent
public static void onRegisterRenderers(
        final EntityRenderersEvent.RegisterRenderers event
) {
    CarWinchPartialModels.init();

    event.registerBlockEntityRenderer(
            CWBlockEntities.WINCH.get(),
            CarWinchRenderer::new
    );

    event.registerBlockEntityRenderer(
            CWBlockEntities.TOWBAR.get(),
            TowbarRenderer::new
    );
}

    @SubscribeEvent
    public static void onRegisterRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CWBlockEntities.WINCH.get(), CarWinchRenderer::new);
        event.registerBlockEntityRenderer(CWBlockEntities.TOWBAR.get(), TowbarRenderer::new);
    }

    private CarWinchClient() {
    }
}
