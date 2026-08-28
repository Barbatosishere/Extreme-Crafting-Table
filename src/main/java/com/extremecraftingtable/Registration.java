package com.extremecraftingtable;

import com.extremecraftingtable.machines.workbench.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class Registration {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ECTMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ECTMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ECTMod.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ECTMod.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, ECTMod.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, ECTMod.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ECTMod.MOD_ID);

    // Register BlockWorkbench using a lambda to avoid circular dependency
    public static final DeferredBlock<BlockWorkbench> WORKBENCH_BLOCK = BLOCKS.register(BlockWorkbench.NAME,
        () -> new BlockWorkbench(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).pushReaction(PushReaction.BLOCK).strength(3.0f)));

    public static final DeferredItem<BlockItem> WORKBENCH_ITEM = ITEMS.registerSimpleBlockItem(WORKBENCH_BLOCK);

    // BlockEntityType holder - set during registration to break circular dependency
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TileWorkbench>> WORKBENCH_TYPE =
        BLOCK_ENTITIES.register(BlockWorkbench.NAME, () -> {
            // Single source of truth for the BlockEntityType: TileWorkbench's
            // super(...) and BlockWorkbench.newBlockEntity both read this
            // DeferredHolder.get() directly. The round-3 audit removed the
            // earlier AtomicReference mirror to keep a single registration path.
            return BlockEntityType.Builder.of(
                TileWorkbench::new, WORKBENCH_BLOCK.get()
            ).build(null);
        });

    public static final DeferredHolder<MenuType<?>, MenuType<ContainerWorkbench>> WORKBENCH_MENU =
        MENUS.register(BlockWorkbench.NAME, () -> IMenuTypeExtension.create(ContainerWorkbench::new));

    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, WorkbenchRecipeSerializer> WORKBENCH_RECIPE_SERIALIZER =
        RECIPE_SERIALIZERS.register("workbench_recipe", () -> WorkbenchRecipe.SERIALIZER);

    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeType<?>, net.minecraft.world.item.crafting.RecipeType<WorkbenchRecipe>> WORKBENCH_RECIPE_TYPE =
        RECIPE_TYPES.register("workbench_recipe", () -> WorkbenchRecipe.RECIPE_TYPE);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
        CREATIVE_MODE_TABS.register("tab", () -> CreativeModeTab.builder()
            .icon(() -> new ItemStack(WORKBENCH_ITEM.get()))
            .title(Component.translatable("itemGroup." + ECTMod.MOD_ID))
            .displayItems((params, output) -> output.accept(WORKBENCH_ITEM.get()))
            .build());
}