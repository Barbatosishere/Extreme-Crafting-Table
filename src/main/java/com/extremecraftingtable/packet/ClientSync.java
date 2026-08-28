package com.extremecraftingtable.packet;

import net.minecraft.nbt.CompoundTag;

/**
 * Interface for block entities that can sync state to the client using CompoundTag.
 * Original: QuarryPlus ClientSync by Kotori316
 */
public interface ClientSync {
    CompoundTag toClientTag(CompoundTag nbt);
    void fromClientTag(CompoundTag nbt);
}