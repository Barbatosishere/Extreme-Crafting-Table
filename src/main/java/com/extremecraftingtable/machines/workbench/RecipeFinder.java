package com.extremecraftingtable.machines.workbench;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.extremecraftingtable.ECTMod;

public interface RecipeFinder {
    Map<ResourceLocation, WorkbenchRecipe> recipes();
    default int recipeSize() { return recipes().size(); }

    /**
     * Force the next {@link #recipes()} call to rebuild from the current
     * RecipeManager. Default is a no-op — implementations without a cache
     * (e.g. test fakes) do not need to do anything. Caching implementations
     * must drop their snapshot.
     */
    default void invalidate() { /* no-op for stateless implementations */ }

    /**
     * Returns the subset of recipes that can be produced from the current
     * ingredient inventory. Default implementation scans every loaded recipe and
     * applies {@link WorkbenchRecipe#quickCheck} + the backtracking
     * {@link WorkbenchRecipe#hasAllRequiredItems}, which is O(M_recipes × N_inputs
     * × avg_alt_items) per call. {@link DefaultFinder} overrides this to first
     * consult an inverted Item→RecipeIds index so the candidate set is the union
     * of all recipes referencing the inventory's distinct Items (typically 30-200
     * of the 2000+ total). Test doubles and any other implementation can rely on
     * the slow default path.
     */
    default List<WorkbenchRecipe.RecipeEntry> getRecipes(List<ItemStack> input) {
        return recipes().entrySet().stream()
            .filter(e -> e.getValue().quickCheck(input))
            .filter(e -> e.getValue().hasAllRequiredItems(input))
            .map(e -> new WorkbenchRecipe.RecipeEntry(e.getKey(), e.getValue()))
            .sorted(WorkbenchRecipe.ENTRY_COMPARATOR)
            .toList();
    }

    default List<WorkbenchRecipe.RecipeEntry> findRecipes(ItemStack output) {
        if (output.isEmpty()) return Collections.emptyList();
        return recipes().entrySet().stream()
            .filter(e -> ItemStack.isSameItemSameComponents(e.getValue().output, output))
            .map(e -> new WorkbenchRecipe.RecipeEntry(e.getKey(), e.getValue()))
            .toList();
    }
}

class DefaultFinder implements RecipeFinder {
    /**
     * Dedicated monitor for the cache rebuild critical section. A separate
     * lock object (rather than {@code synchronized (this)}) is used so that
     * even if a future patch makes DefaultFinder public-extensible or
     * exposes it through another reference, a different code path that
     * synchronises on the instance cannot accidentally share the same
     * monitor as the recipe cache rebuild. The object identity is private
     * and never escapes; only {@link #recipes()} holds a reference.
     */
    private final Object cacheLock = new Object();

    /**
     * Cached filtered recipe map, keyed by the RecipeManager instance it was
     * built from. The recipes() method is hot (called 20×/s per open menu
     * from setTrackValues, plus on every ingredient change), so this avoids
     * re-filtering the whole recipe manager on every call. The cache is keyed
     * by the manager reference rather than a TTL: on a dedicated server
     * /reload replaces the manager object on the server thread, so an
     * identity change correctly drops the cache without any event-listener
     * wiring. On the CLIENT side, {@code Minecraft.getInstance().getConnection()}
     * returns the same Connection object across a /reload — the server pushes
     * the new RecipeManager contents to the existing client connection via
     * the datapack-sync packet, so the identity check {@code cachedManager == current}
     * is a false-positive. The {@link #invalidate()} hook below (called from
     * {@code OnDatapackSyncEvent}) is what actually drops the client cache.
     */
    private volatile RecipeManager cachedManager = null;
    private volatile Map<ResourceLocation, WorkbenchRecipe> cachedRecipes = Collections.emptyMap();

    /**
     * Force the next {@link #recipes()} call to rebuild. Wired from
     * {@code OnDatapackSyncEvent} on the client so a /reload that keeps the
     * same {@code Connection} reference still drops the stale snapshot.
     */
    public void invalidate() {
        synchronized (cacheLock) {
            cachedManager = null;
            cachedRecipes = Collections.emptyMap();
            cachedItemIndex = Collections.emptyMap();
        }
    }
    /**
     * Inverted index: for each candidate Item appearing in any recipe, the set of
     * RecipeHolder IDs that can be reached through that Item. Built once per
     * RecipeManager cache rebuild and used by the overridden {@link #getRecipes}
     * to short-circuit scanning the full 2000+ recipe map.
     */
    private volatile Map<Item, Set<ResourceLocation>> cachedItemIndex = Collections.emptyMap();

    @Override
    public Map<ResourceLocation, WorkbenchRecipe> recipes() {
        RecipeManager current = currentManager();
        if (cachedManager == current) {
            return cachedRecipes;
        }
        // Synchronized on a dedicated lock object (not on DefaultFinder.class,
        // and not on `this`): a class-level monitor would serialize every mod
        // that shares this class loader; a `this` monitor couples the lock to
        // the instance identity and is fragile under subclassing. A private
        // final field gives us a unique monitor that nothing else can
        // synchronise on. The check-then-act on the two volatile fields is
        // not atomic, and HashMap.putAll used in loadRecipes is not
        // thread-safe, so the lock is required regardless.
        synchronized (cacheLock) {
            // Double-check: another thread may have updated the cache while we waited.
            if (cachedManager == current) {
                return cachedRecipes;
            }
            Map<ResourceLocation, WorkbenchRecipe> all = current == null
                ? Collections.emptyMap()
                : loadRecipes(current);
            Map<ResourceLocation, WorkbenchRecipe> filtered = all.entrySet().stream()
                .filter(e -> e.getValue().hasContent())
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
            cachedRecipes = filtered;
            // Build the inverted index from the *filtered* set: dummy recipes, or
            // recipes whose every alternative is Ingredient.EMPTY, do not appear in
            // the index because they have no candidate Items. A lookup that misses
            // every item falls back to the full scan in getRecipes().
            Map<Item, Set<ResourceLocation>> idx = new HashMap<>();
            for (var entry : filtered.entrySet()) {
                WorkbenchRecipe r = entry.getValue();
                for (Item item : r.requiredItems()) {
                    idx.computeIfAbsent(item, k -> new HashSet<>()).add(entry.getKey());
                }
            }
            cachedItemIndex = idx;
            // BUG-R7C1 fix: assign cachedManager LAST. If loadRecipes throws
            // partway (e.g. a single malformed RecipeHolder in a mod datapack
            // that breaks the per-iteration try/catch), the next call still
            // sees the previous (non-null) manager and can retry instead of
            // being pinned to a half-built snapshot whose cachedManager
            // claims to be a manager that never actually finished loading.
            cachedManager = current;
            return cachedRecipes;
        }
    }

    /**
     * Overrides the default scan with the inverted index. Union the per-Item
     * RecipeId sets to build the candidate set, then apply the per-recipe
     * quickCheck + hasAllRequiredItems as the final correctness gate (the index
     * is a superset filter, not a soundness test). Falls back to a full scan when
     * the inventory has no recognised Items (the index would otherwise return
     * an empty set, silently dropping every recipe).
     */
    @Override
    public List<WorkbenchRecipe.RecipeEntry> getRecipes(List<ItemStack> input) {
        Map<ResourceLocation, WorkbenchRecipe> all = recipes();
        Set<ResourceLocation> candidates = collectCandidateIds(input);
        // Empty index or inventory with no registered Items: fall through to the
        // full scan, but skip the quickCheck per-recipe since the index is
        // already empty (it could only be empty before the first RecipeManager
        // scan completes or when the recipe set has zero Items — in the latter
        // case every recipe's requiredItems is empty, so quickCheck is a no-op
        // and the full scan is the only path).
        if (candidates.isEmpty()) {
            return all.entrySet().stream()
                .filter(e -> e.getValue().quickCheck(input))
                .filter(e -> e.getValue().hasAllRequiredItems(input))
                .map(e -> new WorkbenchRecipe.RecipeEntry(e.getKey(), e.getValue()))
                .sorted(WorkbenchRecipe.ENTRY_COMPARATOR)
                .toList();
        }
        Map<ResourceLocation, WorkbenchRecipe> filtered = new java.util.LinkedHashMap<>();
        for (ResourceLocation holderId : candidates) {
            WorkbenchRecipe r = all.get(holderId);
            // Key the result by the holderId we looked up, not by recipe.getId():
            // the internal location is only pinned to the holder ID by loadRecipes
            // and can differ (or collide) for wrappers/reused instances; every other
            // path in this class keys entries by the recipes() map key.
            if (r != null && r.quickCheck(input) && r.hasAllRequiredItems(input)) {
                filtered.put(holderId, r);
            }
        }
        return filtered.entrySet().stream()
            .map(e -> new WorkbenchRecipe.RecipeEntry(e.getKey(), e.getValue()))
            .sorted(WorkbenchRecipe.ENTRY_COMPARATOR)
            .toList();
    }

    /**
     * Returns the union of RecipeHolder IDs that share at least one Item with
     * the non-empty inventory. An empty inventory (or an inventory whose Items
     * are not in the index) returns an empty set; the caller treats that as a
     * signal to fall back to the full scan.
     */
    private Set<ResourceLocation> collectCandidateIds(List<ItemStack> input) {
        if (cachedItemIndex.isEmpty()) return Collections.emptySet();
        Set<ResourceLocation> hits = new HashSet<>();
        for (ItemStack s : input) {
            if (s.isEmpty()) continue;
            Set<ResourceLocation> rids = cachedItemIndex.get(s.getItem());
            if (rids != null) hits.addAll(rids);
        }
        return hits;
    }

    /**
     * Returns the current RecipeManager on this side:
     * the running server's manager (single-player integrated servers also expose
     * it here), falling back to the client's connection-synced manager, or null
     * when neither is available yet.
     */
    private RecipeManager currentManager() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getRecipeManager();
        }
        // Server-only path. The client manager is below; reaching this branch on a
        // dedicated server (where ServerLifecycleHooks.getCurrentServer() briefly
        // returns null during early init) would dereference Minecraft.getInstance(),
        // which on a dedicated server returns null rather than throwing — leading
        // to a NullPointerException on the next call. Guard with the loader Dist to
        // skip the client branch on a dedicated server.
        if (!net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
            return null;
        }
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return null;
            var connection = mc.getConnection();
            if (connection != null) {
                return connection.getRecipeManager();
            }
        } catch (RuntimeException ignored) {
            // Minecraft not fully initialized yet — fall through to null.
        }
        return null;
    }

    private Map<ResourceLocation, WorkbenchRecipe> loadRecipes(RecipeManager manager) {
        Map<ResourceLocation, WorkbenchRecipe> result = new HashMap<>();
        var allRecipes = manager.getRecipes();
        ECTMod.LOGGER.info("RecipeFinder: scanning {} recipe(s) for WorkbenchRecipe instances", allRecipes.size());
        // Capture a real registry access so getResultItem(provider) does not receive null,
        // which some mod CraftingRecipe implementations dereference (NPE). Fall back to null
        // only when no side is available yet; the wrap is guarded below so a failure just skips.
        HolderLookup.Provider registries = null;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            registries = server.registryAccess();
        } else {
            try {
                var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
                if (connection != null) registries = connection.registryAccess();
            } catch (RuntimeException ignored) { }
        }
        final HolderLookup.Provider finalRegistries = registries;
        // Iterate over the Collection<RecipeHolder<?>>; each RecipeHolder carries
        // both the holder ID (id()) and the recipe value (value()).
        manager.getRecipes().forEach(recipeHolder -> {
            try {
                if (recipeHolder.value() instanceof WorkbenchRecipe wbRecipe) {
                    if (wbRecipe.getId() == null) {
                        wbRecipe.setLocation(recipeHolder.id());
                    }
                    result.put(recipeHolder.id(), wbRecipe);
                } else if (recipeHolder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe) {
                    // Wrap vanilla crafting-table recipes (shaped/shapeless) so the
                    // workbench can craft any vanilla recipe without custom JSON files.
                    result.put(recipeHolder.id(),
                        new VanillaWorkbenchRecipe(recipeHolder.id(), craftingRecipe, finalRegistries));
                }
            } catch (RuntimeException e) {
                // A single malformed/modded recipe must not fail the whole cache build.
                ECTMod.LOGGER.warn("Skipping recipe {}: {}", recipeHolder.id(), e.getMessage());
            }
        });
        if (result.isEmpty()) {
            ECTMod.LOGGER.warn("No WorkbenchRecipe loaded from RecipeManager (manager={}, {} total recipes, {} WorkbenchRecipe found); check recipe JSON format and serializer registration.",
                System.identityHashCode(manager), allRecipes.size(), result.size());
        } else {
            ECTMod.LOGGER.info("Loaded {} WorkbenchRecipe(s) from RecipeManager.", result.size());
        }
        return result;
    }
}