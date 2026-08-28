package com.extremecraftingtable.machines.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

public record IngredientWithCount(Ingredient ingredient, int count) implements Predicate<ItemStack> {
    /**
     * Hard ceiling on a single ingredient slot. A datapack recipe with
     * {@code "count": 2147483647} would otherwise produce a stack whose count
     * exceeds the workbench's own {@code MAX_SLOT_STACK} (81920), break the
     * SlotCounts save/restore side-tag range, and confuse JEI's slot rendering
     * which expects ≤ 99. (Note: 1.21.1's {@code ItemStack.setCount} is a plain
     * assignment — the codec's intRange(1, 99) is the enforcement point, not
     * setCount itself.)
     */
    public static final int MAX_INGREDIENT_COUNT = 81920;

    // Compact constructor: validate that count is positive and bounded.
    // Zero or negative count would cause test() to always pass and shrink()
    // to consume nothing (or grow the stack for negative), enabling infinite
    // free crafting from a single item. A count above MAX_INGREDIENT_COUNT
    // would break the 1.21.1 ItemStack invariant at the canMatch / JEI paths
    // and could be abused for >81920 stacks.
    public IngredientWithCount {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive, got: " + count);
        }
        if (count > MAX_INGREDIENT_COUNT) {
            throw new IllegalArgumentException(
                "Count " + count + " exceeds MAX_INGREDIENT_COUNT (" + MAX_INGREDIENT_COUNT + ")");
        }
    }

    public IngredientWithCount(JsonObject jsonObject) {
        this(parseIngredient(jsonObject), countFrom(jsonObject));
    }

    public IngredientWithCount(JsonObject jsonObject, DynamicOps<JsonElement> ops) {
        this(parseIngredient(jsonObject, ops), countFrom(jsonObject));
    }

    public IngredientWithCount(JsonPrimitive jsonPrimitive) {
        this(parseIngredientSafe(shorthandToObject(jsonPrimitive)), 1);
    }

    private static Ingredient parseIngredient(JsonObject jsonObject) {
        return parseIngredient(jsonObject, JsonOps.INSTANCE);
    }

    private static Ingredient parseIngredient(JsonObject jsonObject, DynamicOps<JsonElement> ops) {
        if (jsonObject.has("items")) {
            return parseIngredientElement(jsonObject.get("items"), ops);
        }
        if (jsonObject.has("ingredients")) {
            return parseIngredientElement(jsonObject.get("ingredients"), ops);
        }
        // Single-object form {"item": ...} / {"tag": ...}: pass directly to Ingredient.CODEC.
        // DO NOT wrap in {"items": [...]} — that format is only produced by the CODEC on
        // encode, but the CODEC itself does NOT accept it as input. The CODEC handles
        // both {"item":...} (single Value) and [{...},...] (list of Values) directly.
        // Wrapping in {"items": [...]} makes the decode fall through all alternatives
        // and silently return Ingredient.EMPTY, which makes the recipe hasContent=false.
        return parseIngredientSafe(modifyCount(jsonObject), ops);
    }

    /**
     * Extracts the count from a JSON object, checking the top-level "count" key.
     * Falls back to 1 if no top-level count is present.
     * (Member-level counts inside "items"/"ingredients" arrays are not read;
     * they are stripped before Ingredient.CODEC and would be ambiguous when
     * alternatives have different counts.)
     */
    private static int countFrom(JsonObject jsonObject) {
        // Only the top-level "count" is authoritative. Member-level counts inside
        // "items"/"ingredients" arrays are stripped before Ingredient.CODEC and
        // ignored here; they would be ambiguous when alternatives have different
        // counts (e.g. [{"item":"A","count":3},{"item":"B","count":5}]).
        if (jsonObject.has("count")) {
            return GsonHelper.getAsInt(jsonObject, "count");
        }
        return 1;
    }

    /**
     * Parses an ingredient JSON element, normalizing string-shorthand entries
     * ("minecraft:stone" or "#minecraft:planks") — either a bare primitive or members of
     * an array — into the object form that Ingredient.CODEC understands. Without this,
     * shorthand input fails to decode (throws) at recipe-load time.
     */
    private static Ingredient parseIngredientElement(JsonElement element) {
        return parseIngredientElement(element, JsonOps.INSTANCE);
    }

    private static Ingredient parseIngredientElement(JsonElement element, DynamicOps<JsonElement> ops) {
        if (element instanceof JsonArray array) {
            JsonArray normalized = new JsonArray();
            for (JsonElement member : array) {
                if (member instanceof JsonPrimitive p) {
                    normalized.add(shorthandToObject(p));
                } else if (member instanceof JsonObject obj) {
                    normalized.add(modifyCount(obj));
                } else {
                    normalized.add(member);
                }
            }
            // Pass the normalized array directly to Ingredient.CODEC, which accepts
            // a JSON array of Values (e.g. [{"item":"a"},{"item":"b"}]) as a list input.
            return parseIngredientSafe(normalized, ops);
        }
        if (element instanceof JsonPrimitive p) {
            return parseIngredientSafe(shorthandToObject(p), ops);
        }
        if (element instanceof JsonObject obj) {
            if (obj.has("count")) {
                throw new IllegalArgumentException(
                    "Ingredient object has a 'count' field, which is not supported. " +
                    "Use the outer 'count' field instead. Offending object: " + obj);
            }
            // Pass the single Value object directly to Ingredient.CODEC.
            return parseIngredientSafe(obj, ops);
        }
        return parseIngredientSafe(element, ops);
    }

    /**
     * Applies the shorthand-string unescape rule: {@code \\} -> {@code \} and
     * {@code \:} -> {@code :}. Escaped colons/backslashes in item and tag strings
     * (e.g. "#minecraft\\:planks") would otherwise break ResourceLocation parsing
     * and leave a literal backslash in the resolved ID.
     */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '\\' || next == ':') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Converts a string-shorthand primitive into the {"item": ...} or {"tag": ...} object
     * form that NeoForge's Ingredient.CODEC decodes ("#tag" for tags, plain string for an
     * item ID). The raw input is first unescaped ({@code \\} -> {@code \},
     * {@code \:} -> {@code :}). Mirrors the normalization in toJson().
     */
    private static JsonObject shorthandToObject(JsonPrimitive p) {
        JsonObject obj = new JsonObject();
        String raw = unescape(p.getAsString());
        if (raw.startsWith("#")) {
            String tag = raw.substring(1);
            ResourceLocation loc = ResourceLocation.tryParse(tag);
            obj.add("tag", loc != null ? new JsonPrimitive(loc.toString()) : new JsonPrimitive(tag));
        } else {
            obj.add("item", new JsonPrimitive(raw));
        }
        return obj;
    }

    private static Ingredient parseIngredientSafe(JsonElement element) {
        return parseIngredientSafe(element, JsonOps.INSTANCE);
    }

    /**
     * Safely parses a JsonElement into an Ingredient using the given ops, returning
     * Ingredient.EMPTY when Ingredient.CODEC does not produce a result (e.g., malformed
     * input, empty arrays, or unrecognized structure). Using the caller's ops (typically
     * RegistryOps from the RecipeManager) ensures data components and registry-bound
     * references are resolved correctly.
     */
    private static Ingredient parseIngredientSafe(JsonElement element, DynamicOps<JsonElement> ops) {
        return Ingredient.CODEC.parse(ops, element).result().orElse(Ingredient.EMPTY);
    }

    public IngredientWithCount(ItemStack stack) {
        this(Ingredient.of(stack), stack.getCount());
    }

    @Override public boolean test(ItemStack stack) { return ingredient.test(stack) && stack.getCount() >= count; }

    boolean shrink(ItemStack stack) {
        if (test(stack)) { stack.shrink(count); return true; }
        return false;
    }

    public List<ItemStack> stackList() {
        ItemStack[] items = ingredient.getItems();
        return Arrays.stream(items).map(h -> {
            ItemStack copy = h.copy();
            copy.setCount(count);
            return copy;
        }).toList();
    }

    public JsonElement toJson() {
        // Handle empty ingredient (Ingredient.EMPTY) explicitly: encodeStart
        // returns an empty JsonArray [] that getSeq turns into an empty list,
        // which makes IngredientList.invalid() return true (vacuously true).
        // Produce a proper object form {"items":[], "count": N} that
        // fromElement can deserialize back into Ingredient.EMPTY.
        if (ingredient.getItems().length == 0) {
            JsonObject obj = new JsonObject();
            obj.add("items", new JsonArray());
            obj.addProperty("count", count);
            return obj;
        }
        var result = Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ingredient).result();
        if (result.isPresent()) {
            var encoded = result.get();
            if (encoded instanceof JsonObject ingObj) {
                // Create a new JsonObject instead of mutating the codec's output.
                // The codec may return a shared or cached object; modifying it in-place
                // would corrupt subsequent encodes of the same Ingredient.
                JsonObject obj = new JsonObject();
                ingObj.entrySet().forEach(e -> obj.add(e.getKey(), e.getValue()));
                obj.addProperty("count", count);
                return obj;
            }
            if (encoded instanceof JsonPrimitive primitive) {
                // String shorthand: "minecraft:stone" (item) or "#minecraft:planks" (tag).
                // Place tags under "tag" so the JSON round-trips correctly through Ingredient.CODEC.
                JsonObject obj = new JsonObject();
                String raw = primitive.getAsString();
                if (raw.startsWith("#")) {
                    String tag = raw.substring(1);
                    ResourceLocation loc = ResourceLocation.tryParse(tag);
                    obj.add("tag", loc != null ? new JsonPrimitive(loc.toString()) : new JsonPrimitive(tag));
                } else obj.add("item", new JsonPrimitive(raw));
                obj.addProperty("count", count);
                return obj;
            }
            JsonObject obj = new JsonObject();
            if (encoded instanceof JsonArray ingArray) {
                if (ingArray.size() == 1) {
                    // Single-element array: flatten to primitive form so round-trip works via Ingredient.CODEC
                    JsonElement single = ingArray.get(0);
                    if (single instanceof JsonObject singleObj) {
                        singleObj.entrySet().forEach(e -> obj.add(e.getKey(), e.getValue()));
                    } else {
                        // Single non-object element (a string shorthand like "minecraft:stone"
                        // or "#minecraft:planks"). Open it into the "item"/"tag" keys that
                        // Ingredient.CODEC can decode; a bare "ingredient" key is unrecognized.
                        String raw = single.getAsString();
                        if (raw.startsWith("#")) {
                            String tag = raw.substring(1);
                            ResourceLocation loc = ResourceLocation.tryParse(tag);
                            obj.add("tag", loc != null ? new JsonPrimitive(loc.toString()) : new JsonPrimitive(tag));
                        } else {
                            obj.add("item", single);
                        }
                    }
                } else {
                    obj.add("items", ingArray);
                }
            }
            obj.addProperty("count", count);
            return obj;
        }
        JsonObject fallback = new JsonObject();
        fallback.add("items", new JsonArray());
        fallback.addProperty("count", count);
        return fallback;
    }

    public void toPacket(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(count);
        Ingredient.CONTENTS_STREAM_CODEC.encode(buf, ingredient);
    }

    public static List<IngredientWithCount> getSeq(JsonElement jsonElement) {
        return getSeq(jsonElement, JsonOps.INSTANCE);
    }

    public static List<IngredientWithCount> getSeq(JsonElement jsonElement, DynamicOps<JsonElement> ops) {
        if (jsonElement == null || jsonElement.isJsonNull()) return List.of();
        if (jsonElement instanceof JsonArray jsonArray)
            return StreamSupport.stream(jsonArray.spliterator(), false)
                .map(e -> fromElement(e, ops))
                .toList();
        return List.of(fromElement(jsonElement, ops));
    }

    private static IngredientWithCount fromElement(JsonElement element, DynamicOps<JsonElement> ops) {
        if (element == null || element.isJsonNull()) return new IngredientWithCount(Ingredient.EMPTY, 1);
        if (element instanceof JsonObject object) return new IngredientWithCount(object, ops);
        if (element instanceof JsonPrimitive primitive) return new IngredientWithCount(primitive);
        if (element instanceof JsonArray array) {
            return new IngredientWithCount(parseIngredientElement(array, ops), 1);
        }
        throw new IllegalArgumentException("Invalid ingredient element: " + element.getClass());
    }

    public static IngredientWithCount fromPacket(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        return new IngredientWithCount(Ingredient.CONTENTS_STREAM_CODEC.decode(buf), count);
    }

    private static JsonObject modifyCount(JsonObject object) {
        var clone = new JsonObject();
        object.entrySet().forEach(e -> clone.add(e.getKey(), e.getValue()));
        if (clone.has("count")) {
            clone.remove("count");
        }
        return clone;
    }
}
