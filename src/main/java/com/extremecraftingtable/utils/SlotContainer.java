package com.extremecraftingtable.utils;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Slot with a configurable pickable flag.
 * When pickable is false, items cannot be taken from this slot (used for recipe preview slots).
 * <p>
 * Original: QuarryPlus SlotContainer by Kotori316
 */
public class SlotContainer extends Slot {

    private final boolean pickable;

    public SlotContainer(@Nullable Container inventory, int index, int x, int y) {
        this(inventory, index, x, y, true);
    }

    public SlotContainer(@Nullable Container inventory, int index, int x, int y, boolean pickable) {
        super(inventory, index, x, y);
        this.pickable = pickable;
    }

    @Override
    public boolean mayPickup(Player player) {
        return pickable && container != null && super.mayPickup(player);
    }

    @Override
    public ItemStack getItem() {
        return container != null ? container.getItem(getSlotIndex()) : ItemStack.EMPTY;
    }

    @Override
    public boolean hasItem() {
        return container != null && super.hasItem();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return container != null && pickable && super.mayPlace(stack);
    }

    @Override
    public void set(ItemStack stack) {
        // Null-container tolerance, consistent with getItem()/mayPlace()/remove():
        // the workbench menu can be created with a null tile during a chunk-load or
        // teleport race. On the next server broadcastChanges the client's
        // AbstractContainerMenu.setItem arrives here; throwing would crash the
        // client instead of letting the stillValid() null-tile guard close the menu.
        if (container == null) {
            return;
        }
        if (!pickable) {
            // Recipe preview slots are read-only: reject placement through any code
            // path that reaches slot.set() directly (vanilla QUICK_CRAFT, another
            // mod's moveItemStackTo, etc.), not just via mayPickup()/remove().
            return;
        }
        super.set(stack);
    }

    @Override
    public ItemStack remove(int amount) {
        if (container == null || !pickable) return ItemStack.EMPTY;
        return super.remove(amount);
    }

    @Override
    public void setChanged() {
        if (container != null) super.setChanged();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        // Return the container's own limit, NOT min(container, itemMaxStack):
        // the workbench ingredient slots hold unbounded stacks (tile.getMaxStackSize()
        // returns Integer.MAX_VALUE), and vanilla's PICKUP_ALL / merge logic uses
        // this value to compute remaining capacity. Capping it at the item's normal
        // stack size (64) while the slot actually holds more than 64 yields a NEGATIVE
        // "room" calculation, corrupting counts and swallowing items on double-click
        // or inventory-sorting mods. The drag/cursor path still limits to the item's
        // own stack size independently, so unbounded slots remain safe.
        return container != null ? container.getMaxStackSize() : 64;
    }

    @Override
    public int getMaxStackSize() {
        return container != null ? container.getMaxStackSize() : 64;
    }
}