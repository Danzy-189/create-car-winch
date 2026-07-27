package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import dev.danzy.carwinch.content.item.IronRopeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CarWinch.ID);

    public static final DeferredItem<IronRopeItem> IRON_ROPE = ITEMS.register("iron_rope",
            () -> new IronRopeItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<Item> WINCH = ITEMS.registerSimpleBlockItem("winch", CWBlocks.WINCH);
    public static final DeferredItem<Item> TOWBAR = ITEMS.registerSimpleBlockItem("towbar", CWBlocks.TOWBAR);

    private CWItems() {
    }
}
