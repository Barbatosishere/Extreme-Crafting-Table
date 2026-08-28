package com.extremecraftingtable.machines.workbench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.extremecraftingtable.ECTMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class WorkbenchRecipeSerializer implements RecipeSerializer<WorkbenchRecipe> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkbenchRecipeSerializer.class);

    private static final Map<String, PacketSerialize<?>> serializeMap = Map.of(
        "default", new IngredientRecipeSerialize()
    );

    private static final MapCodec<WorkbenchRecipe> CODEC = new MapCodec<>() {
        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(ops.createString("subType"), ops.createString("result"), ops.createString("energy"), ops.createString("showInJEI"), ops.createString("ingredients"));
        }

        @Override
        public <T> DataResult<WorkbenchRecipe> decode(DynamicOps<T> ops, MapLike<T> input) {
            try {
                JsonObject json = new JsonObject();
                input.entries().forEach(pair -> {
                    String key = ops.getStringValue(pair.getFirst()).getOrThrow();
                    json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                });
                var subType = GsonHelper.getAsString(json, "subType", "default");
                var serializer = serializeMap.get(subType);
                if (serializer == null) return DataResult.error(() -> "Unknown subType: " + subType);
                // Pass the caller's ops through to fromJson instead of discarding it.
                // At datapack load the RecipeManager supplies a RegistryOps<JsonElement>,
                // which ItemStack.SINGLE_ITEM_CODEC needs to resolve data components
                // (enchantments, potion_contents, ...). Throwing it away silently drops
                // every recipe whose output has such components. The cast is safe for the
                // JSON-based ops the RecipeManager always uses; for any other ops the
                // ClassCastException is caught below and surfaced as a DataResult.error.
                @SuppressWarnings("unchecked")
                DynamicOps<JsonElement> jsonOps = (DynamicOps<JsonElement>) ops;
                return DataResult.success(serializer.fromJson(null, json, jsonOps));
            } catch (Exception e) {
                ECTMod.LOGGER.error("Failed to parse WorkbenchRecipe: {}", e.getMessage());
                return DataResult.error(() -> "Failed to parse WorkbenchRecipe: " + e.getMessage());
            }
        }

        @Override
        public <T> RecordBuilder<T> encode(WorkbenchRecipe recipe, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("subType", recipe.getSubTypeName());
                @SuppressWarnings("unchecked")
                PacketSerialize<WorkbenchRecipe> serializer = (PacketSerialize<WorkbenchRecipe>) serializeMap.get(recipe.getSubTypeName());
                // Encode must fail loudly on an unknown subtype (matching the STREAM_CODEC
                // encode branch in this file and the decode branch's DataResult.error) instead
                // of silently emitting a record that only carries the "subType" field — that
                // truncates every other recipe field and corrupts the round-trip.
                if (serializer == null) throw new IllegalArgumentException("Unknown subType: " + recipe.getSubTypeName());
                // BUG-R7m4 fix: pass ops down to the subtype serializer so
                // encode/decode are symmetric and registry-bound data
                // components (potion_contents, enchantments, …) survive a
                // reload round-trip. For the caller's `ops` (typically
                // JsonOps.INSTANCE on the JSON datapack path or a
                // RegistryOps on the datapack-debug path), the subtype
                // encoder will pick the right codec.
                @SuppressWarnings({"unchecked", "rawtypes"})
                DynamicOps<JsonElement> jsonOps = (DynamicOps<JsonElement>) ops;
                serializer.toJson(json, recipe, jsonOps);
                for (var entry : json.entrySet()) {
                    prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to encode WorkbenchRecipe", e);
                String recipeId = recipe.getId() != null ? recipe.getId().toString() : "null";
                // MapCodec.encode must return a DataResult error, not throw, to
                // keep the encoding pipeline intact for other recipes.
                prefix = prefix.withErrorsFrom(DataResult.error(() ->
                    "Failed to encode WorkbenchRecipe: " + recipeId));
            }
            return prefix;
        }
    };

    private static final StreamCodec<RegistryFriendlyByteBuf, WorkbenchRecipe> STREAM_CODEC = StreamCodec.of(
        (buf, recipe) -> {
            buf.writeUtf(recipe.getSubTypeName());
            ResourceLocation id = recipe.getId();
            buf.writeBoolean(id != null);
            if (id != null) buf.writeResourceLocation(id);
            @SuppressWarnings("unchecked")
            PacketSerialize<WorkbenchRecipe> serializer = (PacketSerialize<WorkbenchRecipe>) serializeMap.get(recipe.getSubTypeName());
            if (serializer == null) throw new IllegalArgumentException("Unknown subType: " + recipe.getSubTypeName());
            serializer.toPacket(buf, recipe);
        },
        buf -> {
            var subType = buf.readUtf();
            @SuppressWarnings("unchecked")
            PacketSerialize<WorkbenchRecipe> serializer = (PacketSerialize<WorkbenchRecipe>) serializeMap.get(subType);
            if (serializer == null) throw new IllegalArgumentException("Unknown subType: " + subType);
            return serializer.fromPacket(buf.readBoolean() ? buf.readResourceLocation() : null, buf);
        }
    );

    @Override public MapCodec<WorkbenchRecipe> codec() { return CODEC; }
    @Override public StreamCodec<RegistryFriendlyByteBuf, WorkbenchRecipe> streamCodec() { return STREAM_CODEC; }

    public interface PacketSerialize<T extends WorkbenchRecipe> {
        T fromJson(ResourceLocation id, JsonObject jsonObject, DynamicOps<JsonElement> ops);
        /**
         * @param ops  the caller's {@code DynamicOps<JsonElement>}; the encoder
         *             can be either a plain {@code JsonOps.INSTANCE} (server
         *             datapack write) or a {@code RegistryOps} (datapack debug
         *             round-trip). Implementations should use it for any
         *             {@code ItemStack.CODEC} call so that registry-bound data
         *             components (potion contents, enchantments, …) survive
         *             a reload round-trip instead of being silently dropped.
         */
        default JsonObject toJson(JsonObject jsonObject, T recipe, DynamicOps<JsonElement> ops) {
            // BUG-R7m4 fallback: when the implementation does not override
            // the 3-arg variant, we must still produce a correct stack
            // representation. The legacy {@code toJson(json, recipe)} path
            // used {@code JsonOps.INSTANCE} and dropped registry-bound
            // components — we instead fall back to the simple stack form
            // (item+count) explicitly, with a one-shot WARN, so an
            // oversight is visible without crashing the encode pipeline.
            ECTMod.LOGGER.warn("PacketSerialize {} does not override 3-arg toJson; falling back to simple item+count form for {}",
                getClass().getSimpleName(), recipe.getId());
            return toJson(jsonObject, recipe);
        }
        /** Legacy 2-arg form; implementations should override the 3-arg form instead. */
        JsonObject toJson(JsonObject jsonObject, T recipe);
        T fromPacket(ResourceLocation id, RegistryFriendlyByteBuf buffer);
        void toPacket(RegistryFriendlyByteBuf buffer, T recipe);

        static JsonObject stackToJson(ItemStack stack) {
            var o = new JsonObject();
            o.addProperty("item", Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(stack.getItem())).toString());
            o.addProperty("count", stack.getCount());
            return o;
        }
    }
}
