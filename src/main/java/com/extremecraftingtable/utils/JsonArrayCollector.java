package com.extremecraftingtable.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.stream.Collector;

/**
 * Collector that gathers a stream of JsonElements into a JsonArray.
 * <p>
 * Replaces QuarryPlus's MapMulti.jsonArrayCollector() helper.
 * <p>
 * Original: QuarryPlus MapMulti by Kotori316
 * <p>
 * Sequential-only and ORDER-PRESERVING: the accumulator appends in encounter
 * order, and the recipe JSON round-trip (IngredientRecipe.parseIngredients)
 * depends on that order. No UNORDERED characteristic — declaring one would be
 * a false contract and could legitimise reordering. Also not safe for
 * {@code parallel()} streams: the combiner mutates the {@code left} JsonArray
 * in place, and JsonArray is not thread-safe.
 */
public final class JsonArrayCollector {

    private JsonArrayCollector() {
    }

    public static Collector<JsonElement, ?, JsonArray> instance() {
        return Collector.of(
            JsonArray::new,
            JsonArray::add,
            (left, right) -> {
                right.forEach(left::add);
                return left;
            }
        );
    }
}
