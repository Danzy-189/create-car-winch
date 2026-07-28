package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

public final class CWDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(CarWinch.ID);

    /**
     * Позиция первого выбранного конца.
     */
    public static final DeferredHolder<
            DataComponentType<?>,
            DataComponentType<BlockPos>
            > FIRST_CONNECTION =
            DATA_COMPONENTS.registerComponentType(
                    "first_connection",
                    builder -> builder
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
            );

    /**
     * UUID sub-level первого выбранного конца.
     *
     * Если компонента отсутствует, первый конец находится
     * в обычном мире.
     */
    public static final DeferredHolder<
            DataComponentType<?>,
            DataComponentType<UUID>
            > FIRST_CONNECTION_SUBLEVEL =
            DATA_COMPONENTS.registerComponentType(
                    "first_connection_sublevel",
                    builder -> builder
                            .persistent(UUID.CODEC)
                            .networkSynchronized(ByteBufCodecs.UUID)
            );

    private CWDataComponents() {
    }
}
