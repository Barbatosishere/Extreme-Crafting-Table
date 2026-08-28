package com.extremecraftingtable.machines.workbench;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import java.util.List;

/**
 * Adapts a vanilla crafting-table recipe (shaped or shapeless) into the workbench's
 * {@link WorkbenchRecipe} interface so the workbench can craft any vanilla recipe.
 * The crafting ingredients are converted to the workbench's {@link IngredientList}
 * format, and matching/consumption is handled by the base class backtracking.
 *
 * <p>Energy is computed from the ingredient count (a flat rate per ingredient) so
 * vanilla recipes are not free; the {@code WorkbenchRecipe} base class never
 * serializes this wrapper back to disk — these recipes come from the vanilla
 * {@code RecipeManager} and are synced by vanilla's own serializer.</p>
 */
class VanillaWorkbenchRecipe extends WorkbenchRecipe {
    private final CraftingRecipe recipe;
    private final List<IngredientList> inputs;

    /** Energy cost per ingredient, as a simple flat rate (no per-recipe config needed). */
    static final long ENERGY_PER_INGREDIENT = 1000L;

    public VanillaWorkbenchRecipe(ResourceLocation id, CraftingRecipe recipe, HolderLookup.Provider registries) {
        super(id, recipe.getResultItem(registries).copy(),
            Math.max(1L, (long) recipe.getIngredients().stream().filter(i -> !i.isEmpty()).count()) * ENERGY_PER_INGREDIENT,
            true);
        this.recipe = recipe;
        // hasNoItems() additionally excludes NeoForge's empty-TAG sentinel: a
        // TagValue whose tag resolves to zero items reports a single BARRIER
        // placeholder from getItems() (isEmpty() is false for it), which would
        // otherwise make the recipe craftable with a plain barrier.
        this.inputs = recipe.getIngredients().stream()
            .filter(ing -> !ing.isEmpty() && !ing.hasNoItems())
            .map(ing -> new IngredientList(List.of(new IngredientWithCount(ing, 1))))
            .toList();
    }

    @Override public List<IngredientList> inputs() { return inputs; }
    // inputs must be non-empty: dynamic CustomRecipe family members (firework
    // rocket/star) expose an EMPTY getIngredients() list with a real result —
    // with only the output check they would match ANY inventory vacuously and
    // craft from nothing (P0 free-item exploit).
    @Override public boolean hasContent() { return !output.isEmpty() && !inputs.isEmpty(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override protected String getSubTypeName() { return "vanilla"; }
    @Override protected ItemStack getOutput(List<ItemStack> inventory, HolderLookup.Provider access) { return output.copy(); }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return output.copy(); }
}