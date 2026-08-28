package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import com.extremecraftingtable.Registration;
import com.extremecraftingtable.utils.SlotContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.stream.IntStream;

public class ContainerWorkbench extends AbstractContainerMenu {
    final TileWorkbench tile;
    private static final int SOURCE_SLOT = 27, RECIPE_SLOT = 18, PLAYER_SLOT = 36;

    final DataSlot progress = this.addDataSlot(DataSlot.standalone());
    final DataSlot isWorking = this.addDataSlot(DataSlot.standalone());
    final DataSlot workContinue = this.addDataSlot(DataSlot.standalone());
    final DataSlot recipeIndex = this.addDataSlot(DataSlot.standalone());

    public ContainerWorkbench(int id, Inventory inv, RegistryFriendlyByteBuf buf) { this(id, inv, buf.readBlockPos()); }

    public ContainerWorkbench(int id, Inventory inv, BlockPos pos) {
        super(Registration.WORKBENCH_MENU.get(), id);
        Player player = inv.player;
        // Issue #10 fix: Use pattern matching to safely check BlockEntity type
        BlockEntity be = player.level().getBlockEntity(pos);
        this.tile = be instanceof TileWorkbench tb ? tb : null;
        // A null tile (missing or wrong-type block entity) must be tolerated instead of
        // throwing here: getBlockEntity(pos) can legitimately return null (client-side
        // chunk-load race, block broken). Slots are built against the null container
        // (SlotContainer handles a null Container, returning empty stacks) and the
        // existing downstream null checks plus stillValid()==false close the menu cleanly.
        int row, col;
        for (row = 0; row < 3; ++row) for (col = 0; col < 9; ++col)
            addSlot(new SlotContainer(tile, col + row * 9, 8 + col * 18, 18 + row * 18));
        for (row = 0; row < 2; ++row) for (col = 0; col < 9; ++col)
            addSlot(new SlotContainer(tile, col + row * 9 + SOURCE_SLOT, 8 + col * 18, 90 + row * 18, false));
        // Output cache slot at y=130 (occupies 130-148), player inventory starts at 148 (adjacent, no overlap).
        addSlot(new SlotContainer(tile, 45, 8, 130, true));
        for (row = 0; row < 3; ++row) for (col = 0; col < 9; ++col)
            addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 148 + row * 18));
        for (col = 0; col < 9; ++col)
            addSlot(new Slot(inv, col, 8 + col * 18, 206));
        if (!player.level().isClientSide() && tile != null) {
            tile.startOpen(player);
            // Populate recipesList and selectionInventory on the server so that
            // recipe slot clicks work and the server has recipe output items to sync.
            tile.updateRecipeOutputs();
        }
        if (player.level().isClientSide() && tile != null) tile.updateRecipeList(player.level().registryAccess());
    }

    private void setTrackValues() {
        // Defensive null check: tile may be null from chunk-load races or broken blocks.
        // The only caller (broadcastChanges) already guards with tile != null, but this
        // ensures the method is safe even if called from a future unguarded code path.
        if (this.tile == null) return;
        progress.set(this.tile.getProgressScaled(160));
        isWorking.set(this.tile.getRecipe().hasContent() ? 1 : 0);
        workContinue.set(this.tile.workContinue ? 1 : 0);
        // Bug 3 fix: match by RecipeHolder ID instead of object identity (==).
        // After a craft consumes items, updateRecipeOutputs() rebuilds recipesList
        // with fresh RecipeEntry objects, while the workContinue path in tick()
        // restores the current recipe from the RecipeFinder's map by holder ID.
        // If the rebuilt entries wrap new recipe objects (defensive copies),
        // identity comparison would fail and recipeIndex would stay at -1 even
        // though a recipe is active, leaving the client with no recipe selected.
        // Resolve the current recipe's RecipeHolder ID from the finder map and
        // compare each entry's holderId() against it instead.
        // -1 means "no recipe selected". Sentinel 0 would be ambiguous: it is a
        // valid recipe index, so a cleared selection would be reported as the
        // first recipe being selected.
        ResourceLocation currentHolderId = this.tile.getRecipe().hasContent()
            ? tile.recipesList.stream()
                .filter(e -> e.recipe() == this.tile.getRecipe())
                .findFirst().map(WorkbenchRecipe.RecipeEntry::holderId)
                .orElseGet(() -> {
                    ResourceLocation id = this.tile.getRecipe().getId();
                    return id != null ? id : ECTMod.location("dummy");
                })
            : null;
        int index = IntStream.range(0, tile.recipesList.size())
            .filter(i -> currentHolderId != null && currentHolderId.equals(tile.recipesList.get(i).holderId()))
            .findFirst().orElse(-1);
        recipeIndex.set(index);
    }

    @Override public boolean stillValid(Player player) { return this.tile != null && this.tile.stillValid(player); }

    @Override
    public ItemStack quickMoveStack(Player playerIn, int index) {
        if (SOURCE_SLOT <= index && index < SOURCE_SLOT + RECIPE_SLOT) return ItemStack.EMPTY;
        ItemStack src = ItemStack.EMPTY;
        final Slot slot = this.getSlot(index);
        if (slot.hasItem()) {
            final ItemStack remain = slot.getItem();
            src = remain.copy();
            // n_moveItemStackTo already calls updateRecipeOutputs internally on
            // success; the post-branch update below is for the other two branches
            // (ingredient -> player, output cache -> player). Track which path
            // we took so we do not pay for the recipe recompute twice on the
            // hot player-inventory->workbench path.
            boolean movedToWorkbench = false;
            if (index < SOURCE_SLOT) {
                // Ingredient slots (0-26): move to player inventory (46-81).
                if (!moveItemStackTo(remain, SOURCE_SLOT + RECIPE_SLOT + 1, SOURCE_SLOT + RECIPE_SLOT + 1 + PLAYER_SLOT, true))
                    return ItemStack.EMPTY;
            } else if (index == SOURCE_SLOT + RECIPE_SLOT) {
                // Output cache slot (menu index 45): move the crafted result to the player's inventory.
                if (!moveItemStackTo(remain, SOURCE_SLOT + RECIPE_SLOT + 1, SOURCE_SLOT + RECIPE_SLOT + 1 + PLAYER_SLOT, true))
                    return ItemStack.EMPTY;
            } else if (!n_moveItemStackTo(remain)) {
                return ItemStack.EMPTY;
            } else {
                movedToWorkbench = true;
            }
            // n_moveItemStackTo already updated the recipe list internally; skip
            // the duplicate recompute on that path.
            if (tile != null && !movedToWorkbench) tile.updateRecipeOutputs();
            if (remain.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
            if (remain.getCount() == src.getCount()) return ItemStack.EMPTY;
            slot.onTake(playerIn, remain);
            // The contract for quickMoveStack is to return the stack the
            // caller now holds in its cursor (the moved portion, not the
            // pre-move snapshot). Vanilla shift-click ignores the return,
            // but a third-party mod (e.g. a "move all" macro or an
            // automation plugin) calling quickMoveStack directly would
            // otherwise see a phantom full stack.
            ItemStack moved = src.copy();
            moved.shrink(remain.getCount());
            return moved;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() { if (this.tile != null) setTrackValues(); super.broadcastChanges(); }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickTypeIn, Player player) {
        // Top-level null guard: if the tile is gone (chunk-load race, broken block),
        // the menu is broken and will close via stillValid() shortly.  Avoid running
        // slot logic against a null container (reads would return empty stacks and
        // writes would no-op) instead of reflecting the real tile.
        if (tile == null) return;

        // Block CLONE (creative middle-click) on any workbench slot (0-45):
        // super.clicked() would clone the output slot's crafted item or the recipe
        // preview's derived stack straight onto the cursor, giving items for free
        // without consuming ingredients. Only the player's own inventory slots
        // (46+) may be cloned, matching vanilla creative behaviour.
        if (clickTypeIn == ClickType.CLONE && slotId <= SOURCE_SLOT + RECIPE_SLOT) {
            return;
        }

        // Safe PICKUP_ALL across all slot types. Vanilla's PICKUP_ALL uses
        // slot.getMaxStackSize(item) to compute how many items to transfer from
        // each slot onto the cursor.  Workbench slots return Integer.MAX_VALUE
        // here (unbounded capacity), making vanilla take ALL items from a >64
        // stack at once and overflow the cursor above the item's normal stack
        // limit (64), corrupting counts.  Cursor-side transfers are capped at
        // the item's own max stack size in this replacement.
        if (clickTypeIn == ClickType.PICKUP_ALL) {
            handlePickupAll(player);
            return;
        }

        // Guard SWAP (number keys 1-9) and THROW (Q / Ctrl+Q) on ingredient slots.
        // Vanilla's SWAP hotbar-empty and different-item cases move the WHOLE slot
        // stack into the player inventory, and Ctrl+Q drops the whole stack as a
        // single ItemEntity. An ingredient stack can legally hold up to
        // MAX_SLOT_STACK (81920) items — far above the item's own max stack size —
        // and vanilla then writes a count > 99 stack into the player inventory or
        // an ItemEntity. Both persist through ItemStack.save codecs bounded by
        // intRange(1, 99): the player data save throws and the ENTIRE session's
        // inventory/XP/position is not persisted; the entity save throws and the
        // dropped items silently vanish. Cap every transfer at the item's own max
        // stack size; the surplus stays in the workbench slot. Recipe-preview
        // slots need no guard (mayPickup/mayPlace are false there), and player
        // slots never hold oversized stacks, so those still forward to vanilla.
        if ((clickTypeIn == ClickType.SWAP || clickTypeIn == ClickType.THROW)
            && 0 <= slotId && slotId < SOURCE_SLOT) {
            Slot slot = this.getSlot(slotId);
            ItemStack slotStack = slot.getItem();
            if (clickTypeIn == ClickType.THROW) {
                // Vanilla no-ops THROW while the cursor holds items
                // (AbstractContainerMenu.doClick gates on getCarried().isEmpty()).
                if (getCarried().isEmpty() && !slotStack.isEmpty() && slot.mayPickup(player)) {
                    int throwCount = dragType == 0
                        ? 1
                        : Math.min(slotStack.getCount(), slotStack.getMaxStackSize());
                    // 2-arg drop: the 3-arg overload only CONSTRUCTS the ItemEntity
                    // without addFreshEntity — items removed from the slot would be
                    // silently deleted.
                    player.drop(slot.remove(throwCount), true);
                    slot.setChanged();
                    tile.updateRecipeOutputs();
                }
                return;
            }
            // ClickType.SWAP: dragType is the hotbar index (0-8).
            if (dragType < 0 || dragType >= 9) return;
            var inv = player.getInventory();
            ItemStack hotbarStack = inv.getItem(dragType);
            if (hotbarStack.isEmpty()) {
                if (!slotStack.isEmpty() && slot.mayPickup(player)) {
                    int take = Math.min(slotStack.getCount(), slotStack.getMaxStackSize());
                    inv.setItem(dragType, slot.remove(take));
                }
            } else if (slotStack.isEmpty()) {
                // Hotbar → workbench slot: the slot's capacity (81920) always
                // accommodates a hotbar stack (≤ the item's max stack size).
                if (slot.mayPlace(hotbarStack)) {
                    inv.setItem(dragType, ItemStack.EMPTY);
                    slot.set(hotbarStack);
                }
            } else if (ItemStack.isSameItemSameComponents(slotStack, hotbarStack)) {
                // Same item: merge from the slot into the hotbar up to the hotbar
                // stack's own max — the amount moved is always ≤ 64.
                int room = hotbarStack.getMaxStackSize() - hotbarStack.getCount();
                if (room > 0 && slot.mayPickup(player)) {
                    hotbarStack.grow(slot.remove(room).getCount());
                    inv.setItem(dragType, hotbarStack);
                }
            } else if (slot.mayPlace(hotbarStack) && slot.mayPickup(player)) {
                // Different items: swap the two stacks. The workbench slot accepts
                // the hotbar stack unconditionally, but the hotbar cannot hold an
                // oversized displaced stack — split the swap and return the surplus.
                int maxTake = slotStack.getMaxStackSize();
                if (slotStack.getCount() <= maxTake) {
                    inv.setItem(dragType, slotStack);
                    slot.set(hotbarStack);
                } else {
                    // split() reduces slotStack in place; slot.set() then replaces
                    // the slot's reference, so the old reference carries the surplus.
                    ItemStack taken = slotStack.split(maxTake);
                    slot.set(hotbarStack);
                    inv.setItem(dragType, taken);
                    // Inventory.add returns true if ANY item was added, not all —
                    // the remainder stays in slotStack. Drop it in persistable
                    // chunks: a single ItemEntity above 99 items fails the
                    // ItemStack.save codec and silently vanishes on entity save.
                    player.getInventory().add(slotStack);
                    while (!slotStack.isEmpty()) {
                        int chunk = Math.min(slotStack.getCount(), Math.min(slotStack.getMaxStackSize(), 99));
                        player.drop(slotStack.split(chunk), false);
                    }
                }
            }
            slot.setChanged();
            tile.updateRecipeOutputs();
            return;
        }
        if (SOURCE_SLOT <= slotId && slotId < SOURCE_SLOT + RECIPE_SLOT && clickTypeIn == ClickType.PICKUP) {
            int index = slotId - SOURCE_SLOT;
            if (tile != null && index < tile.recipesList.size()) {
                if (dragType == 0) {
                    // Match by object identity (==): getRecipe() returns the exact recipe
                    // object stored in recipesList, so no ID comparison is needed. The
                    // entry's holderId is what setCurrentRecipe expects on selection.
                    var newRecipe = tile.recipesList.get(index).recipe();
                    if (newRecipe == tile.getRecipe()) tile.workContinue = !tile.workContinue;
                    else tile.setCurrentRecipe(tile.recipesList.get(index).holderId());
                } else if (dragType == 1) {
                    // Clearing the recipe selection must also clear workContinue,
                    // otherwise a stale workContinue resurrects a cleared recipe.
                    // null resets to the dummy recipe (no valid RecipeHolder ID).
                    tile.setCurrentRecipe(null);
                    tile.workContinue = false;
                }
                // Persist the recipe selection + workContinue. These are tile fields,
                // not inventory contents, so the menu's slot-changed bookkeeping does
                // not mark the tile dirty; without this the selection would be lost on
                // a chunk unload/auto-save if the battery is full and no craft fires.
                tile.setChanged();
            }
        } else if (0 <= slotId && slotId < SOURCE_SLOT && clickTypeIn == ClickType.PICKUP) {
            // tile is guaranteed non-null here (top-level guard above).
            Slot slot = this.getSlot(slotId);
            ItemStack slotStack = slot.getItem();
            ItemStack carrying = getCarried();
            if (slotStack.isEmpty()) {
                if (!carrying.isEmpty() && slot.mayPlace(carrying)) {
                    // Vanilla semantics: left-click (dragType 0) places the whole carried
                    // stack, right-click (dragType 1) places a single item. The unlimited
                    // slot capacity means the whole stack always fits.
                    int l2 = dragType == 0 ? carrying.getCount() : 1;
                    slot.set(carrying.split(l2));
                }
            } else {
                if (carrying.isEmpty()) {
                    // The ingredient slot can hold more than the item's normal stack limit
                    // (getMaxStackSize() returns Integer.MAX_VALUE), but the cursor cannot.
                    // Cap what comes off the slot at the item's own max stack size; the surplus
                    // stays in the slot, so nothing is lost or duplicated.
                    // Right-click takes half the slot stack, rounded up to match vanilla
                    // behaviour (count/2 would truncate, taking one fewer for odd counts).
                    // NOTE: use ItemStack.getMaxStackSize() (the item's own limit) here, NOT
                    // slot.getMaxStackSize(stack) — the latter returns the container's
                    // Integer.MAX_VALUE for unbounded workbench slots, which would let a
                    // single click pull an over-64 stack onto the cursor.
                    int k2 = dragType == 0
                        ? Math.min(slotStack.getCount(), slotStack.getMaxStackSize())
                        : Math.min((slotStack.getCount() + 1) / 2, slotStack.getMaxStackSize());
                    ItemStack removed = slot.remove(k2);
                    setCarried(removed);
                } else if (ItemStack.isSameItemSameComponents(slotStack, carrying)) {
                    // Left-click merges the whole carried stack (vanilla); right-click
                    // merges one at a time. The slot can hold up to MAX_SLOT_STACK (81920);
                    // the count is clamped to 64 before saveAdditional with the real
                    // count stored in the SlotCounts side tag, so the codec accepts it.
                    // before each save, so the large count survives serialization.
                    int j2 = dragType == 0 ? carrying.getCount() : 1;
                    int room = tile.getMaxStackSize() - slotStack.getCount();
                    int amount = Math.min(j2, Math.max(room, 0));
                    carrying.shrink(amount);
                    slotStack.grow(amount);
                } else {
                    // Cursor holds a different item than the slot: swap the two stacks.
                    // Only the ingredient slot accepts unlimited counts; the cursor must
                    // not exceed the item's max stack size. The slot holds a single stack,
                    // so an oversized displaced stack cannot fully move onto the cursor
                    // and its surplus would have nowhere to go — split the swap: put what
                    // fits on the cursor, return the surplus to the player's inventory,
                    // and place the cursor's items in the slot.  Without this, a sorting
                    // mod that tries to swap a >64 stack with a cursor item would have
                    // both the swap refused AND the cursor items silently discarded
                    // (the mod thinks they were placed, but the slot state is unchanged),
                    // causing the cursor items to be overwritten by the next operation.
                    int maxTake = slotStack.getMaxStackSize();
                    if (slotStack.getCount() <= maxTake) {
                        slot.set(carrying);
                        setCarried(slotStack);
                    } else {
                        // Slot stack is too large: take what fits on the cursor, put the
                        // cursor's items in the slot, and return the surplus to the player.
                        // split() modifies the original slotStack in-place, then
                        // slot.set() replaces the reference — the old slotStack reference
                        // still holds the surplus, so we can return it to the player.
                        ItemStack taken = slotStack.split(maxTake);
                        slot.set(carrying);
                        setCarried(taken);
                        if (!slotStack.isEmpty()) {
                            // Inventory.add returns true if ANY item was added, not
                            // all — when the inventory is partially full the boolean
                            // is true but a remainder is left in slotStack, and
                            // skipping the drop would silently delete it. Check the
                            // stack, not the boolean. Drop in persistable chunks: a
                            // single ItemEntity above 99 items fails the
                            // ItemStack.save codec and vanishes on entity save.
                            player.getInventory().add(slotStack);
                            while (!slotStack.isEmpty()) {
                                int chunk = Math.min(slotStack.getCount(), Math.min(slotStack.getMaxStackSize(), 99));
                                player.drop(slotStack.split(chunk), false);
                            }
                        }
                    }
                }
            }
            slot.setChanged();
            if (tile != null) tile.updateRecipeOutputs();
        } else if (SOURCE_SLOT <= slotId && slotId < SOURCE_SLOT + RECIPE_SLOT) {
            // Block non-PICKUP click types (PICKUP_ALL, THROW, SWAP) on recipe
            // preview slots. PICKUP is handled above (recipe selection); all other
            // click types would fall through to super.clicked(), which calls
            // slot.remove(amount) on the recipe preview slots, extracting the
            // preview items as if they were real items — an exploit that lets a
            // player double-click a matching inventory item to vacuum up recipe
            // output previews for free.
            // QUICK_CRAFT is forwarded to super.clicked() so the container's drag
            // state machine stays consistent (the recipe slot's mayPlace() returns
            // false for pickable=false, so no items are placed in the recipe slot,
            // but the drag counter is still updated). Silently swallowing QUICK_CRAFT
            // would leave the drag state corrupted, breaking subsequent drags over
            // ingredient slots.
            // When PICKUP is used on a recipe preview slot with items on the cursor
            // (e.g. from a sorting mod), the items must be returned to the player's
            // inventory to prevent them from being silently discarded — the sorting
            // mod does not check the cursor state after the operation, so the items
            // would be overwritten by the next click and lost.
            if (clickTypeIn == ClickType.PICKUP && !getCarried().isEmpty()) {
                ItemStack leftover = getCarried();
                setCarried(ItemStack.EMPTY);
                // Inventory.add returns true if ANY item was added — a partially
                // full inventory leaves a remainder in leftover, so check the
                // stack and drop what is left rather than trusting the boolean.
                player.getInventory().add(leftover);
                if (!leftover.isEmpty()) {
                    player.drop(leftover, false);
                }
            } else if (clickTypeIn == ClickType.QUICK_CRAFT) {
                super.clicked(slotId, dragType, clickTypeIn, player);
            }
        } else { super.clicked(slotId, dragType, clickTypeIn, player); if (tile != null) tile.updateRecipeOutputs(); }
    }

    /**
     * Safe replacement for vanilla PICKUP_ALL (double-click).  Iterates all
     * pickable slots and transfers matching items onto the cursor, but caps the
     * cursor total at the item's own max stack size (typically 64) instead of
     * using the slot's getMaxStackSize() which returns Integer.MAX_VALUE for
     * unbounded workbench slots.  Without this cap, double-clicking a &gt;64 stack
     * would overflow the cursor above the item's stack limit, corrupting counts.
     */
    private void handlePickupAll(Player player) {
        ItemStack carried = getCarried();
        if (carried.isEmpty()) return;
        int maxStack = carried.getMaxStackSize();
        for (Slot slot : this.slots) {
            if (carried.getCount() >= maxStack) break;
            if (!slot.hasItem() || !slot.mayPickup(player)) continue;
            ItemStack slotStack = slot.getItem();
            if (slotStack.isEmpty() || !ItemStack.isSameItemSameComponents(carried, slotStack)) continue;
            int take = Math.min(slotStack.getCount(), maxStack - carried.getCount());
            if (take > 0) {
                carried.grow(slot.remove(take).getCount());
                slot.setChanged();
            }
        }
        if (tile != null) tile.updateRecipeOutputs();
    }

    protected boolean n_moveItemStackTo(ItemStack stack) {
        // A null tile (construction race: getBlockEntity() returned null/wrong type) means the
        // ingredient slots are backed by an empty SlotContainer and can hold nothing. Returning
        // false here avoids the tile.getMaxStackSize() NPE in both the fill and merge loops, and
        // lets quickMoveStack unwrap cleanly place the item back on the cursor.
        if (this.tile == null) return false;
        boolean flag = false;
        for (int i = 0; i < SOURCE_SLOT && !stack.isEmpty(); i++) {
            Slot slot = getSlot(i);
            ItemStack itemstack = slot.getItem();
            if (!itemstack.isEmpty() && ItemStack.isSameItemSameComponents(stack, itemstack)) {
                // Cap the merge limit at the tile's own max stack size (Integer.MAX_VALUE),
                // not the moved item's normal stack limit: the workbench slots are designed
                // to accept unlimited identical stacks. The long math below prevents the
                // sum from overflowing past MAX_VALUE into negative counts.
                int maxSize = tile.getMaxStackSize();
                long sum = (long) itemstack.getCount() + (long) stack.getCount();
                if (sum <= maxSize) {
                    itemstack.setCount((int) sum);
                    // `stack` is the exact ItemStack instance `quickMoveStack` holds as
                    // `remain`; zeroing its count (not `slot.set(ItemStack.EMPTY)`, which
                    // targets `slot` — this loop's *destination* ingredient slot, the one
                    // `itemstack` was just merged into above) is what signals the caller
                    // the move fully succeeded, via its own `remain.isEmpty()` check.
                    // BUG (found 2026-08-27): this used to call `slot.set(ItemStack.EMPTY)`
                    // right after growing `itemstack`, which wiped the destination
                    // ingredient slot's live reference back to empty — silently deleting
                    // both the pre-existing pile and the just-merged items on every
                    // shift-click into a slot that already held a matching stack.
                    // `itemstack` came from `slot.getItem()`, which for the workbench's
                    // ingredient slots is the live `ingredientInventory` reference (see
                    // TileWorkbench.getItem()), so `itemstack.setCount(sum)` above already
                    // persists the merge — no further write to `slot` is needed.
                    // stack.isEmpty() checks count <= 0 regardless of components, so a
                    // count-0 `stack` correctly reads as empty to quickMoveStack's
                    // `remain.isEmpty()` check even though its DataComponents (if any)
                    // are still attached.
                    stack.setCount(0);
                    slot.setChanged();
                    flag = true;
                } else if (itemstack.getCount() < maxSize) {
                    stack.shrink(maxSize - itemstack.getCount()); itemstack.setCount(maxSize);
                    slot.setChanged(); flag = true;
                }
                // Otherwise the slot stack exceeds the item's stack limit: leave both stacks
            }
        }
        if (!stack.isEmpty()) {
            for (int i = 0; i < SOURCE_SLOT; i++) {
                Slot slot1 = getSlot(i);
                if (slot1.getItem().isEmpty() && slot1.mayPlace(stack)) {
                    // Cap at the tile's own max stack size (Integer.MAX_VALUE), consistent with
                    // the merge limit above. A player slot stack never exceeds its own max, so
                    // this only guards against oversized inputs.
                    slot1.set(stack.split(Math.min(stack.getCount(), tile.getMaxStackSize()))); slot1.setChanged(); flag = true;
                }
            }
        }
        if (flag && tile != null) tile.updateRecipeOutputs();
        return flag;
    }

    @Override public void removed(Player player) { super.removed(player); if (this.tile != null) this.tile.stopOpen(player); }
}