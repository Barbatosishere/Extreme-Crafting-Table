package com.extremecraftingtable;

import com.extremecraftingtable.packet.ClientSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ECTMod.MOD_ID)
public class ECTMod {
    public static final String MOD_ID = "extremecraftingtable";
    public static final String MOD_NAME = "Extreme Crafting Table";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    public static Config config;
    private static boolean isClient;

    public ECTMod(IEventBus modEventBus, ModContainer modContainer) {
        isClient = Dist.CLIENT.equals(FMLLoader.getDist());

        Registration.BLOCKS.register(modEventBus);
        Registration.ITEMS.register(modEventBus);
        Registration.BLOCK_ENTITIES.register(modEventBus);
        Registration.MENUS.register(modEventBus);
        Registration.RECIPE_SERIALIZERS.register(modEventBus);
        Registration.RECIPE_TYPES.register(modEventBus);
        Registration.CREATIVE_MODE_TABS.register(modEventBus);

        config = Config.INSTANCE;
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterCapabilities);
        modEventBus.addListener(this::onRegisterPayloads);
        modEventBus.addListener(this::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(this::onServerStart);
        // BUG-C1 fix: when a player disconnects without closing their menu
        // (kick, network drop, /stop) the menu's `removed(Player)` is never
        // called and the Player reference would otherwise leak into
        // TileWorkbench.openPlayers for the rest of the chunk's life. We
        // sweep on logout and on server stop.
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);

        if (isClient) {
            ECTClient.registerClientBus(modEventBus);
        }
    }

    public static boolean isClient() {
        return isClient;
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Extreme Crafting Table initialized.");
    }

    private void onRegisterCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Registration.WORKBENCH_TYPE.get(),
            (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Registration.WORKBENCH_TYPE.get(),
            (be, side) -> be.getEnergyStorage());
    }

    private void onServerStart(ServerStartedEvent event) {
        LOGGER.info("Extreme Crafting Table server started.");
    }

    /**
     * Sweep the disconnected player out of every workbench's
     * {@code openPlayers} list. Menu's {@code removed(Player)} is what
     * normally calls {@code stopOpen}, but a network drop / /kick / /stop
     * never reaches that path.
     */
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            com.extremecraftingtable.machines.workbench.TileWorkbench.sweepOpenPlayer(sp);
        }
    }

    /**
     * On server stop, every tile in every loaded chunk needs its
     * openPlayers list cleared; the chunk teardown will not fire
     * {@code setRemoved} on BEs that are merely being unloaded, and the
     * player references held there are about to become invalid.
     */
    private void onServerStopping(ServerStoppingEvent event) {
        com.extremecraftingtable.machines.workbench.TileWorkbench.clearAllOpenPlayers();
    }

    /**
     * {@code OnDatapackSyncEvent} only fires on the logical server (it is
     * constructed from {@code PlayerList}/{@code ServerPlayer} and never
     * crosses the network), so this invalidates the shared
     * {@code RecipeFinder} cache for the server side of a /reload. In
     * singleplayer the integrated server and the client share this JVM and
     * the same static {@code RecipeFinder}, so this also covers that case;
     * for a genuine remote multiplayer client — which never sees this event —
     * {@link ECTClient#registerClientBus} listens for the client-only
     * {@code RecipesUpdatedEvent} instead (fired once the client's
     * RecipeManager has actually received the resynced recipes), which also
     * drives the JEI recipe-list refresh.
     */
    private void onDatapackSync(OnDatapackSyncEvent event) {
        com.extremecraftingtable.machines.workbench.WorkbenchRecipe.getRecipeFinder().invalidate();
    }

    private void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MOD_ID);
        registrar.playToClient(ClientSyncPayload.TYPE, ClientSyncPayload.STREAM_CODEC, ClientSyncPayload::handle);
    }

    /**
     * Hot-reload capacity values into every live workbench when the user
     * edits the COMMON config and runs {@code /reload}. Without this, the
     * new {@code workbenchCapacity}/{@code workbenchMaxReceive} would only
     * take effect for newly-placed tiles — see Round 3 audit C-1.
     */
    private void onConfigReloading(final ModConfigEvent.Reloading event) {
        var cfg = event.getConfig();
        if (cfg.getType() != ModConfig.Type.COMMON || !MOD_ID.equals(cfg.getModId())) return;
        if (config == null) return;
        int cap = config.workbenchCapacity.get();
        int rec = config.workbenchMaxReceive.get();
        com.extremecraftingtable.machines.workbench.TileWorkbench.applyConfigCapacity(cap, rec);
        LOGGER.info("Applied config reload to live workbenches: capacity={}, maxReceive={}", cap, rec);
    }

    public static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}