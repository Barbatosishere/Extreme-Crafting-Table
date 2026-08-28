package com.extremecraftingtable.utils;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Interface for block entities that can dump debug information.
 * <p>
 * Original: QuarryPlus CheckerLog by Kotori316
 */
@FunctionalInterface
public interface CheckerLog {
    List<? extends Component> getDebugLogs();
}