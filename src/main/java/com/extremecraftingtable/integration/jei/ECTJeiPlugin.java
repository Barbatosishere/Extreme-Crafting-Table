package com.extremecraftingtable.integration.jei;

import com.extremecraftingtable.ECTMod;
import com.extremecraftingtable.Registration;
import com.extremecraftingtable.machines.workbench.RecipeFinder;
import com.extremecraftingtable.machines.workbench.WorkbenchRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * JEI plugin for the Extreme Crafting Table. Registers a recipe category so
 * players can browse {@code workbench_recipe} entries inside JEI, and adds
 * the workbench block as a recipe catalyst so the category appears in the
 * workbench's GUI.
 *
 * <p>The {@code @JeiPlugin} annotation makes the JEI runtime discover this
 * class automatically; no manual registration in {@link ECTMod} is needed.
 * Because JEI is loaded via {@code runtimeOnly}, this class is compiled with
 * JEI on the classpath (so {@code IModPlugin} resolves) but the JEI jar is
 * only present at runtime when the user installs JEI — without JEI the
 * plugin never fires and the rest of the mod still works.</p>
 */
@JeiPlugin
public class ECTJeiPlugin implements IModPlugin {
    public static final ResourceLocation RECIPE_TYPE_UID =
        ECTMod.location("workbench_recipe");
    public static final mezz.jei.api.recipe.RecipeType<WorkbenchRecipe> RECIPE_TYPE =
        // JEI 19's create() takes (String modId, String name, Class<T>). The
        // full ResourceLocation for the recipe type is rebuilt from the two
        // components inside getPluginUid() / getRecipeType().
        mezz.jei.api.recipe.RecipeType.create(ECTMod.MOD_ID, "workbench_recipe", WorkbenchRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return RECIPE_TYPE_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        // JEI 19 passes a IGuiHelper into the addRecipeCategories callback so
        // the category can build its drawables from the JEI-managed texture
        // manager. The actual WorkbenchRecipeCategory is created here and
        // registers itself with JEI.
        registration.addRecipeCategories(new WorkbenchRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // Iterate every workbench recipe currently known to the finder. The
        // finder holds the canonical map of holderId -> WorkbenchRecipe, with
        // both vanilla CraftingRecipe wrappers and the custom IngredientRecipe
        // implementations registered through the workbench_recipe serializer.
        // Recipes whose showInJEI is false are filtered out — that flag lets
        // mod authors hide a recipe from JEI without removing it from the
        // workbench's craftable list.
        List<WorkbenchRecipe> recipes = collectVisibleRecipes();
        registration.addRecipes(RECIPE_TYPE, recipes);
        registeredRecipes = recipes;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // JEI 19's addRecipeCatalyst signature is (IIngredientType, IIngredient,
        // RecipeType...) — the workbench block is the catalyst that ties the
        // category into the JEI sidebar.
        registration.addRecipeCatalyst(
            VanillaTypes.ITEM_STACK,
            new ItemStack(Registration.WORKBENCH_ITEM.get()),
            RECIPE_TYPE);
    }

    // Captured on JEI startup so /reload can push updated recipes into a
    // running JEI runtime without requiring the player to reconnect.
    private static volatile IJeiRuntime runtime;
    // The exact list last handed to JEI, needed because IRecipeManager has no
    // "replace all" call — a diff-free refresh must remove the old list before
    // adding the new one.
    private static volatile List<WorkbenchRecipe> registeredRecipes = List.of();

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    private static List<WorkbenchRecipe> collectVisibleRecipes() {
        RecipeFinder finder = WorkbenchRecipe.getRecipeFinder();
        List<WorkbenchRecipe> result = new java.util.ArrayList<>();
        for (var entry : finder.recipes().entrySet()) {
            WorkbenchRecipe recipe = entry.getValue();
            if (!recipe.showInJEI()) continue;
            if (!recipe.hasContent()) continue;
            result.add(recipe);
        }
        return result;
    }

    /**
     * Pushes the current recipe set into a running JEI runtime. Called after
     * {@link RecipeFinder#invalidate()} on datapack reload so JEI's recipe
     * browser reflects the change immediately instead of only after the
     * client reconnects. No-op if JEI's runtime isn't up yet (e.g. still on
     * the main menu).
     *
     * <p>This JEI API version has no "remove recipes" call, only
     * {@code addRecipes} and {@code hideRecipes}/{@code unhideRecipes}. So a
     * refresh hides every previously-registered recipe instance (JEI recipes
     * are matched by object identity / equality, and {@code RecipeFinder}
     * rebuilds fresh {@link WorkbenchRecipe} instances on every reload) and
     * adds the newly-collected instances — recipes unchanged by the reload
     * are simply hidden-then-re-added under a new instance, which is
     * indistinguishable to the player from "left alone".</p>
     *
     * <p>The caller ({@code ECTMod.onDatapackSync}) runs on the logical
     * server thread — for an integrated (singleplayer) server that is a
     * distinct thread from the client's render thread that JEI's runtime
     * expects to be called on. Mutating JEI's recipe manager off-thread while
     * the render thread is concurrently drawing the ingredient list / recipe
     * screen would race. So the actual work is deferred onto the client's
     * main-thread executor, matching how {@code ClientSyncPayload.handle}
     * schedules its own client-side work.</p>
     */
    public static void refresh() {
        // Dist guard: this class is only ever loaded by JEI's client plugin scan,
        // but a future server-side caller reaching this method would otherwise
        // dereference Minecraft.getInstance() (null on a dedicated server) and
        // NPE. Fail silent — JEI is client-only by definition.
        if (!net.neoforged.fml.loading.FMLLoader.getDist().isClient()) return;
        IJeiRuntime rt = runtime;
        if (rt == null) return;
        Minecraft.getInstance().execute(() -> {
            // Re-check: the runtime may have gone away (e.g. client shutting
            // down) between scheduling and this executor callback running.
            IJeiRuntime current = runtime;
            if (current == null) return;
            IRecipeManager recipeManager = current.getRecipeManager();
            List<WorkbenchRecipe> updated = collectVisibleRecipes();
            List<WorkbenchRecipe> previous = registeredRecipes;
            if (!previous.isEmpty()) recipeManager.hideRecipes(RECIPE_TYPE, previous);
            if (!updated.isEmpty()) recipeManager.addRecipes(RECIPE_TYPE, updated);
            registeredRecipes = updated;
        });
    }
}
