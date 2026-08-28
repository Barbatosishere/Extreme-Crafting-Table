package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import com.extremecraftingtable.packet.ClientSync;
import com.extremecraftingtable.packet.ClientSyncPayload;
import com.extremecraftingtable.utils.CheckerLog;
import com.extremecraftingtable.utils.InvUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TileWorkbench extends BlockEntity implements Container, MenuProvider, CheckerLog, ClientSync {
    public static final int ONE_FE = 1;
    /** Max items per ingredient slot. 1.21.1's ItemStack count codec is a flat
     *  {@code ExtraCodecs.intRange(1, 99)} (the range is NOT tied to the stack's
     *  {@code max_stack_size} component — that coupling only exists in 1.21.2+),
     *  so any count above 99 fails {@link ItemStack#save}. The save path clamps
     *  to 64 and stores the real count in a side tag ("SlotCounts") instead. */
    public static final int MAX_SLOT_STACK = 81920;

    // Capability instances, exposed as a standard NeoForge IEnergyStorage wrapper
    // around the internal energy fields and an IItemHandler wrapper around the
    // ingredient inventory (recipe preview slots are excluded from the handler).
    private final IEnergyStorage energyStorage = new WorkbenchEnergyStorage();
    private final IItemHandler itemHandler = new WorkbenchItemHandler();

    final NonNullList<ItemStack> ingredientInventory = NonNullList.withSize(27, ItemStack.EMPTY);
    final NonNullList<ItemStack> selectionInventory = NonNullList.withSize(18, ItemStack.EMPTY);
    // Output cache slot (index 45): crafted items are deposited here instead of
    // dropping to the ground immediately. Players take results from this slot.
    final NonNullList<ItemStack> outputInventory = NonNullList.withSize(1, ItemStack.EMPTY);

    // Volatile: these fields are read on the client thread (fromClientTag,
    // ContainerWorkbench.broadcastChanges, ScreenWorkbench.renderRecipeSelection)
    // and written on the server thread (tick, setItem, removeItem). Today the
    // server and client are separate Java instances (no shared state across
    // sides), but marking them volatile future-proofs the BE against a path
    // that mutates these fields from a different in-process thread (e.g. a
    // payload handler, a /reload-time callback, or a future capability-driven
    // update that bypasses the menu).
    public volatile List<WorkbenchRecipe.RecipeEntry> recipesList = Collections.emptyList();
    // Inventory fingerprint to skip re-scanning the (potentially 2000+) vanilla
    // recipe set when the ingredient contents did not change. Keyed on the 27
    // ingredient slots; any item/count change invalidates it, and the /reload
    // case is handled by the RecipeFinder's own manager-keyed cache.
    private volatile int inventoryFingerprint = -1;
    private volatile List<WorkbenchRecipe.RecipeEntry> cachedRecipes = null;
    private volatile WorkbenchRecipe currentRecipe = WorkbenchRecipe.dummyRecipe();
    public boolean workContinue;
    private final List<Player> openPlayers = new CopyOnWriteArrayList<>();
    /**
     * Global weak index of every live {@link TileWorkbench} keyed by
     * {@link BlockPos}, used by {@link #sweepOpenPlayer(Player)} to scrub
     * disconnected players out of the per-tile {@link #openPlayers} list
     * without iterating every loaded chunk. Entries are added in the
     * constructor and removed in {@link #setRemoved()}.
     */
    /**
     * Global index of server-side live workbench tiles, keyed by
     * {@link GlobalPos} (dimension + pos). The dimension qualifier matters: two
     * workbenches at the same x/y/z in different dimensions (portal-linked
     * bases) would otherwise evict each other from a bare-BlockPos map, and the
     * evicted tile would silently stop being reached by sweepOpenPlayer /
     * clearAllOpenPlayers / applyConfigCapacity. Registered in {@link #onLoad()}
     * (where the level/dimension is known) and removed in {@link #setRemoved()}.
     */
    private static final Map<GlobalPos, TileWorkbench> LIVE_TILES = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The inventory fingerprint at the moment the most recent
     * {@code hasAllRequiredItems} check passed. While this matches the current
     * {@link #inventoryFingerprint}, the selected recipe's ingredients are still
     * satisfied and {@code tick()} can skip the 27-stack copy + backtracking
     * pass — saving O(alternatives × stacks) work per tick for every active
     * workbench. Invalidated by {@link #updateRecipeList} (which always
     * recomputes the fingerprint) and by a recipe switch.
     */
    private int lastCraftSatisfiedFingerprint = Integer.MIN_VALUE;

    // Volatile: applyConfigCapacity runs on the NightConfig file-watcher thread
    // (ModConfigEvent.Reloading does not fire on the server thread) and clamps
    // this field while the server thread mutates it in tick()/receiveEnergy().
    private volatile int energy;
    // Note: not final — Config.Reloading mutates them so config-file changes pick up
    // workbenchCapacity / workbenchMaxReceive changes for every live tile
    // (BUG-R3C1). Reads in hot paths (tick, getMaxEnergy) are still single
    // int loads, so the relaxed semantics are not observable from vanilla
    // single-threaded paths. A volatile read is enough because the only
    // writer is the Reloading handler on the NightConfig watcher thread.
    private volatile int maxEnergy;
    private volatile int maxReceive;

    // Pending recipe ID for deferred restoration when level is not yet available (Bug #2 fix)
    @Nullable
    private ResourceLocation pendingRecipeId = null;

    // Change detection for network sync optimization (Issue #7)
    private int lastSyncedEnergy = -1;
    @Nullable
    private ResourceLocation lastSyncedRecipeId = null;
    private boolean lastSyncedHasRecipe = false;

    public TileWorkbench(BlockPos pos, BlockState state) {
        // WORKBENCH_TYPE is now typed BlockEntityType<TileWorkbench>, so the unchecked
        // wildcard cast is no longer needed. Access via getWorkbenchType(): a single
        // writer (Registration) publishes the type, multiple readers (tile instances,
        // including during construction) read it, so a volatile read is sufficient.
        super(com.extremecraftingtable.Registration.WORKBENCH_TYPE.get(), pos, state);
        // ECTMod.config is initialized before block entities are created, so null check is unnecessary
        this.maxEnergy = ECTMod.config.workbenchCapacity.get();
        this.maxReceive = ECTMod.config.workbenchMaxReceive.get();
        // NOTE: not registered in LIVE_TILES here — the dimension is not known at
        // construction time (level is null until setLevel), and the map is keyed
        // by GlobalPos. Registration happens in onLoad().
    }

    @Override
    public void setRemoved() {
        // BUG-C1 / BUG-C2 fix: a removed BE must release its player references
        // — otherwise any player who happened to have a menu open at the moment
        // of chunk unload would have their ServerPlayer pinned in
        // openPlayers until the chunk is reloaded-and-rebuilt.
        openPlayers.clear();
        if (level != null) {
            // Two-arg value-checked remove keyed by GlobalPos: a same-coordinates
            // tile in another dimension can never evict this entry's key.
            LIVE_TILES.remove(GlobalPos.of(level.dimension(), getBlockPos()), this);
        }
        super.setRemoved();
    }

    /**
     * Remove a player from every live workbench's {@code openPlayers} list.
     * Called from {@link net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent}.
     * Uses identity comparison so a recycled player id (rare, but possible on
     * a /reload + reconnect) cannot accidentally match a different player.
     */
    public static void sweepOpenPlayer(Player player) {
        // Fast path: only touch a tile's openPlayers list if the player is
        // actually in it. CopyOnWriteArrayList.removeIf is O(n) per call
        // (it copies the whole backing array), and a /reload + reconnect
        // can move a player through hundreds of workbenches per second.
        for (TileWorkbench wb : LIVE_TILES.values()) {
            if (wb.openPlayers.contains(player)) {
                wb.openPlayers.removeIf(p -> p == player);
            }
        }
    }

    /**
     * Drop every open player from every live workbench. Called on
     * {@link net.neoforged.neoforge.event.server.ServerStoppingEvent}
     * before the player objects become invalid.
     */
    public static void clearAllOpenPlayers() {
        for (TileWorkbench wb : LIVE_TILES.values()) {
            wb.openPlayers.clear();
        }
    }

    /**
     * Push the latest config values into every live workbench. Called by
     * {@link com.extremecraftingtable.ECTMod} on
     * {@code ModConfigEvent.Reloading}. If the new capacity is smaller than
     * the currently stored energy we clamp down to the new cap; if it is
     * larger the existing energy is left alone and the tile may simply
     * accept more energy on the next {@code receiveEnergy} call.
     */
    public static void applyConfigCapacity(int newCapacity, int newMaxReceive) {
        for (TileWorkbench wb : LIVE_TILES.values()) {
            wb.maxEnergy = newCapacity;
            wb.maxReceive = newMaxReceive;
            if (wb.energy > newCapacity) wb.energy = newCapacity;
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TileWorkbench wb) { wb.tick(); }

    /**
     * Returns true when the currently selected recipe can be crafted from the
     * current ingredient inventory. Caches the inventory fingerprint in
     * {@link #lastCraftSatisfiedFingerprint} on success so subsequent ticks
     * with an unchanged inventory can skip the backtracking check.
     */
    private boolean isCraftSatisfied() {
        if (lastCraftSatisfiedFingerprint == inventoryFingerprint) return true;
        if (currentRecipe.hasAllRequiredItems(ingredientInventory)) {
            lastCraftSatisfiedFingerprint = inventoryFingerprint;
            return true;
        }
        return false;
    }

    private void tick() {
        if (level == null || level.isClientSide()) return;
        // This block runs exclusively on the server tick loop thread (single-threaded),
        // so no synchronization is needed between players; the old craftLock/isCrafting
        // guard rejected re-entrant crafting that the server thread cannot produce.
        // The fingerprint gate at the end short-circuits the 27-stack copy +
        // backtracking pass when the selected recipe's ingredients are still
        // satisfied by the same inventory state.
        if (currentRecipe.hasContent()
            && (ECTMod.config.noEnergy.get() || currentRecipe.getRequiredEnergy() <= getEnergy())
            && isCraftSatisfied()) {
            // Bug #1: Pass a defensive copy so recipe subclasses that modify the
            // inventory in getOutput (e.g. via ItemStack.shrink) do not cause
            // double consumption — the explicit consumeItems call below is the
            // sole owner of inventory mutation.
            ItemStack created = currentRecipe.getOutput(
                ingredientInventory.stream().map(ItemStack::copy).collect(Collectors.toCollection(ArrayList::new)),
                level.registryAccess());
            // Bug fix: Guard against an empty output stack.  hasContent() checks the
            // recipe's static output field, but a custom recipe subclass may override
            // getOutput() and return an empty stack.  If we passed an empty stack to
            // injectToNearTile, it would return ItemStack.EMPTY (the "successfully
            // placed" sentinel), causing the caller to consume items for nothing.
            if (!created.isEmpty()) {
                // Output-cache-first: deposit the crafted stack into the output slot
                // instead of dropping it to the ground immediately (Mekanism-style).
                // If the slot holds a different item or is full, the craft is deferred
                // (nothing is consumed) until the player clears the slot.
                ItemStack outputStack = outputInventory.get(0);
                boolean canCraft = false;
                if (outputStack.isEmpty()) {
                    outputInventory.set(0, created.copy());
                    canCraft = true;
                } else if (ItemStack.isSameItemSameComponents(outputStack, created)) {
                    int maxStack = Math.min(outputStack.getMaxStackSize(), getMaxStackSize());
                    int room = maxStack - outputStack.getCount();
                    if (room >= created.getCount()) {
                        outputStack.grow(created.getCount());
                        canCraft = true;
                    } else if (room > 0) {
                        // Partial fit: fill the slot, then try to drop the remainder.
                        int placed = room;
                        outputStack.setCount(outputStack.getCount() + placed);
                        created.shrink(placed);
                        ItemStack leftover = InvUtils.injectToNearTile(level, getBlockPos(), created);
                        if (leftover.isEmpty()) {
                            canCraft = true;
                        } else {
                            // Rollback: remainder could not be dropped this tick.
                            outputStack.shrink(placed);
                        }
                    }
                    // else room == 0: slot full, defer.
                }
                // else different item in output slot: defer.

                if (canCraft) {
                    currentRecipe.consumeItems(ingredientInventory);
                    // Bug fix: noEnergy mode should not drain energy (config says "no external power needed")
                    if (!ECTMod.config.noEnergy.get()) {
                        useEnergy((int) Math.min(currentRecipe.getRequiredEnergy(), Integer.MAX_VALUE));
                    }
                    setChanged();
                    // Bug fix: Save the RecipeHolder ID BEFORE updateRecipeOutputs clears it (workContinue fix)
                    // Use the holderId from the recipe list entry, not the recipe's internal getId().
                    // When the recipe is not in the local list (finder fallback path), look up
                    // the holder ID from the RecipeFinder's recipes map by recipe identity.
                    ResourceLocation currentHolderId = currentRecipe.hasContent()
                        ? (!recipesList.isEmpty()
                            ? recipesList.stream()
                                .filter(e -> e.recipe() == currentRecipe)
                                .findFirst().map(WorkbenchRecipe.RecipeEntry::holderId)
                                .orElseGet(() -> findHolderIdInFinder(currentRecipe))
                            : findHolderIdInFinder(currentRecipe))
                        : currentRecipe.getId();
                    updateRecipeOutputs();
                    setCurrentRecipe(workContinue ? currentHolderId : null);
                }
            }
        }
        // No passive self-charge: like a Mekanism machine, energy is only gained
        // by receiving it through the IEnergyStorage capability from an external
        // power source (energy cables/cubes, e.g. Mekanism Universal Cable/Energy
        // Cube). The machine drains its internal buffer to craft and stops when
        // it runs dry, exactly like a Mekanism machine with no active reactor.
        // Issue #7 fix: Only send sync when data actually changed.
        // Note: workContinue is synced through the container menu's data slots,
        // not through the block entity sync packet, so it is excluded from the
        // change detection. The recipe change (from workContinue restoring the
        // recipe after crafting) is already detected by the recipe ID check below.
        if (!openPlayers.isEmpty()) {
            boolean hasRecipe = currentRecipe.hasContent();
            boolean changed = energy != lastSyncedEnergy
                || lastSyncedHasRecipe != hasRecipe
                || (hasRecipe && !currentRecipe.getId().equals(lastSyncedRecipeId));
            if (changed) {
                lastSyncedEnergy = energy;
                lastSyncedHasRecipe = hasRecipe;
                lastSyncedRecipeId = hasRecipe ? currentRecipe.getId() : null;
                ClientSyncPayload.sendToClient(this, level);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, ingredientInventory, registries);
        // Restore the actual slot counts (we save them clamped to 64 and store
        // the real count in a side tag to dodge the ItemStack [1;99] codec range).
        restoreSlotCounts(tag);
        // Restore the selection inventory from NBT under its dedicated key.
        // ContainerHelper.loadAllItems only reads the "Items" key, so the
        // "SelectionItems" list is restaged under "Items" in a scratch tag.
        // This is derived data (regenerated from the finder on ingredient
        // changes), but preserving it avoids an empty state right after a
        // chunk reload before the first recipe-list refresh.
        if (tag.contains("SelectionItems", Tag.TAG_LIST)) {
            CompoundTag selectionNbt = new CompoundTag();
            selectionNbt.put("Items", tag.getList("SelectionItems", Tag.TAG_COMPOUND));
            ContainerHelper.loadAllItems(selectionNbt, selectionInventory, registries);
            // Restore real counts from the side tag (see saveAdditional).
            if (tag.contains("SelectionSlotCounts", Tag.TAG_LIST)) {
                ListTag sc = tag.getList("SelectionSlotCounts", Tag.TAG_INT);
                int n = Math.min(sc.size(), selectionInventory.size());
                for (int i = 0; i < n; i++) {
                    int real = sc.getInt(i);
                    if (real > 0 && i < selectionInventory.size()
                        && !selectionInventory.get(i).isEmpty()) {
                        // Upper clamp mirrors restoreSlotCounts: a hand-edited tag
                        // must not load an absurd count into the preview slots.
                        selectionInventory.get(i).setCount(Math.min(real, MAX_SLOT_STACK));
                    }
                }
            }
        }
        // Restore the single output cache slot from its own key.
        if (tag.contains("OutputItem", Tag.TAG_LIST)) {
            CompoundTag outputNbt = new CompoundTag();
            outputNbt.put("Items", tag.getList("OutputItem", Tag.TAG_COMPOUND));
            ContainerHelper.loadAllItems(outputNbt, outputInventory, registries);
            // Restore the real count for the output slot (clamped to 64 during save).
            if (tag.contains("OutputSlotCount", Tag.TAG_LIST)) {
                var list = tag.getList("OutputSlotCount", Tag.TAG_INT);
                if (!list.isEmpty() && !outputInventory.get(0).isEmpty()) {
                    // Clamp to the item's actual maxStackSize to defend against
                    // externally edited NBT (cheat tools, manual disk edits) that
                    // could otherwise inject a count larger than the slot can hold
                    // and would make tick() defer crafting forever.
                    int loaded = list.getInt(0);
                    int maxStack = Math.min(outputInventory.get(0).getMaxStackSize(), MAX_SLOT_STACK);
                    // Clamp to the item's actual maxStackSize to defend against
                    // externally edited NBT (cheat tools, manual disk edits) that
                    // could otherwise inject a count larger than the slot can hold
                    // and would make tick() defer crafting forever. Math.max(0, ...)
                    // also rejects negative values that ItemStack.setCount would
                    // otherwise accept unconditionally, producing an invalid
                    // (negative-count) ItemStack.
                    outputInventory.get(0).setCount(Math.max(0, Math.min(loaded, maxStack)));
                }
            }
        }
        // Clamp to maxEnergy: a config change reducing workbenchCapacity must not
        // leave the machine over its new maximum after a reload from disk.
        energy = Math.max(0, Math.min(tag.getInt("energy"), maxEnergy));
        workContinue = tag.getBoolean("workContinue");
        pendingRecipeId = null;
        if (tag.contains("recipe")) {
            String recipeStr = tag.getString("recipe");
            if (!recipeStr.isEmpty()) {
                ResourceLocation recipeId = ResourceLocation.tryParse(recipeStr);
                // null triggers the dummy fallback inside setCurrentRecipe (no valid holder ID)
                ResourceLocation finalRecipeId = recipeId;
                // Defer: level is null during chunk load; restore in onLoad()
                if (level != null && level.getServer() != null) level.getServer().executeIfPossible(() -> {
                    updateRecipeOutputs();
                    setCurrentRecipe(finalRecipeId);
                });
                // Bug #2 fix: Save pending recipe ID if level is not yet available (server null is also deferred)
                else pendingRecipeId = finalRecipeId;
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Register in the global tile index (server side) so PlayerLoggedOutEvent /
        // ServerStoppingEvent can scrub openPlayers without scanning chunks, and so
        // applyConfigCapacity reaches every loaded tile. Keyed by GlobalPos so
        // same-coordinates tiles in different dimensions cannot collide. The level
        // is null during construction; only here is the dimension known.
        if (level != null && !level.isClientSide()) {
            LIVE_TILES.put(GlobalPos.of(level.dimension(), getBlockPos()), this);
        }
        // Defer: level is null during chunk load; restore in onLoad()
        ResourceLocation deferredRecipe = pendingRecipeId;
        if (deferredRecipe != null && level != null) {
            pendingRecipeId = null;
            if (level.isClientSide()) {
                // Bug #1 fix: On the client, level.getServer() is always null, so the old
                // server-only guard meant the deferred restore never fired and the client
                // recipe stayed stale after a chunk reload. Restore directly on the client
                // thread (there is no cross-thread hop needed), mirroring fromClientTag.
                // Do NOT call updateRecipeList here: it would clear the selection inventory
                // and may clobber recipesList. setCurrentRecipe already falls back to the
                // RecipeFinder map when the client's list is empty (see fromClientTag).
                setCurrentRecipe(deferredRecipe);
            } else {
                // Server side: defer to the next tick so the recipe is applied only
                // after the chunk / block entity is fully registered.
                if (level.getServer() != null) {
                    level.getServer().executeIfPossible(() -> {
                        updateRecipeOutputs();
                        setCurrentRecipe(deferredRecipe);
                    });
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Save the actual count of each slot separately. ItemStack.save() rejects
        // a count > 99 with "[1;99] out of range", so we write the real count
        // to a side tag ("SlotCounts") and clamp the stack to 64 before the
        // standard ContainerHelper.saveAllItems call.
        int[] slotCounts = new int[ingredientInventory.size()];
        {
            ListTag list = new ListTag();
            for (int i = 0; i < ingredientInventory.size(); i++) {
                ItemStack stack = ingredientInventory.get(i);
                int c = stack.isEmpty() ? 0 : stack.getCount();
                slotCounts[i] = c;
                list.add(IntTag.valueOf(c));
            }
            tag.put("SlotCounts", list);
        }
        // Clamp stacks to 64 so the codec accepts them, then save, then restore.
        for (int i = 0; i < ingredientInventory.size(); i++) {
            if (slotCounts[i] > 64) {
                ingredientInventory.get(i).setCount(64);
            }
        }
        ContainerHelper.saveAllItems(tag, ingredientInventory, registries);
        for (int i = 0; i < ingredientInventory.size(); i++) {
            if (slotCounts[i] > 64) {
                ingredientInventory.get(i).setCount(slotCounts[i]);
            }
        }
        // Save the selection inventory under a dedicated key. Clamp to 64 first
        // and remember each real count so we can restore the in-memory stacks
        // after saveAllItems. Because selection slots can hold >64 (vanilla's
        // 99-cap codec is the same [1;99] range we dodge on the output slot),
        // we also write a per-slot side tag — without it a chunk save+reload
        // would silently drop every selection count above 64.
        int[] selectionCounts = new int[selectionInventory.size()];
        boolean anySelectionClamped = false;
        for (int i = 0; i < selectionInventory.size(); i++) {
            ItemStack stack = selectionInventory.get(i);
            selectionCounts[i] = stack.isEmpty() ? 0 : stack.getCount();
            if (!stack.isEmpty() && stack.getCount() > 64) {
                stack.setCount(64);
                anySelectionClamped = true;
            }
        }
        CompoundTag selectionNbt = new CompoundTag();
        ContainerHelper.saveAllItems(selectionNbt, selectionInventory, registries);
        tag.put("SelectionItems", selectionNbt.getList("Items", Tag.TAG_COMPOUND));
        if (anySelectionClamped) {
            ListTag selectionCountsTag = new ListTag();
            for (int i = 0; i < selectionCounts.length; i++) {
                if (selectionCounts[i] > 0 && selectionCounts[i] != selectionInventory.get(i).getCount()) {
                    selectionCountsTag.add(IntTag.valueOf(selectionCounts[i]));
                } else {
                    // Pad the list to the slot index so loadAdditional can map
                    // counts back 1:1 with the selectionInventory slots.
                    selectionCountsTag.add(IntTag.valueOf(0));
                }
            }
            tag.put("SelectionSlotCounts", selectionCountsTag);
        }
        for (int i = 0; i < selectionInventory.size(); i++) {
            if (selectionCounts[i] > 64) {
                selectionInventory.get(i).setCount(selectionCounts[i]); // restore in-memory
            }
        }
        // Save the single output cache slot under its own key.
        // Clamp to 64 first (same as ingredient slots), and store the real count
        // in a side tag so the [1;99] codec range does not truncate it.
        ItemStack outputStack = outputInventory.get(0);
        int outputCount = outputStack.isEmpty() ? 0 : outputStack.getCount();
        if (outputCount > 64) outputStack.setCount(64);
        CompoundTag outputNbt = new CompoundTag();
        ContainerHelper.saveAllItems(outputNbt, outputInventory, registries);
        tag.put("OutputItem", outputNbt.getList("Items", Tag.TAG_COMPOUND));
        if (outputCount > 64) {
            outputStack.setCount(outputCount); // restore
            ListTag outputCounts = new ListTag();
            outputCounts.add(IntTag.valueOf(outputCount));
            tag.put("OutputSlotCount", outputCounts);
        }
        tag.putInt("energy", energy);
        tag.putBoolean("workContinue", workContinue);
        // Save the holder ID from the recipe list entry, not the recipe's internal getId().
        // loadAdditional restores via setCurrentRecipe, which resolves by holderId;
        // this also feeds getUpdateTag/handleUpdateTag, so a mismatch would drop the
        // client-side recipe restore to dummyRecipe().
        // When the recipe is not in the local list (finder fallback path), look up
        // the holder ID from the RecipeFinder's recipes map by recipe identity.
        ResourceLocation holderId = currentRecipe.hasContent()
            ? (!recipesList.isEmpty()
                ? recipesList.stream()
                    .filter(e -> e.recipe() == currentRecipe)
                    .findFirst().map(WorkbenchRecipe.RecipeEntry::holderId)
                    .orElseGet(() -> findHolderIdInFinder(currentRecipe))
                : findHolderIdInFinder(currentRecipe))
            : currentRecipe.getId();
        tag.putString("recipe", holderId != null ? holderId.toString() : "");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
        // Bug #2 fix: Restore recipe state on the client during chunk reload.
        // The update tag carries the recipe ID; fromClientTag applies it to the client-side
        // tile entity so the GUI does not show a stale dummy recipe.
        // NOTE: loadAdditional above sets pendingRecipeId on the client (because
        // level.getServer() is null), but onLoad() has already fired before
        // handleUpdateTag() during chunk loading. Clear pendingRecipeId here so
        // the stale value is not picked up by a subsequent onLoad() call.
        if (level != null && level.isClientSide()) {
            fromClientTag(tag);
            pendingRecipeId = null;
        }
    }

    @Override
    public CompoundTag toClientTag(CompoundTag nbt) {
        nbt.putInt("energy", energy);
        // Use the holder ID from the recipe list entry, not the recipe's internal getId().
        // fromClientTag resolves by holderId, so sending the internal ID would cause
        // the client-side recipe restore to fall back to dummyRecipe().
        // When the recipe is not in the local list (finder fallback path), look up
        // the holder ID from the RecipeFinder's recipes map by recipe identity.
        ResourceLocation holderId = currentRecipe.hasContent()
            ? (!recipesList.isEmpty()
                ? recipesList.stream()
                    .filter(e -> e.recipe() == currentRecipe)
                    .findFirst().map(WorkbenchRecipe.RecipeEntry::holderId)
                    .orElseGet(() -> findHolderIdInFinder(currentRecipe))
                : findHolderIdInFinder(currentRecipe))
            : currentRecipe.getId();
        nbt.putString("recipe", holderId != null ? holderId.toString() : "");
        return nbt;
    }

    @Override
    public void fromClientTag(CompoundTag nbt) {
        // Bug fix: clear pendingRecipeId so that onLoad() does not try to restore
        // a stale recipe ID from a previous loadAdditional() call. The recipe
        // is restored from nbt below, so no deferred restore is needed.
        pendingRecipeId = null;
        // Clamp to maxEnergy, consistent with loadAdditional(): the two client-state
        // restore paths (chunk sync tag and network payload) must not diverge.
        energy = Math.max(0, Math.min(nbt.getInt("energy"), maxEnergy));
        if (level != null) {
            updateRecipeList(level.registryAccess());
        }
        if (nbt.contains("recipe")) {
            String recipeStr = nbt.getString("recipe");
            if (!recipeStr.isEmpty()) {
                ResourceLocation holderId = ResourceLocation.tryParse(recipeStr);
                if (holderId == null) {
                    currentRecipe = WorkbenchRecipe.dummyRecipe();
                    return;
                }
                // Use the RecipeHolder ID from the entry (not the recipe's internal getId()).
                // When recipesList is empty (e.g. on the client after chunk reload), fall
                // back to the RecipeFinder's recipe map which also indexes by holder ID.
                currentRecipe = recipesList.stream()
                    .filter(r -> holderId.equals(r.holderId()))
                    .findFirst().map(WorkbenchRecipe.RecipeEntry::recipe).orElseGet(() -> {
                        var recipe = WorkbenchRecipe.getRecipeFinder().recipes().get(holderId);
                        return recipe != null ? recipe : WorkbenchRecipe.dummyRecipe();
                    });
            } else {
                // Bug fix: empty recipe string must reset to dummy, otherwise a stale
                // recipe persists on the client when the server clears the recipe.
                currentRecipe = WorkbenchRecipe.dummyRecipe();
            }
        }
    }

    private static final int OUTPUT_INDEX = 27 + 18; // 45

    @Override public int getContainerSize() { return 27 + 18 + 1; }
    @Override public boolean isEmpty() { return ingredientInventory.stream().allMatch(ItemStack::isEmpty) && outputInventory.get(0).isEmpty(); }

    @Override
    public ItemStack getItem(int index) {
        if (index >= 0 && index < ingredientInventory.size())
            return ingredientInventory.get(index);
        if (index >= ingredientInventory.size() && index < OUTPUT_INDEX)
            return selectionInventory.get(index - ingredientInventory.size());
        if (index == OUTPUT_INDEX)
            return outputInventory.get(0);
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        if (index >= 0 && index < ingredientInventory.size()) {
            ItemStack stack = ContainerHelper.removeItem(ingredientInventory, index, count);
            // Mark the BE dirty so the chunk saves the new ingredient counts; see
            // the matching comment in setItem() for why AbstractContainerMenu does
            // not auto-call setChanged on the underlying BE.
            setChanged();
            updateRecipeOutputs();
            return stack;
        }
        if (index >= ingredientInventory.size() && index < OUTPUT_INDEX)
            // Selection slots hold derived recipe-output previews, not player-owned
            // items. Never allow extraction through the Container API (the GUI layer
            // already guards via pickable=false); returning EMPTY here blocks a
            // direct-API duplication vector where a caller extracts a preview and a
            // later updateRecipeList() repopulates it for free.
            return ItemStack.EMPTY;
        if (index == OUTPUT_INDEX) {
            // Output slot is player-owned: extraction is allowed. Mark the BE dirty
            // so the chunk saves the new output count — without this, a player who
            // takes the crafted item from the cache slot risks the change being
            // dropped on chunk unload or crash.
            ItemStack taken = ContainerHelper.removeItem(outputInventory, 0, count);
            setChanged();
            return taken;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        if (index >= 0 && index < ingredientInventory.size()) {
            // Ingredient slots are player-owned — like the output slot we must
            // mark the BE dirty explicitly so the new count reaches disk
            // before a chunk unload or autosave tick drops it.
            ItemStack taken = ContainerHelper.takeItem(ingredientInventory, index);
            setChanged();
            return taken;
        }
        if (index >= ingredientInventory.size() && index < OUTPUT_INDEX)
            // See removeItem(): never expose derived previews to the Container API.
            return ItemStack.EMPTY;
        if (index == OUTPUT_INDEX) {
            // Output slot is player-owned. AbstractContainerMenu's removeItemNoUpdate
            // contract does not auto-call setChanged; mark the BE dirty here so
            // the chunk saves the new output count.
            ItemStack taken = ContainerHelper.takeItem(outputInventory, 0);
            setChanged();
            return taken;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index >= 0 && index < ingredientInventory.size()) {
            ingredientInventory.set(index, stack);
            // Mark the BE dirty: AbstractContainerMenu's setItem does NOT auto-call
            // setChanged on the underlying BE, so without this the chunk only saves
            // the new ingredient slot on a periodic autosave / chunk unload, risking
            // item loss across crashes. updateRecipeOutputs already mutates derived
            // state but never sets the dirty flag.
            setChanged();
            updateRecipeOutputs();
        } else if (index >= ingredientInventory.size() && index < OUTPUT_INDEX) {
            selectionInventory.set(index - ingredientInventory.size(), stack);
        } else if (index == OUTPUT_INDEX) {
            // Output slot is a player-facing cache, not a recipe input: no recipe recompute.
            // Mark the BE dirty so the new output count is persisted before chunk unload.
            outputInventory.set(0, stack);
            setChanged();
        }
    }

    @Override
    public int getMaxStackSize() { return MAX_SLOT_STACK; }

    /** Restores the real per-slot counts that were clamped to 64 during save
     *  (ItemStack.save() rejects counts > 99). Wired in loadAdditional. */
    private void restoreSlotCounts(CompoundTag tag) {
        if (!tag.contains("SlotCounts", Tag.TAG_LIST)) return;
        var list = tag.getList("SlotCounts", Tag.TAG_INT);
        int n = Math.min(list.size(), ingredientInventory.size());
        for (int i = 0; i < n; i++) {
            int c = list.getInt(i);
            if (c > 0 && !ingredientInventory.get(i).isEmpty()) {
                ingredientInventory.get(i).setCount(Math.min(c, MAX_SLOT_STACK));
            }
        }
    }
    @Override public void clearContent() { for (int i = 0; i < ingredientInventory.size(); i++) ingredientInventory.set(i, ItemStack.EMPTY); for (int i = 0; i < selectionInventory.size(); i++) selectionInventory.set(i, ItemStack.EMPTY); outputInventory.set(0, ItemStack.EMPTY); updateRecipeOutputs(); setChanged(); }
    @Override public boolean canPlaceItem(int index, ItemStack stack) {
        // Ingredient slots accept anything. Selection slots (27-44) and the
        // output cache (45) reject GUI placement — the output slot is
        // server-driven (setItem / tick craft path), and selection slots hold
        // derived recipe previews and must remain read-only.
        return index >= 0 && index < 27;
    }
    // Menu title: use the dedicated container.* lang key instead of the block's
    // getName(). The block-name fallback made the lang entry dead — translators
    // editing "container.extremecraftingtable.workbench" saw no effect because
    // the GUI title silently resolved to "block.extremecraftingtable.workbench".
    @Override public Component getDisplayName() {
        return Component.translatable("container." + ECTMod.MOD_ID + ".workbench");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new ContainerWorkbench(id, inv, getBlockPos());
    }

    // 1.20.5+ menu redesign: MenuProvider now writes its own extra data to the
    // client via writeClientSideData instead of relying on an external data writer.
    // The block position is written here so the client's ContainerWorkbench factory
    // can locate this tile entity when the menu is created.
    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(getBlockPos());
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    public void startOpen(Player player) {
        openPlayers.add(player);
        // Push an immediate state snapshot so a player who reopens the GUI after
        // walking out of tracking range does not see a stale energy/recipe value
        // until the next tick. Routed to this player only — broadcasting the
        // sync to every chunk tracker would overwrite other players' client-side
        // BEs (e.g. their selected recipe) with ours.
        if (level != null && !level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            ClientSyncPayload.sendToClient(sp, this);
        }
    }
    public void stopOpen(Player player) { openPlayers.remove(player); }
    @Override public void setChanged() { super.setChanged(); }

    public int getEnergy() { return energy; }
    public int getMaxEnergy() { return maxEnergy; }
    /**
     * Drain {@code amount} energy. Always marks the BE dirty so the new
     * {@code energy} value survives an immediate chunk-unload — previously
     * only the {@code tick()}-driven craft path called {@code setChanged()},
     * so a future caller (a debug command, an upgrade module, reflection)
     * draining energy directly would lose the change if the chunk unloaded
     * before the next tick.
     */
    public void useEnergy(int amount) {
        if (amount > 0) {
            energy = Math.max(0, energy - amount);
            setChanged();
        }
    }
    /**
     * Add energy, clamped to the current {@link #maxEnergy} cap (which can
     * be hot-reloaded by {@code ModConfigEvent.Reloading}). Marks dirty
     * for the same reason as {@link #useEnergy(int)}.
     */
    public void addEnergy(int amount) {
        if (amount > 0) {
            energy = (int) Math.min((long) maxEnergy, (long) energy + amount);
            setChanged();
        }
    }

    public int getProgressScaled(int scale) {
        if (currentRecipe.hasContent() && currentRecipe.getRequiredEnergy() > 0)
            return Math.min(scale, (int) ((long) getEnergy() * scale / currentRecipe.getRequiredEnergy()));
        return 0;
    }

    void updateRecipeOutputs() {
        if (level != null) {
            // Refresh the recipe list on both client and server so the recipe
            // previews (selectionInventory) stay current after local ingredient
            // edits. Only the server clears a no-longer-matchable selection.
            updateRecipeList(level.registryAccess());
            if (!level.isClientSide()) {
                // Compare the recipe's own ID, not the RecipeHolder ID: a recipe holder
                // ID may differ from the recipe's internal getId(), and comparing them
                // would spuriously drop stale recipes. This backtracking resets the
                // current recipe only when it is truly no longer produced.
                // When workContinue is active, the ingredient inventory may be
                // temporarily empty between crafts; DO NOT clear the selection in
                // that case, otherwise the machine would never resume once restocked.
                // (The tick() code restores the recipe after its own craft path, but
                // other callers — menu open, setItem, removeItem, clicked — would
                // permanently kill the workContinue selection.)
                if (currentRecipe.hasContent() && !workContinue && recipesList.stream().noneMatch(r -> r.recipe().getId().equals(currentRecipe.getId())))
                    setCurrentRecipe(null); // null resets to the dummy recipe
            }
        }
    }

    @VisibleForTesting
    void updateRecipeList(HolderLookup.Provider access) {
        // Quick fingerprint: if the ingredient slots haven't changed, reuse the
        // last recipe list. This avoids re-scanning the full 2000+ recipe set
        // on every setItem/removeItem/quickMoveStack/clicked/handlePickupAll.
        int fp = 0;
        for (ItemStack stack : ingredientInventory) {
            if (!stack.isEmpty()) {
                fp = 31 * fp + stack.getItem().hashCode();
                fp = 31 * fp + stack.getCount();
            }
        }
        if (fp == inventoryFingerprint && cachedRecipes != null) {
            recipesList = cachedRecipes;
        } else {
            inventoryFingerprint = fp;
            recipesList = WorkbenchRecipe.getRecipeFinder().getRecipes(ingredientInventory);
            cachedRecipes = recipesList;
        }
        // Use replaceAll instead of clear() to avoid shrinking the NonNullList
        // to size 0 (AbstractList.clear() calls removeRange, which removes all
        // elements). The loop below requires the list to still have 18 entries
        // so that selectionInventory.set(i, ...) works correctly.
        selectionInventory.replaceAll(ignored -> ItemStack.EMPTY);
        for (int i = 0; i < recipesList.size() && i < selectionInventory.size(); i++)
            selectionInventory.set(i, recipesList.get(i).recipe().output.copy());
    }

    public void setCurrentRecipe(ResourceLocation recipeName) {
        // Use the RecipeHolder ID from the recipe list entry (not the recipe's internal getId()).
        // When recipesList is empty (e.g. after items were consumed with workContinue active),
        // fall back to the RecipeFinder's recipe map which also indexes by holder ID.
        //
        // Invalidate the craft-satisfied cache whenever the selected recipe
        // changes. The fingerprint only covers ingredient contents, not which
        // recipe is active, so without this reset the next tick would see
        // "fingerprint matches an earlier recipe" and short-circuit
        // hasAllRequiredItems — letting a player switch to a recipe whose
        // ingredients the inventory does not hold, then accept its output and
        // pay only its energy cost (free item exploit).
        WorkbenchRecipe previous = this.currentRecipe;
        if (recipeName == null) {
            this.currentRecipe = WorkbenchRecipe.dummyRecipe();
        } else {
            this.currentRecipe = recipesList.stream()
                .filter(r -> recipeName.equals(r.holderId()))
                .findFirst().map(WorkbenchRecipe.RecipeEntry::recipe).orElseGet(() -> {
                    var recipe = WorkbenchRecipe.getRecipeFinder().recipes().get(recipeName);
                    return recipe != null ? recipe : WorkbenchRecipe.dummyRecipe();
                });
        }
        if (this.currentRecipe != previous) {
            this.lastCraftSatisfiedFingerprint = Integer.MIN_VALUE;
        }
    }

    public WorkbenchRecipe getRecipe() { return currentRecipe; }

    /**
     * Resolves the RecipeHolder ID for a recipe by scanning the RecipeFinder's
     * recipes map by object identity. This is the authoritative fallback when
     * the recipe is not in the local recipesList, because the finder's map is
     * keyed by holder ID (the key that setCurrentRecipe expects).
     * Falls back to the recipe's own internal getId() when the recipe is no
     * longer discoverable in the finder (e.g. removed by a datapack reload).
     */
    private static ResourceLocation findHolderIdInFinder(WorkbenchRecipe recipe) {
        return WorkbenchRecipe.getRecipeFinder().recipes().entrySet().stream()
            .filter(e -> e.getValue() == recipe)
            .findFirst().map(e -> e.getKey()).orElseGet(recipe::getId);
    }

    @Override
    public List<? extends Component> getDebugLogs() {
        return Stream.of(
            "%sRecipe:%s %s".formatted(ChatFormatting.GREEN, ChatFormatting.RESET, currentRecipe),
            "%sWorkContinue:%s %b".formatted(ChatFormatting.GREEN, ChatFormatting.RESET, workContinue),
            "%sRecipe List:%s %s".formatted(ChatFormatting.GREEN, ChatFormatting.RESET, recipesList),
            "Energy: %d/%d FE".formatted(getEnergy(), getMaxEnergy())
        ).map(Component::literal).toList();
    }

    // ========== NeoForge Capabilities ==========

    /**
     * Returns the IEnergyStorage wrapper for external integration (pipes, chargers).
     * Registered via {@link net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent}
     * in {@link ECTMod#commonSetup}.
     */
    public IEnergyStorage getEnergyStorage() { return energyStorage; }

    /**
     * Returns the IItemHandler wrapper for the 27 ingredient slots.
     * Registered via {@link net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent}
     * in {@link ECTMod#commonSetup}.
     */
    public IItemHandler getItemHandler() { return itemHandler; }

    /**
     * IEnergyStorage wrapper around the workbench's internal energy buffer.
     * External receive is capped at the configured maxReceive rate; extraction
     * is always blocked (the workbench is a consumer, not a generator).
     */
    private class WorkbenchEnergyStorage implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (ECTMod.config.noEnergy.get()) return 0;
            // Clamp the incoming amount to both the configured receive rate and
            // the remaining capacity so a single pipe tick cannot exceed the
            // workbench's designed per-tick energy budget. Math.max(0, ...) is
            // a defensive guard for a saturated buffer (energy == maxEnergy) —
            // the IEnergyStorage contract requires the returned value to be
            // non-negative, so we never return the negative delta that an
            // oversize formula would otherwise produce.
            int received = Math.max(0,
                Math.min(maxReceive, Math.min(TileWorkbench.this.maxReceive, TileWorkbench.this.maxEnergy - TileWorkbench.this.energy)));
            if (!simulate && received > 0) {
                TileWorkbench.this.energy += received;
                setChanged();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) { return 0; }

        @Override
        public int getEnergyStored() { return TileWorkbench.this.energy; }

        @Override
        public int getMaxEnergyStored() { return TileWorkbench.this.maxEnergy; }

        @Override
        public boolean canExtract() { return false; }

        @Override
        public boolean canReceive() { return !ECTMod.config.noEnergy.get(); }
    }

    /**
     * IItemHandler wrapper around the 27 ingredient slots.
     * Recipe preview slots (18) are excluded — they carry derived data and must
     * not be externally accessible.
     */
    private class WorkbenchItemHandler implements IItemHandlerModifiable {
        @Override
        public int getSlots() { return TileWorkbench.this.ingredientInventory.size(); }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= getSlots()) return ItemStack.EMPTY;
            // BUG-R6m1 fix: the IItemHandler contract (and NeoForge's javadoc)
            // explicitly says "the returned ItemStack is the *same* as the one
            // stored, do NOT modify it". We used to hand back the live
            // reference, which a careless modder (e.g. AE2's external storage
            // adapter, Logistics Pipes' ModInvUtil) could mutate in place and
            // silently corrupt the slot. A defensive copy costs one allocation
            // per external read and keeps the contract honest.
            return TileWorkbench.this.ingredientInventory.get(slot).copy();
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (slot < 0 || slot >= getSlots()) return;
            TileWorkbench.this.ingredientInventory.set(slot, stack);
            setChanged();
            updateRecipeOutputs();
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= getSlots() || stack.isEmpty()) return stack;
            ItemStack existing = TileWorkbench.this.ingredientInventory.get(slot);
            if (existing.isEmpty()) {
                int maxSize = TileWorkbench.this.getMaxStackSize();
                if (stack.getCount() <= maxSize) {
                    if (!simulate) {
                        // NO MAX_STACK_SIZE stamp here. The earlier BUG-R6C1 revision
                        // stamped DataComponents.MAX_STACK_SIZE=81920 on newly-filled
                        // slots, but 1.21.1's MAX_STACK_SIZE persistent codec is
                        // ExtraCodecs.intRange(1, 99) — encoding 81920 made
                        // ItemStack.save throw, so the first automation insert
                        // permanently broke chunk saves for that chunk (and the
                        // stamped components also broke isSameItemSameComponents for
                        // later merges). Oversized counts survive the save/load round
                        // trip via the SlotCounts clamp+restore side tag in
                        // saveAdditional instead; a plain copy is correct here.
                        TileWorkbench.this.ingredientInventory.set(slot, stack.copy());
                        setChanged();
                        updateRecipeOutputs();
                    }
                    return ItemStack.EMPTY;
                }
                // Offered stack exceeds the slot's own capacity: fill the slot to
                // the limit and return the surplus, mirroring the merge branch.
                if (!simulate) {
                    ItemStack accepted = stack.copy();
                    accepted.setCount(maxSize);
                    TileWorkbench.this.ingredientInventory.set(slot, accepted);
                    setChanged();
                    updateRecipeOutputs();
                }
                ItemStack surplus = stack.copy();
                surplus.shrink(maxSize);
                return surplus;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                long sum = (long) existing.getCount() + (long) stack.getCount();
                int maxSize = TileWorkbench.this.getMaxStackSize();
                if (sum <= maxSize) {
                    if (!simulate) {
                        existing.setCount((int) sum);
                        setChanged();
                        updateRecipeOutputs();
                    }
                    return ItemStack.EMPTY;
                }
                int added = maxSize - existing.getCount();
                if (added > 0) {
                    if (!simulate) {
                        existing.setCount(maxSize);
                        setChanged();
                        updateRecipeOutputs();
                    }
                    ItemStack remainder = stack.copy();
                    remainder.shrink(added);
                    return remainder;
                }
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= getSlots() || amount <= 0) return ItemStack.EMPTY;
            ItemStack existing = TileWorkbench.this.ingredientInventory.get(slot);
            if (existing.isEmpty()) return ItemStack.EMPTY;
            // Cap at the item's OWN max stack size (matching ItemStackHandler's
            // reference contract): a slot can hold up to MAX_SLOT_STACK = 81920,
            // but the extracted stack leaves the handler and must survive normal
            // persistence — a count > 99 fails the ItemStack.save codec the
            // moment it lands in an ItemEntity/chest/player cursor.
            int toExtract = Math.min(amount, Math.min(existing.getCount(), existing.getMaxStackSize()));
            ItemStack result = existing.copy();
            result.setCount(toExtract);
            if (!simulate) {
                existing.shrink(toExtract);
                setChanged();
                updateRecipeOutputs();
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) { return TileWorkbench.this.getMaxStackSize(); }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= 0 && slot < getSlots() && !stack.isEmpty();
        }
    }
}
