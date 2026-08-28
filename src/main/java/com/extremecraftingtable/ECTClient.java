package com.extremecraftingtable;

import com.extremecraftingtable.machines.workbench.RecipeFinder;
import com.extremecraftingtable.machines.workbench.ScreenWorkbench;
import com.extremecraftingtable.machines.workbench.WorkbenchRecipe;
import com.extremecraftingtable.packet.ClientSyncPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

public class ECTClient {
    public static void registerClientBus(IEventBus modEventBus) {
        modEventBus.addListener(ECTClient::onRegisterScreens);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> ClientSyncPayload.tick());
        // Clear pending sync payloads when leaving a world, so stale entries queued for
        // an unloaded chunk cannot be applied to a different world joined within the TTL.
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientSyncPayload.clear());
        NeoForge.EVENT_BUS.addListener(RecipesUpdatedEvent.class, ECTClient::onRecipesUpdated);
    }

    /**
     * Fired on the logical client whenever its {@code RecipeManager} finishes
     * receiving a fresh recipe sync from the server — this covers both the
     * initial join and every {@code /reload} while connected. Unlike
     * {@code OnDatapackSyncEvent} (server-only; never posted in a remote
     * multiplayer client's JVM), this is the correct client-side signal that
     * the workbench's own recipe cache and JEI's recipe list need refreshing.
     */
    private static void onRecipesUpdated(RecipesUpdatedEvent event) {
        RecipeFinder finder = WorkbenchRecipe.getRecipeFinder();
        finder.invalidate();
        if (ModList.get().isLoaded("jei")) {
            com.extremecraftingtable.integration.jei.ECTJeiPlugin.refresh();
        }
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.WORKBENCH_MENU.get(), ScreenWorkbench::new);
    }
}