package com.extremecraftingtable.integration.jei;

import com.extremecraftingtable.ECTMod;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Visual definition of the workbench recipe category. The real workbench GUI
 * ({@code ContainerWorkbench}) lays out its 27 ingredient slots as a 9-column
 * by 3-row grid at (8, 18) with 18px spacing; the JEI category mirrors that
 * exact layout so recipes with more than 9 ingredients (e.g. Create's
 * Mechanical Crafting recipes, which this mod's {@code VanillaWorkbenchRecipe}
 * wraps like any other {@code CraftingRecipe}) render fully instead of being
 * clamped. The actual recipe data is supplied per-recipe by
 * {@link #setRecipe(IRecipeLayoutBuilder, com.extremecraftingtable.machines.workbench.WorkbenchRecipe, mezz.jei.api.gui.ingredient.IFocusGroup)}.
 */
public class WorkbenchRecipeCategory implements IRecipeCategory<com.extremecraftingtable.machines.workbench.WorkbenchRecipe> {
    private final IDrawable background;
    private final IDrawable icon;
    private final Component title;

    /**
     * X/Y of the output slot inside the background crop. The 9x3 input grid
     * occupies x=[8,170) y=[18,72); the output slot sits below it (rather
     * than to the right, its old position at (142, 38)) so it can never
     * overlap a populated input slot regardless of how many of the 27
     * ingredient slots a given recipe actually fills.
     */
    public static final int RECIPE_OUTPUT_X = 80;
    public static final int RECIPE_OUTPUT_Y = 76;

    public WorkbenchRecipeCategory(IGuiHelper guiHelper) {
        ResourceLocation tex = ECTMod.location("textures/gui/workbench.png");
        // Crop widened from 176x92 to the full 9-column grid width (unchanged,
        // 170px already fit in 176) and heightened from 92 to 96 so the
        // relocated output slot (y=76..92) has a couple of pixels of margin
        // to the crop edge instead of sitting flush against it.
        this.background = guiHelper.createDrawable(tex, 0, 0, 176, 96);
        this.icon = guiHelper.createDrawable(tex, 0, 0, 16, 16);
        this.title = Component.translatable("gui.extremecraftingtable.jei.category");
    }

    @Override
    public mezz.jei.api.recipe.RecipeType<com.extremecraftingtable.machines.workbench.WorkbenchRecipe> getRecipeType() {
        return ECTJeiPlugin.RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, com.extremecraftingtable.machines.workbench.WorkbenchRecipe recipe, mezz.jei.api.recipe.IFocusGroup focuses) {
        // JEI 19's IRecipeLayoutBuilder takes one slot at a time. The
        // workbench's ingredients live in a 9-column by 3-row grid (27 slots)
        // at the left of the texture, matching ContainerWorkbench's own
        // `addSlot(new SlotContainer(tile, col + row * 9, 8 + col * 18, 18 + row * 18))`
        // layout pixel-for-pixel; for recipes with fewer than 27 ingredients
        // the empty slots stay invisible. We compute input X/Y from the index
        // so a later texture change does not require editing this method.
        // BUG-R4m2 fix (superseded): the workbench holds at most 27 inputs
        // (9x3 grid), not 9 — the original clamp to a 3x3 layout did not match
        // the real GUI and silently dropped ingredients for any recipe with
        // more than 9 inputs (observed with Create's Mechanical Crafting
        // recipes, wrapped via VanillaWorkbenchRecipe like any other
        // CraftingRecipe). A malformed datapack recipe with 28+ inputs is not
        // representable in the 27-slot grid at all; clamp and warn so that
        // case stays visible in logs without crashing JEI.
        int inputCount = recipe.inputs().size();
        if (inputCount > 27) {
            com.extremecraftingtable.ECTMod.LOGGER.warn(
                "Recipe {} has {} inputs (>27), clamping for JEI display",
                recipe.getId(), inputCount);
            inputCount = 27;
        }
        int columns = 9;
        for (int i = 0; i < inputCount; i++) {
            int col = i % columns;
            int row = i / columns;
            int x = 8 + col * 18;
            int y = 18 + row * 18;
            IRecipeSlotBuilder slot = builder.addInputSlot(x, y);
            for (var alt : recipe.inputs().get(i).alternatives()) {
                for (ItemStack sample : alt.ingredient().getItems()) {
                    ItemStack copy = sample.copy();
                    copy.setCount(alt.count());
                    slot.addItemStack(copy);
                }
            }
        }
        // Single output slot on the right of the background. Pass a COPY: JEI
        // stores the stack reference in its ingredient registry and may hand it
        // back mutated (focus normalisation, addon processing); the live recipe's
        // output stack must never be reachable from JEI's internal state.
        builder.addOutputSlot(RECIPE_OUTPUT_X, RECIPE_OUTPUT_Y)
            .addItemStack(recipe.output.copy());
    }
}
