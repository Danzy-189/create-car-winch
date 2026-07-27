package dev.danzy.carwinch;

import dev.danzy.carwinch.registry.CWBlockEntities;
import dev.danzy.carwinch.registry.CWBlocks;
import dev.danzy.carwinch.registry.CWDataComponents;
import dev.danzy.carwinch.registry.CWItems;
import dev.danzy.carwinch.registry.CWTabs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(CarWinch.ID)
public class CarWinch {
    public static final String ID = "carwinch";

    public CarWinch(final IEventBus modBus, final ModContainer container) {
        CWBlocks.BLOCKS.register(modBus);
        CWItems.ITEMS.register(modBus);
        CWBlockEntities.BLOCK_ENTITIES.register(modBus);
        CWDataComponents.DATA_COMPONENTS.register(modBus);
        CWTabs.TABS.register(modBus);
    }

    public static ResourceLocation asResource(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
