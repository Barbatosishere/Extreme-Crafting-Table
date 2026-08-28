package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.Collections;
import java.util.List;

class DummyRecipe extends WorkbenchRecipe {
    static final DummyRecipe INSTANCE = new DummyRecipe();
    private DummyRecipe() { super(ECTMod.location("builtin_dummy"), ItemStack.EMPTY, 0, false); }
    @Override public List<IngredientList> inputs() { return Collections.emptyList(); }
    @Override public boolean hasContent() { return false; }
    @Override protected String getSubTypeName() { return "dummy"; }
    @Override protected ItemStack getOutput(List<ItemStack> inventory, HolderLookup.Provider access) { return ItemStack.EMPTY; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return ItemStack.EMPTY; }
    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public String toString() { return "WorkbenchRecipe NoRecipe"; }
}
