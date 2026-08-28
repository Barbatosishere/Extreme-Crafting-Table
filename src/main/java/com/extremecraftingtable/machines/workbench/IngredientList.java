package com.extremecraftingtable.machines.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.extremecraftingtable.utils.JsonArrayCollector;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class IngredientList implements Predicate<ItemStack> {
    private final List<IngredientWithCount> ingredientList;
    public IngredientList(List<IngredientWithCount> ingredientList) { this.ingredientList = ingredientList; }
    public IngredientList(IngredientWithCount ingredient) { this(List.of(ingredient)); }
    public List<IngredientWithCount> alternatives() { return ingredientList; }
    @Override public boolean test(ItemStack stack) { return ingredientList.stream().anyMatch(t -> t.test(stack)); }
    // Note: shrink is intentionally NOT provided here. The backtracking algorithm in
    // WorkbenchRecipe.canMatch() iterates over alternatives() and calls
    // IngredientWithCount.shrink() on each alternative individually, which is
    // correct for the full-search strategy. A per-IngredientList shrink that picks
    // the min-count alternative would give a different (greedy) result.
    boolean invalid() { return ingredientList.stream().allMatch(t -> t.ingredient().isEmpty()); }
    public List<ItemStack> stackList() { return ingredientList.stream().flatMap(i -> i.stackList().stream()).toList(); }
    public JsonElement toJson() {
        if (ingredientList.size() == 1) return ingredientList.get(0).toJson();
        return ingredientList.stream().map(IngredientWithCount::toJson).collect(JsonArrayCollector.instance());
    }
    public static IngredientList fromJson(JsonElement jsonElement) {
        return fromJson(jsonElement, JsonOps.INSTANCE);
    }

    public static IngredientList fromJson(JsonElement jsonElement, DynamicOps<JsonElement> ops) {
        if (jsonElement == null || jsonElement.isJsonNull()) return new IngredientList(List.of());
        if (jsonElement instanceof JsonArray array) return new IngredientList(IngredientWithCount.getSeq(array, ops));
        else if (jsonElement instanceof JsonObject object) return new IngredientList(new IngredientWithCount(object, ops));
        else if (jsonElement instanceof JsonPrimitive primitive) return new IngredientList(new IngredientWithCount(primitive));
        throw new IllegalArgumentException("Invalid Json type: " + jsonElement.getClass());
    }
    public void toPacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(ingredientList.size());
        for (IngredientWithCount ingredient : ingredientList) ingredient.toPacket(buffer);
    }
    public static IngredientList fromPacket(RegistryFriendlyByteBuf buffer) {
        var size = buffer.readVarInt();
        var list = IntStream.range(0, size).mapToObj(i -> IngredientWithCount.fromPacket(buffer)).toList();
        return new IngredientList(list);
    }
}