package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BlockWorkbench extends Block implements EntityBlock {
    public static final String NAME = "workbench";

    public BlockWorkbench(Properties properties) { super(properties); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            level.getBlockEntity(pos, Registration.WORKBENCH_TYPE.get())
                .ifPresent(w -> player.openMenu(w));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !movedByPiston && level instanceof ServerLevel) {
            if (level.getBlockEntity(pos) instanceof TileWorkbench workbench) {
                // Drop item contents. Cap each stack at the item's maxStackSize so
                // that on-removal drops do not carry the 81920-stack "big stack"
                // trick: a player who picks up a >64 ItemEntity and shoves it into
                // a chest would have the count silently clamped to 64 on save,
                // losing the difference. Splitting here keeps the round-trip
                // lossless and matches what players can do via shift-click.
                dropClampedContents(level, pos, workbench.ingredientInventory);
                dropClampedContents(level, pos, workbench.outputInventory);
                // Bug fix: Do NOT drop selectionInventory — it contains recipe output
                // previews, not player-owned items. Dropping them would be a free-item
                // exploit (players could break the block to get recipe outputs without
                // crafting).
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Drop every non-empty stack in {@code inv} as an ItemEntity at the block
     * position, splitting stacks larger than the per-entity cap into multiple
     * entities. The workbench slot accepts up to {@code MAX_SLOT_STACK}
     * (81920) of a single item, but the {@link ItemStack#save} codec rejects
     * count > 99 — so an ItemEntity whose stack exceeds 99 silently truncates
     * the surplus the moment it is re-saved (e.g. on chunk unload). Some
     * modded items also report {@code getMaxStackSize() == Integer.MAX_VALUE}
     * via {@code DataComponents.MAX_STACK_SIZE} which would let us spawn a
     * single entity with thousands of items; clamp to 99 to match the
     * 1.21.1 codec ceiling.
     */
    private static void dropClampedContents(Level level, BlockPos pos, List<ItemStack> inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack.isEmpty()) continue;
            int maxStack = Math.min(stack.getMaxStackSize(), 99);
            while (!stack.isEmpty()) {
                ItemStack drop = stack.split(Math.min(stack.getCount(), maxStack));
                net.minecraft.world.entity.item.ItemEntity entity =
                    new net.minecraft.world.entity.item.ItemEntity(
                        level,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        drop);
                if (!level.addFreshEntity(entity)) {
                    // Entity could not be spawned (chunk unloading, entity cap) —
                    // give players a chance to pick it up. Inventory.add returns
                    // true if ANY item was added, not all — a partially full
                    // inventory leaves a remainder in `drop`, so check the stack
                    // and keep offering it to the next player.
                    if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                        for (var p : sl.players()) {
                            if (drop.isEmpty()) break;
                            p.getInventory().add(drop);
                        }
                        // Still nothing placed (or leftovers remain) — drop the
                        // remainder at the nearest player so it is not deleted.
                        if (!drop.isEmpty() && !sl.players().isEmpty()) {
                            sl.players().get(0).drop(drop, false);
                        }
                    }
                    if (!drop.isEmpty()) {
                        // Nobody received it (e.g. automation broke the block
                        // with no players online). Re-merge the chunk into
                        // `stack` and stop: without the break, re-merging would
                        // re-split the same chunk forever; without re-merging,
                        // the split-off chunk silently vanishes from the slot.
                        stack.grow(drop.getCount());
                        break;
                    }
                }
            }
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return Registration.WORKBENCH_TYPE.get().create(pos, state);
    }

    /**
     * Cached server-side ticker. NeoForge may call {@link #getTicker} on every
     * chunk tick to support per-state tickers; without this cache, a new lambda
     * (and a fresh registry lookup of {@code WORKBENCH_TYPE}) would happen
     * for every active workbench on every tick, producing needless allocation
     * and registry traffic. The cast to {@code BlockEntityTicker<T>} is safe
     * because the runtime cast inside the lambda will fail-fast on a wrong
     * BE type, and the outer {@code getTicker} already gates by
     * {@code Registration.WORKBENCH_TYPE}.
     */
    private static final BlockEntityTicker<TileWorkbench> SERVER_TICKER =
        (l, p, s, be) -> TileWorkbench.tick(l, p, s, (TileWorkbench) be);

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> entityType) {
        if (level.isClientSide() || entityType != Registration.WORKBENCH_TYPE.get()) return null;
        return (BlockEntityTicker<T>) SERVER_TICKER;
    }
}