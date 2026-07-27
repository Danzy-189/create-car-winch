package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CarWinch.ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.carwinch"))
                    .icon(() -> new ItemStack(CWItems.WINCH.get()))
                    .displayItems((params, output) -> {
                        output.accept(CWItems.WINCH.get());
                        output.accept(CWItems.TOWBAR.get());
                        output.accept(CWItems.IRON_ROPE.get());
                    })
                    .build());

    private CWTabs() {
    }
}
