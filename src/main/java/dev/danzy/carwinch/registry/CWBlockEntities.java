package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import dev.danzy.carwinch.content.towbar.TowbarBlockEntity;
import dev.danzy.carwinch.content.winch.CarWinchBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CarWinch.ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CarWinchBlockEntity>> WINCH =
            BLOCK_ENTITIES.register("winch", () -> BlockEntityType.Builder
                    .of((pos, state) -> new CarWinchBlockEntity(CWBlockEntities.WINCH.get(), pos, state), CWBlocks.WINCH.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TowbarBlockEntity>> TOWBAR =
            BLOCK_ENTITIES.register("towbar", () -> BlockEntityType.Builder
                    .of((pos, state) -> new TowbarBlockEntity(CWBlockEntities.TOWBAR.get(), pos, state), CWBlocks.TOWBAR.get())
                    .build(null));

    private CWBlockEntities() {
    }
}
