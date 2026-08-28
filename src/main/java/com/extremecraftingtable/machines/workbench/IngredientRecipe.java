package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import com.extremecraftingtable.utils.JsonArrayCollector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import java.util.List;

public class IngredientRecipe extends WorkbenchRecipe {
    private final List<IngredientList> input;
    public IngredientRecipe(ResourceLocation location, ItemStack output, long energy, boolean showInJEI, List<IngredientList> input) {
        super(location, output, energy, showInJEI);
        // Defensive copy: the packet path (fromPacket) and the compact constructor both
        // pass caller-built lists; keep an unmodifiable copy so external mutation cannot
        // corrupt the recipe's inputs after construction (recipes are compared by identity).
        this.input = List.copyOf(input);
    }

    public IngredientRecipe(ResourceLocation location, JsonObject jsonObject, DynamicOps<JsonElement> ops) {
        this(location,
             jsonObject.has("result") && !jsonObject.get("result").isJsonNull()
                 ? parseResult(jsonObject.get("result"), ops)
                 : ItemStack.EMPTY,
             Math.max(0L, GsonHelper.getAsLong(jsonObject, "energy", 1000L)) * TileWorkbench.ONE_FE,
             GsonHelper.getAsBoolean(jsonObject, "showInJEI", true),
             parseIngredients(jsonObject, ops));
    }

    private static ItemStack parseResult(JsonElement result, DynamicOps<JsonElement> ops) {
        // ItemStack.SINGLE_ITEM_CODEC requires the item id under the "id" key, but manually
        // authored recipe JSON uses the "item" key (matching Ingredient.CODEC).
        // Normalize "item" to "id" when it is present.
        if (result.isJsonObject()) {
            JsonObject resultObject = result.getAsJsonObject();
            if (resultObject.has("item") && !resultObject.has("id")) {
                resultObject = resultObject.deepCopy();
                resultObject.add("id", resultObject.remove("item"));
                result = resultObject;
            }
        }
        // Handle shorthand string primitives (e.g. "minecraft:stone").
        // ItemStack.SINGLE_ITEM_CODEC expects an object with "id"/"count" keys,
        // so wrap the bare string into {"id": "..."} form.
        if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
            JsonObject wrapped = new JsonObject();
            wrapped.add("id", result.getAsJsonPrimitive());
            result = wrapped;
        }
        var parsed = ItemStack.SINGLE_ITEM_CODEC.parse(ops, result);
        if (parsed.result().isEmpty()) {
            // Without registry context (plain JsonOps), outputs with registry-bound
            // data components fail to encode; surfacing rather than silently erasing
            // the recipe (which would then fail hasContent() and drop from the finder).
            ECTMod.LOGGER.warn("[Extreme Crafting Table] Failed to parse recipe result `{}`; the recipe will be unavailable (missing registry context or unsupported component).", result);
        }
        return parsed.result().orElse(ItemStack.EMPTY);
    }

    private static List<IngredientList> parseIngredients(JsonObject jsonObject, DynamicOps<JsonElement> ops) {
        JsonElement ingredientsElement = jsonObject.get("ingredients");
        if (ingredientsElement == null || ingredientsElement.isJsonNull()) return List.of();
        if (ingredientsElement.isJsonObject() || ingredientsElement.isJsonPrimitive())
            return List.of(IngredientList.fromJson(ingredientsElement, ops));
        if (ingredientsElement.isJsonArray())
            return ((JsonArray) ingredientsElement).asList().stream()
                .map(e -> IngredientList.fromJson(e, ops)).toList();
        throw new IllegalArgumentException("Bad Json type of ingredients: " + ingredientsElement);
    }

    @Override public List<IngredientList> inputs() { return input; }
    @Override protected String getSubTypeName() { return "default"; }
    @Override protected ItemStack getOutput(List<ItemStack> inventory, HolderLookup.Provider access) { return output.copy(); }
    @Override public boolean hasContent() { return !output.isEmpty() && !input.isEmpty() && input.stream().noneMatch(IngredientList::invalid); }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return output.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
}

class IngredientRecipeSerialize implements WorkbenchRecipeSerializer.PacketSerialize<IngredientRecipe> {
    @Override
    public IngredientRecipe fromJson(ResourceLocation id, JsonObject jsonObject, DynamicOps<JsonElement> ops) {
        return new IngredientRecipe(id, jsonObject, ops);
    }

    @Override
    public JsonObject toJson(JsonObject jsonObject, IngredientRecipe recipe, DynamicOps<JsonElement> ops) {
        if (!recipe.output.isEmpty()) {
            // BUG-R7m4 fix: pass the caller's ops through so a RegistryOps
            // (server datapack write, datapack debug round-trip) can encode
            // registry-bound data components (potion_contents, enchantments,
            // …). Previously this used JsonOps.INSTANCE unconditionally,
            // which silently dropped those components. Fall back to the
            // simple item+count form only when the codec genuinely fails.
            jsonObject.add("result", ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, recipe.output)
                .result().orElseGet(() -> WorkbenchRecipeSerializer.PacketSerialize.stackToJson(recipe.output)));
        }
        jsonObject.addProperty("energy", recipe.getRequiredEnergy() / TileWorkbench.ONE_FE);
        jsonObject.addProperty("showInJEI", recipe.showInJEI());
        jsonObject.add("ingredients", recipe.inputs().stream().map(IngredientList::toJson).collect(JsonArrayCollector.instance()));
        return jsonObject;
    }

    @Override
    public JsonObject toJson(JsonObject jsonObject, IngredientRecipe recipe) {
        // Legacy 2-arg path used by callers that do not have a DynamicOps
        // available. Routed through the 3-arg form with JsonOps so the
        // fallback message is consistent.
        return toJson(jsonObject, recipe, JsonOps.INSTANCE);
    }

    @Override
    public IngredientRecipe fromPacket(ResourceLocation id, RegistryFriendlyByteBuf buffer) {
        ItemStack output = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
        long energy = Math.max(0L, buffer.readLong());
        boolean showInJei = buffer.readBoolean();
        int inputSize = buffer.readVarInt();
        List<IngredientList> inputs = java.util.stream.IntStream.range(0, inputSize).mapToObj(i -> IngredientList.fromPacket(buffer)).toList();
        return new IngredientRecipe(id, output, energy, showInJei, inputs);
    }

    @Override
    public void toPacket(RegistryFriendlyByteBuf buffer, IngredientRecipe recipe) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, recipe.output);
        buffer.writeLong(recipe.getRequiredEnergy()).writeBoolean(recipe.showInJEI());
        buffer.writeVarInt(recipe.inputs().size());
        for (IngredientList ing : recipe.inputs()) ing.toPacket(buffer);
    }
}
