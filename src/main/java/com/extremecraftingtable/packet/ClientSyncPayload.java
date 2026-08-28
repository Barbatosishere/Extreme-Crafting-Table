package com.extremecraftingtable.packet;

import com.extremecraftingtable.ECTMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record ClientSyncPayload(BlockPos pos, ResourceLocation dimension, CompoundTag tag) implements CustomPacketPayload {
    public static final Type<ClientSyncPayload> TYPE = new Type<>(ECTMod.location("client_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSyncPayload> STREAM_CODEC =
        StreamCodec.composite(BlockPos.STREAM_CODEC, ClientSyncPayload::pos, ResourceLocation.STREAM_CODEC, ClientSyncPayload::dimension, ByteBufCodecs.COMPOUND_TAG, ClientSyncPayload::tag, ClientSyncPayload::new);

    public ClientSyncPayload(BlockEntity entity) {
        this(entity.getBlockPos(),
                entity.getLevel() != null ? entity.getLevel().dimension().location() : Level.OVERWORLD.location(),
                entity instanceof ClientSync cs ? cs.toClientTag(new CompoundTag()) : new CompoundTag());
    }
    @Override public @NotNull Type<ClientSyncPayload> type() { return TYPE; }

    /**
     * Payloads whose block entity was not yet loaded on the client when the packet
     * arrived (e.g. the tag raced an initial chunk load). Keyed by position so a newer
     * packet simply replaces an older pending one; drained by {@link #tick()} once the
     * block entity is available.
     *
     * <p>Concurrency: the contract says "mutated from the client thread", but
     * {@code enqueueWork} is not a happens-before edge with {@code ClientTickEvent.Post}
     * listeners on the same thread, and the documented exception is that some
     * payload handlers can briefly run on a Netty thread on the dedicated-server
     * handoff. Use {@link ConcurrentHashMap} to avoid a structural data race
     * (lost-update, infinite loop on a rehashed bucket, ConcurrentModificationException
     * during tick()) even in that off-thread window.</p>
     */
    private static final Map<BlockPos, Pending> PENDING = new ConcurrentHashMap<>();
    /** Pending syncs older than this are dropped; the payload for that position is stale. */
    private static final long PENDING_TTL_NS = 30_000_000_000L; // 30 s
    /**
     * Hard cap on the number of pending payloads. A pathological workload (server
     * flooding the client with chunks that never load) would otherwise grow this map
     * without bound, costing both memory and per-tick iteration time. When the cap
     * is reached the oldest entry (by insertion time) is evicted to make room for the
     * incoming one — newer data is more likely to be useful to a player joining a
     * region than older data for a chunk they may never reach.
     */
    private static final int PENDING_MAX_ENTRIES = 256;

    /**
     * Holds a deferred payload and the monotonic timestamp at which it was queued,
     * so the two values cannot drift relative to each other under concurrent updates.
     * {@code sinceNs} is in nanoseconds from {@link System#nanoTime()} — a monotonic
     * clock that ignores wall-clock adjustments (NTP, DST, manual changes) which
     * would otherwise let a backward clock jump freeze TTL-based eviction forever.
     */
    private record Pending(ClientSyncPayload payload, long sinceNs) {}

    /**
     * Soft upper bound for a single ClientSyncPayload's serialized CompoundTag
     * size in bytes. NeoForge 1.21.1 caps custom payloads at ~1 MB by default
     * (mod-channel negotiation); a runaway tag (e.g. a recipe slot accidentally
     * filled with a placeholder Holder pointing at a removed registry entry)
     * could otherwise exceed the channel limit and either throw an
     * {@code EncoderException} deep in the pipeline (which we would not see
     * until after the chunk is already partially applied) or be silently
     * truncated. {@code 64 KiB} is generous for {@code toClientTag} which only
     * writes recipe + energy today; the field is a constant rather than a
     * config so the cap survives an operator setting it absurdly high.
     */
    private static final int MAX_TAG_BYTES = 64 * 1024;

    /**
     * Estimate the encoded byte size of a CompoundTag. Counts VALUES, not just
     * key names: the original key-only estimate bounded the size by key count,
     * so a tag carrying a large string/int-array value passed the 64 KiB
     * pre-check and only failed later in the pipeline. Wire layout per entry:
     * {1 type byte} {2 name length} {name UTF bytes} {value body}.
     */
    private static int estimateBytes(CompoundTag tag) {
        // NB: NbtAccounter (vanilla's hard cap) is the authoritative gate; this
        // is a coarse pre-check so we can warn in the log instead of crashing
        // the network thread when an upstream regression grows toClientTag().
        int n = 8; // outer type byte + 4-byte length prefix + slack
        for (String key : tag.getAllKeys()) {
            n += 1 + 2 + key.length();
            Tag value = tag.get(key);
            if (value != null) n += estimateValueBytes(value);
        }
        return n;
    }

    private static int estimateValueBytes(Tag value) {
        if (value instanceof CompoundTag compound) {
            int n = 1; // end tag
            for (String key : compound.getAllKeys()) {
                n += 1 + 2 + key.length();
                Tag inner = compound.get(key);
                if (inner != null) n += estimateValueBytes(inner);
            }
            return n;
        }
        if (value instanceof ListTag list) {
            int n = 5; // element type byte + 4-byte length
            for (Tag element : list) {
                n += estimateValueBytes(element);
            }
            return n;
        }
        if (value instanceof StringTag s) {
            return 2 + s.getAsString().length(); // length prefix + UTF-8 bytes (char count ≈ byte count for BMP)
        }
        if (value instanceof ByteArrayTag a) {
            return 4 + a.size();
        }
        if (value instanceof IntArrayTag a) {
            return 4 + 4 * a.size();
        }
        if (value instanceof LongArrayTag a) {
            return 4 + 8 * a.size();
        }
        // Byte/Short/Int/Float = 5 body bytes; Long/Double = 9; End = 0.
        return switch (value) {
            case LongTag ignored -> 9;
            case DoubleTag ignored -> 9;
            case EndTag ignored -> 0;
            default -> 5;
        };
    }

    public static void sendToClient(ClientSync entity, Level level) {
        if (level instanceof ServerLevel serverLevel && entity instanceof BlockEntity be) {
            var payload = new ClientSyncPayload(be);
            if (estimateBytes(payload.tag()) > MAX_TAG_BYTES) {
                com.extremecraftingtable.ECTMod.LOGGER.warn(
                    "Refusing to send oversized ClientSyncPayload ({}+ bytes) for {}; the client's chunk will fall back to chunk-sync state",
                    estimateBytes(payload.tag()), be.getBlockPos());
                return;
            }
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(be.getBlockPos()), payload);
        }
    }

    /**
     * Targeted variant used by per-player events like {@code startOpen} where
     * the workbench only wants to refresh a single player's view, not every
     * player tracking the chunk. The chunk-tracking variant (no target) is
     * still appropriate for tick-driven syncs of state the GUI shows in real
     * time to anyone nearby.
     */
    public static void sendToClient(ServerPlayer target, ClientSync entity) {
        if (entity instanceof BlockEntity be) {
            var payload = new ClientSyncPayload(be);
            if (estimateBytes(payload.tag()) > MAX_TAG_BYTES) {
                com.extremecraftingtable.ECTMod.LOGGER.warn(
                    "Refusing oversized ClientSyncPayload to {} for {}",
                    target.getName().getString(), be.getBlockPos());
                return;
            }
            PacketDistributor.sendToPlayer(target, payload);
        }
    }

    public static void handle(ClientSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var level = Minecraft.getInstance().level;
            long now = System.nanoTime();
            if (level == null) {
                // Defer: the client is not yet in a world (loading screen, initial
                // connect). Queue into PENDING so tick() drains it once the level is
                // available, instead of silently dropping the sync update.
                offerPending(payload.pos, new Pending(payload, now));
                return;
            }
            // Robustness: skip if the client is in a different dimension than the TE.
            // In practice this should not happen because sendToPlayersTrackingChunk is
            // dimension-scoped, but guard against it to avoid silently applying stale data.
            if (!level.dimension().location().equals(payload.dimension)) return;
            BlockEntity be = level.getBlockEntity(payload.pos);
            if (be instanceof ClientSync cs) {
                cs.fromClientTag(payload.tag);
                PENDING.remove(payload.pos);
            } else {
                // Defer until the block entity is loaded.
                offerPending(payload.pos, new Pending(payload, now));
            }
        });
    }

    /**
     * Inserts an entry into PENDING, evicting the oldest entry if the cap is reached.
     * Holds a brief lock on PENDING to make the cap check + eviction atomic across
     * concurrent enqueueWork tasks — a race that allowed two threads to each see
     * size = cap - 1 and both insert would let the map grow to cap + 1.
     */
    private static void offerPending(BlockPos pos, Pending entry) {
        synchronized (PENDING) {
            if (PENDING.size() >= PENDING_MAX_ENTRIES) {
                BlockPos oldestPos = null;
                long oldestSince = Long.MAX_VALUE;
                for (var e : PENDING.entrySet()) {
                    long since = e.getValue().sinceNs();
                    if (since < oldestSince) {
                        oldestSince = since;
                        oldestPos = e.getKey();
                    }
                }
                if (oldestPos != null) {
                    PENDING.remove(oldestPos);
                }
            }
            PENDING.put(pos, entry);
        }
    }

    /**
     * Clears all pending sync payloads. Called when the client world changes
     * (e.g. a new save is loaded) to prevent stale entries from leaking across worlds.
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * Called every client tick (via {@link net.neoforged.client.event.ClientTickEvent.Post})
     * to drain pending payloads whose block entity has become available since the packet arrived.
     *
     * <p>Both {@link #offerPending} (called from {@code enqueueWork} in {@code handle})
     * and this method run on the client main thread, so the {@code synchronized (PENDING)}
     * monitor is held for the whole drain — the previous code used a
     * {@code ConcurrentHashMap.weaklyConsistent} iterator which, while not unsafe
     * (it never throws {@code ConcurrentModificationException}), could observe a
     * half-applied state during the very brief window a future code path might
     * run this off-thread. Snapshotting under the monitor makes the contract
     * explicit.</p>
     */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        long now = System.nanoTime();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            // Expire entries queued during loading screen even without a level.
            // Otherwise entries added when level==null (handle()) would leak until
            // the next world join or forever. The snapshot+iter.remove pattern
            // is safe for concurrent puts because we hold PENDING's monitor.
            java.util.List<java.util.Map.Entry<BlockPos, Pending>> snapshot;
            synchronized (PENDING) {
                snapshot = new java.util.ArrayList<>(PENDING.entrySet());
            }
            for (var entry : snapshot) {
                if (now - entry.getValue().sinceNs() > PENDING_TTL_NS) {
                    PENDING.remove(entry.getKey());
                }
            }
            return;
        }
        // Snapshot under the monitor so a concurrent offerPending cannot
        // re-order our removal decisions.
        java.util.List<java.util.Map.Entry<BlockPos, Pending>> snapshot;
        synchronized (PENDING) {
            snapshot = new java.util.ArrayList<>(PENDING.entrySet());
        }
        for (var entry : snapshot) {
            var payload = entry.getValue().payload();
            // TTL expiry: if the chunk never loads (player moved far away and never
            // returned), drop the stale payload instead of leaking it in PENDING forever.
            // Conditional remove: a newer offer for the same position must survive.
            if (now - entry.getValue().sinceNs() > PENDING_TTL_NS) {
                PENDING.remove(entry.getKey(), entry.getValue());
                continue;
            }
            // Drop stale entries from a different dimension (player travelled).
            if (!level.dimension().location().equals(payload.dimension)) {
                PENDING.remove(entry.getKey(), entry.getValue());
                continue;
            }
            BlockEntity be = level.getBlockEntity(payload.pos);
            if (be instanceof ClientSync cs) {
                cs.fromClientTag(payload.tag);
                // Conditional remove: if a NEWER payload was offered for the same
                // position between our snapshot and this point, leave it for the
                // next tick. An unconditional remove(key) would silently drop the
                // newer offer after having applied the older snapshot value.
                PENDING.remove(entry.getKey(), entry.getValue());
            } else if (level.isLoaded(payload.pos)) {
                // The chunk holding this position is loaded, so no ClientSync block
                // entity exists here: either the workbench was broken to air, or a
                // different (non-ClientSync) block/entity now occupies the position.
                // Waiting can never turn it into a ClientSync, so drop the entry —
                // otherwise it would leak in PENDING forever and would be applied as
                // stale state to a ClientSync block later placed at the same position.
                PENDING.remove(entry.getKey());
            }
        }
    }
}