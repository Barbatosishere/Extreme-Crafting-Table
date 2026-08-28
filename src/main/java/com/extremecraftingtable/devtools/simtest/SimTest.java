package com.extremecraftingtable.devtools.simtest;

import com.extremecraftingtable.ECTMod;
import com.extremecraftingtable.machines.workbench.IngredientList;
import com.extremecraftingtable.machines.workbench.IngredientRecipe;
import com.extremecraftingtable.machines.workbench.IngredientWithCount;
import com.extremecraftingtable.machines.workbench.WorkbenchRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone simulation harness for {@link WorkbenchRecipe} and friends.
 * Exercises the real NeoForge 1.21.1 ItemStack/Ingredient classes inside a
 * live modded server boot. Used to regression-test the bug fixes the audit
 * found (BUG-2 fingerprint reset, snapshot sharing, equals/hashCode
 * contract, hasContent, consumeItems greediness).
 *
 * <p>Why this is a {@link SubscribeEvent} on the dedicated-server setup
 * phase instead of a {@code public static void main}: the chain
 * {@code Bootstrap.bootStrap → Blocks.<clinit> → FeatureFlags →
 * LoadingModList.get()} requires the ModLauncher classloader, which is
 * only present in a live mod runtime. Firing from setupEvent runs after
 * the bootstrap is complete so {@code Items.STONE} and friends are
 * initialised normally.</p>
 *
 * <p>To run, launch a dedicated server with the mod loaded:
 * {@code gradle runServer}. The harness prints a pass/fail summary to the
 * server console (and to {@code logs/latest.log} via SLF4J). The
 * {@code jar} task excludes this package so users never see the class.</p>
 */
@EventBusSubscriber(modid = ECTMod.MOD_ID)
public class SimTest {
    private static final Logger LOG = LoggerFactory.getLogger("simtest");
    private static int passed = 0, failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) { passed++; LOG.info("  PASS  {}", name); }
        else { failed++; LOG.error("  FAIL  {}", name); }
    }

    private static Ingredient ingOf(ItemStack... stacks) { return Ingredient.of(stacks); }
    private static IngredientList il(IngredientWithCount... alts) { return new IngredientList(List.of(alts)); }
    private static IngredientWithCount iwc(Ingredient ing, int count) { return new IngredientWithCount(ing, count); }

    private static List<ItemStack> inventoryOf(ItemStack... stacks) {
        var inv = new ArrayList<ItemStack>(27);
        for (ItemStack s : stacks) inv.add(s);
        while (inv.size() < 27) inv.add(ItemStack.EMPTY);
        return inv;
    }

    @SubscribeEvent
    public static void onServerSetup(FMLDedicatedServerSetupEvent event) {
        LOG.info("=== SimTest for Extreme Crafting Table ===");
        try {
            test_BUG2_fingerprintResetOnRecipeSwitch();
            test_shareSnapshotNoAliasing();
            test_equalsHashCodeContract();
            test_ingredientListFailsWhenEmpty();
            test_consumeItemsDrainsGreedy();
            test_backtrackRestoreAcrossLevels();
            test_javaRuntimeSanity();
        } catch (Throwable t) {
            failed++;
            LOG.error("unhandled exception in tests", t);
        }
        LOG.info("=== {} passed, {} failed ===", passed, failed);
        if (failed > 0) {
            // Mark the boot as having a known-failing harness; the actual
            // server still starts (we are not failing the boot) but operators
            // can grep logs for the failure summary.
            LOG.warn("SimTest reported {} failures. See above.", failed);
        }
    }

    /**
     * BUG-2 regression: switching to a recipe whose ingredients the inventory
     * does not hold must report false rather than stale-matching an earlier
     * fingerprint.
     */
    private static void test_BUG2_fingerprintResetOnRecipeSwitch() {
        LOG.info("[BUG-2] fingerprint reset on recipe switch");
        var locA = ResourceLocation.fromNamespaceAndPath("ect", "test/a");
        var locB = ResourceLocation.fromNamespaceAndPath("ect", "test/b");
        var recipeA = new IngredientRecipe(locA,
            new ItemStack(Items.COBBLESTONE, 1), 1000L, true,
            List.of(il(iwc(ingOf(new ItemStack(Items.STONE)), 1))));
        var recipeB = new IngredientRecipe(locB,
            new ItemStack(Items.DIAMOND, 1), 5000L, true,
            List.of(il(iwc(ingOf(new ItemStack(Items.DIAMOND)), 1))));
        var inv = inventoryOf(new ItemStack(Items.STONE, 1));
        check("recipeA matches a STONE inventory", recipeA.matches(new WorkbenchRecipe.WorkbenchRecipeInput(inv), null));
        check("recipeB rejects a STONE-only inventory", !recipeB.matches(new WorkbenchRecipe.WorkbenchRecipeInput(inv), null));
    }

    /**
     * The shared snapshot buffer between canMatch and consumeItems must
     * not cause one path to read the other's intermediate state.
     */
    private static void test_shareSnapshotNoAliasing() {
        LOG.info("[snapshot] canMatch + consumeItems share buffer safely");
        var loc = ResourceLocation.fromNamespaceAndPath("ect", "test/snap");
        var recipe = new IngredientRecipe(loc,
            new ItemStack(Items.COBBLESTONE, 1), 1000L, true,
            List.of(
                il(iwc(ingOf(new ItemStack(Items.STONE)), 1)),
                il(iwc(ingOf(new ItemStack(Items.STONE)), 1))));
        var inv = inventoryOf(new ItemStack(Items.STONE, 2));
        check("canMatch matches 2-STONE inventory", recipe.matches(new WorkbenchRecipe.WorkbenchRecipeInput(inv), null));
        recipe.consumeItems(inv);
        check("after consumeItems: slot 0 is empty", inv.get(0).isEmpty());
        check("after consumeItems: slot 1 is empty", inv.get(1).isEmpty());
    }

    /**
     * equals/hashCode contract: equal recipes must have equal hashCodes.
     */
    private static void test_equalsHashCodeContract() {
        LOG.info("[equals/hashCode] contract");
        var loc = ResourceLocation.fromNamespaceAndPath("ect", "test/eq");
        var x = new IngredientRecipe(loc, new ItemStack(Items.STONE, 1), 1000L, true, List.of());
        var y = new IngredientRecipe(loc, new ItemStack(Items.STONE, 1), 1000L, true, List.of());
        check("equal recipes are equal", x.equals(y));
        check("equal recipes have equal hashCodes", x.hashCode() == y.hashCode());
    }

    /**
     * An ingredient list that resolves to an empty Ingredient should
     * not match anything (hasContent should be false).
     */
    private static void test_ingredientListFailsWhenEmpty() {
        LOG.info("[hasContent] empty-input recipe has no content");
        var loc = ResourceLocation.fromNamespaceAndPath("ect", "test/empty");
        var recipe = new IngredientRecipe(loc,
            new ItemStack(Items.STONE, 1), 1000L, true,
            List.of(il(iwc(Ingredient.EMPTY, 1))));
        check("recipe with EMPTY ingredient has no content", !recipe.hasContent());
    }

    /**
     * canMatch must backtrack correctly: a single stack of 2 STONE presented
     * for a recipe wanting STONE+STONE should consume 1+1 from the same stack.
     */
    private static void test_consumeItemsDrainsGreedy() {
        LOG.info("[consumeItems] drains a single shared stack across two ingredients");
        var loc = ResourceLocation.fromNamespaceAndPath("ect", "test/greedy");
        var recipe = new IngredientRecipe(loc,
            new ItemStack(Items.COBBLESTONE, 1), 1000L, true,
            List.of(
                il(iwc(ingOf(new ItemStack(Items.STONE)), 1)),
                il(iwc(ingOf(new ItemStack(Items.STONE)), 1))));
        var inv = inventoryOf(new ItemStack(Items.STONE, 2));
        check("single stack of 2 STONE matches 2x STONE recipe", recipe.matches(new WorkbenchRecipe.WorkbenchRecipeInput(inv), null));
        recipe.consumeItems(inv);
        check("single stack fully drained", inv.get(0).isEmpty());
    }

    /**
     * Sanity: this code is on a Java 21 runtime.
     */
    private static void test_javaRuntimeSanity() {
        LOG.info("[sanity] runtime is Java 21+");
        String version = System.getProperty("java.version");
        check("running on Java 21+", version.startsWith("21") || version.startsWith("22"));
    }

    /**
     * Backtrack-restore across recursion levels. Regression for the shared-snapshot
     * corruption: with the old shared int[] buffer, a deeper level that shrank a
     * stack and then failed overwrote the buffer, so the shallower level's restore
     * resurrected the deeper shrink on an unrelated stack.
     *
     * <p>Minimal trigger (old code returned false; correct answer is true):
     * I0=[DIRT|STONE], I1=[DIRT|STONE], I2=[DIRT]; inventory DIRT(1)+STONE(2).
     * Level 0 tries DIRT for I0; level 1 shrinks STONE, fails at I2 and restores;
     * level 1 returns false leaving the buffer at its own post-shrink counts; the
     * old level-0 restore read that stale buffer and destroyed one STONE, so the
     * later successful assignment (STONE+STONE for I0/I1, DIRT for I2) no longer fit.</p>
     */
    private static void test_backtrackRestoreAcrossLevels() {
        LOG.info("[backtrack] restore across recursion levels (shared-snapshot regression)");
        var loc = ResourceLocation.fromNamespaceAndPath("ect", "test/backtrack");
        var recipe = new IngredientRecipe(loc,
            new ItemStack(Items.COBBLESTONE, 1), 1000L, true,
            List.of(
                il(iwc(ingOf(new ItemStack(Items.DIRT)), 1), iwc(ingOf(new ItemStack(Items.STONE)), 1)),
                il(iwc(ingOf(new ItemStack(Items.DIRT)), 1), iwc(ingOf(new ItemStack(Items.STONE)), 1)),
                il(iwc(ingOf(new ItemStack(Items.DIRT)), 1))));
        var inv = inventoryOf(new ItemStack(Items.DIRT, 1), new ItemStack(Items.STONE, 2));
        check("backtracked recipe matches DIRT(1)+STONE(2)", recipe.matches(new WorkbenchRecipe.WorkbenchRecipeInput(inv), null));
        recipe.consumeItems(inv);
        check("consumed exactly DIRT(1)", inv.get(0).isEmpty());
        check("consumed exactly STONE(2)", inv.get(1).isEmpty());
    }
}
