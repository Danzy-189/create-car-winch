package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import dev.danzy.carwinch.content.towbar.TowbarBlock;
import dev.danzy.carwinch.content.winch.CarWinchBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CarWinch.ID);

    public static final DeferredBlock<CarWinchBlock> WINCH = BLOCKS.register("winch",
            () -> new CarWinchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .noOcclusion()));

    public static final DeferredBlock<TowbarBlock> TOWBAR = BLOCKS.register("towbar",
            () -> new TowbarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .noOcclusion()));

    private CWBlocks() {
    }
}
