package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class WorkbenchRecipe implements Recipe<RecipeInput> {
    public static final ResourceLocation RECIPE_ID = ECTMod.location("workbench_recipe");
    public static final WorkbenchRecipeSerializer SERIALIZER = new WorkbenchRecipeSerializer();
    public static final RecipeType<WorkbenchRecipe> RECIPE_TYPE = new RecipeType<>() {
        @Override public String toString() { return RECIPE_ID.toString(); }
    };

    static final RecipeFinder recipeFinder = new DefaultFinder();

    public static final Comparator<WorkbenchRecipe> COMPARATOR =
        Comparator.comparingLong(WorkbenchRecipe::getRequiredEnergy)
            .thenComparingInt(r -> BuiltInRegistries.ITEM.getId(r.output.getItem()))
            // getId() can be null for codec-deserialized recipes before the finder
            // pins the RecipeHolder ID, so null-safe compare (nulls sort last).
            .thenComparing(WorkbenchRecipe::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Associates a WorkbenchRecipe with its unique RecipeHolder ID (the map key
     * from the RecipeFinder). The RecipeHolder ID comes from the datapack file
     * path and is unambiguous, unlike the recipe's internal {@link #getId()}.
     */
    public record RecipeEntry(ResourceLocation holderId, WorkbenchRecipe recipe) {}

    public static final Comparator<RecipeEntry> ENTRY_COMPARATOR =
        Comparator.comparingLong((RecipeEntry e) -> e.recipe().getRequiredEnergy())
            .thenComparingInt(e -> BuiltInRegistries.ITEM.getId(e.recipe().output.getItem()))
            .thenComparing(RecipeEntry::holderId);

    private ResourceLocation location;
    public final ItemStack output;
    private final long energy;
    private final boolean showInJEI;
    /**
     * Item-level prefilter index. Flattened from every alternative of every
     * {@link IngredientList} at construction time. Used by
     * {@link #quickCheckByItem(List)} and by the {@code quickCheck} hot path to
     * short-circuit the 1000+ vanilla recipes whose Item set is disjoint from
     * the inventory in O(stacks) instead of the O(ingredients × stacks ×
     * ingredient-items) cost of a full {@link Ingredient} test.
     *
     * <p>Lazily computed (not built in the constructor): {@link #inputs()} is
     * overridden by subclasses to return a field that is only assigned after
     * their {@code super(...)} call returns, so calling it during base-class
     * construction would see {@code null}/uninitialised state. Computing it on
     * first use instead guarantees the subclass is fully constructed.</p>
     */
    private volatile Set<Item> requiredItems;

    public WorkbenchRecipe(ResourceLocation location, ItemStack output, long energy, boolean showInJEI) {
        this.location = location;
        this.output = output != null ? output : ItemStack.EMPTY;
        // Clamp energy: a negative recipe cost would pass the craft gate and
        // inflate the machine's energy on use (see useEnergy). Subclasses and
        // network deserialization already clamp, but enforce it here as the
        // single chokepoint for all construction paths.
        this.energy = Math.max(0L, energy);
        this.showInJEI = showInJEI;
    }

    /**
     * Lazily computes and caches {@link #requiredItems}. Thread-safe: recipes
     * may be read from both the server thread and (via JEI) the client thread.
     */
    Set<Item> requiredItems() {
        Set<Item> result = requiredItems;
        if (result == null) {
            synchronized (this) {
                result = requiredItems;
                if (result == null) {
                    result = computeRequiredItems();
                    requiredItems = result;
                }
            }
        }
        return result;
    }

    /**
     * Collects every {@link Item} accepted by any alternative of any input
     * into a deduplicated {@link HashSet}. Computed once, lazily, and cached; the
     * inventory-based check then becomes a constant-time set membership test
     * per stack instead of an {@code Ingredient} test (which iterates the
     * ingredient's items array).
     */
    private Set<Item> computeRequiredItems() {
        Set<Item> result = new HashSet<>();
        List<IngredientList> lists = inputs();
        if (lists == null) return Collections.emptySet();
        for (IngredientList list : lists) {
            for (IngredientWithCount alt : list.alternatives()) {
                Ingredient ing = alt.ingredient();
                if (ing == null) continue;
                for (ItemStack sample : ing.getItems()) {
                    if (sample != null && !sample.isEmpty()) result.add(sample.getItem());
                }
            }
        }
        return result;
    }

    @Override public String toString() { return "WorkbenchRecipe{id=" + location + ", output=" + output + ", energy=" + energy + '}'; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkbenchRecipe that = (WorkbenchRecipe) o;
        // The internal location is not set by the codec (no placeholder ID pattern);
        // it is only meaningful as the RecipeHolder ID once set by the RecipeFinder.
        return energy == that.energy && Objects.equals(location, that.location) && ItemStack.matches(this.output, that.output);
    }

    @Override public int hashCode() { return Objects.hash(location, energy, output); }
    public final long getRequiredEnergy() { return energy; }
    public final boolean showInJEI() { return showInJEI; }

    @Override
    public final boolean matches(RecipeInput input, Level level) {
        if (!(input instanceof WorkbenchRecipeInput wbInput)) return false;
        return hasContent() && hasAllRequiredItems(wbInput.ingredientInventory());
    }

    @Override
    public final ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        if (!(input instanceof WorkbenchRecipeInput wbInput)) return ItemStack.EMPTY;
        return getOutput(wbInput.ingredientInventory(), registries);
    }

    @Override public RecipeSerializer<? extends Recipe<RecipeInput>> getSerializer() { return SERIALIZER; }
    @Override public RecipeType<? extends Recipe<RecipeInput>> getType() { return RECIPE_TYPE; }
    @Override public boolean isSpecial() { return true; }

    public final ResourceLocation getId() { return location; }
    // Package-private: used by RecipeFinder to overwrite the placeholder ID set during deserialization
    // with the actual RecipeHolder ID from the RecipeManager.
    void setLocation(ResourceLocation id) { this.location = id; }
    public static WorkbenchRecipe dummyRecipe() { return DummyRecipe.INSTANCE; }
    public static RecipeFinder getRecipeFinder() { return recipeFinder; }

    public abstract List<IngredientList> inputs();
    public boolean hasContent() { return true; }
    protected abstract String getSubTypeName();
    protected abstract ItemStack getOutput(List<ItemStack> inventory, HolderLookup.Provider access);

    /**
     * Cheap fail-fast prefilter used before the expensive combinatorial
     * {@link #hasAllRequiredItems} backtracking. For each ingredient it only checks
     * that at least one stack in the inventory can match it (ignoring the assignment
     * problem). This filters out the overwhelming majority of the ~2000 vanilla
     * recipes for the current ingredient set in O(ingredients × stacks), preventing
     * the server thread from being pinned by backtracking on every craft / GUI open.
     *
     * <p>Optimisation #2: an Item-level prefilter ({@link #quickCheckByItem}) runs
     * first. When the inventory holds items that are disjoint from the recipe's
     * candidate set, the recipe is rejected without touching the per-ingredient
     * IngredientList test, which is the hot inner loop on every setItem/removeItem
     * and on every GUI open.</p>
     */
    protected boolean quickCheck(List<ItemStack> inventory) {
        // Defense-in-depth: a recipe with zero IngredientLists matches vacuously
        // at every later stage (canMatch returns true at index >= size on any
        // inventory). Subclasses must exclude input-less recipes via hasContent();
        // this guard keeps a future subclass regression from reintroducing a
        // free-crafting exploit.
        if (inputs().isEmpty()) return false;
        if (!quickCheckByItem(inventory)) return false;
        outer:
        for (IngredientList input : inputs()) {
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && input.test(stack)) {
                    continue outer;
                }
            }
            return false; // no stack matches this ingredient
        }
        return true;
    }

    /**
     * Item-level prefilter. Returns false when the inventory's non-empty Item set is
     * disjoint from the recipe's required Item set, i.e. no ingredient can possibly
     * match. Empty inventory or empty requiredItems (e.g. recipes whose every
     * alternative resolved to {@code Ingredient.EMPTY}) fall through to the
     * IngredientList test so behaviour is unchanged for those edge cases.
     */
    boolean quickCheckByItem(List<ItemStack> inventory) {
        Set<Item> required = requiredItems();
        if (required.isEmpty()) return true;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && required.contains(stack.getItem())) return true;
        }
        return false;
    }

    protected boolean hasAllRequiredItems(List<ItemStack> inventory) {
        var stacks = inventory.stream().map(ItemStack::copy).collect(Collectors.toCollection(ArrayList::new));
        return canMatch(stacks, 0);
    }

    private boolean canMatch(List<ItemStack> stacks, int index) {
        if (index >= this.inputs().size()) return true;
        var input = this.inputs().get(index);
        for (int i = 0; i < stacks.size(); i++) {
            var stack = stacks.get(i);
            // Try each alternative within the IngredientList individually, with
            // backtracking across alternatives. This prevents the greedy-consumption
            // bug where the first matching alternative (e.g. 3 iron ingots) consumes
            // all items and strands later ingredients, when a smaller alternative
            // (e.g. 1 iron ingot) would have left enough items for subsequent ingredients.
            for (var alt : input.alternatives()) {
                int before = stack.getCount();
                if (alt.shrink(stack)) {
                    if (canMatch(stacks, index + 1)) {
                        return true;
                    }
                    // Full backtrack with NO snapshot buffer. Invariant: canMatch
                    // returning false has already restored every stack to the state
                    // it was entered in (each level restores everything except its
                    // own shrink via the callee's guarantee, plus its own stack via
                    // setCount(before) here). So the only residual change at this
                    // point is this level's shrink on `stack`.
                    //
                    // An earlier revision shared one int[] snapshot buffer across
                    // recursion levels and restored all counts from it after a failed
                    // subtree. That read STALE data: the deeper level overwrites the
                    // buffer with its own post-shrink counts before returning false,
                    // so this level's restore loop resurrected the deeper level's
                    // shrink on unrelated stacks — corrupting counts, producing false
                    // negatives, and (in consumeItems) over-consuming real items.
                    stack.setCount(before);
                }
            }
        }
        return false;
    }

    public void consumeItems(List<ItemStack> inventory) {
        // Use the same backtracking algorithm as canMatch() to find the correct
        // slot assignment, then apply the consumption to the real inventory.
        // This prevents the greedy-consumption bug where the first matching slot
        // is taken for an early ingredient, stranding later ingredients.
        var stacks = inventory.stream().map(ItemStack::copy).collect(Collectors.toCollection(ArrayList::new));
        if (canMatch(stacks, 0)) {
            for (int i = 0; i < inventory.size(); i++) {
                int consumed = inventory.get(i).getCount() - stacks.get(i).getCount();
                if (consumed > 0) {
                    inventory.get(i).shrink(consumed);
                }
            }
        }
    }

    public record WorkbenchRecipeInput(List<ItemStack> ingredientInventory) implements RecipeInput {
        @Override public ItemStack getItem(int index) { return ingredientInventory.get(index); }
        @Override public int size() { return ingredientInventory.size(); }
    }
}
