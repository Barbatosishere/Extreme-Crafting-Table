package com.extremecraftingtable.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Inventory helpers for the workbench.
 * <p>
 * Original: QuarryPlus InvUtils by Kotori316 (simplified — no neighbor injection)
 */
public final class InvUtils {
    private InvUtils() {}

    /**
     * Drop the item in the world at the given position.
     * Returns ItemStack.EMPTY only when the input was fully consumed (spawned as
     * an entity). Returns a copy of the input stack in all other cases — when the
     * item could not be spawned this tick, or when called on the client side,
     * where entities cannot be spawned at all — so callers never mistake a no-op
     * for a successful injection.
     */
    public static ItemStack injectToNearTile(Level level, BlockPos pos, ItemStack toMove) {
        if (toMove.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack toReturn = toMove.copy();
        if (level.isClientSide()) {
            return toReturn;
        }
        var entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, toMove);
        if (!level.addFreshEntity(entity)) {
            return toReturn;
        }
        return ItemStack.EMPTY;
    }
}