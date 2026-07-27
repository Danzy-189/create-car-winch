package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(CarWinch.ID);

    /** Stores the first anchor the player clicked with the steel rope. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> FIRST_CONNECTION =
            DATA_COMPONENTS.registerComponentType("first_connection", builder -> builder
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC));

    private CWDataComponents() {
    }
}
