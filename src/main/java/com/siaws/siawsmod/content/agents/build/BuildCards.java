package com.siaws.siawsmod.content.agents.build;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * The build-card corpus loader (design pillar 4, "Build cards (the RAG corpus)"): datapack
 * JSON under {@code data/<ns>/siasworkshop_build_cards/*.json}, reloaded with the datapacks
 * via {@code AddReloadListenerEvent}. Parsing is defensive — a bad card costs one WARN line
 * and is skipped. At decision time the loop retrieves 1–3 cards by style-tag overlap and
 * injects them into the prompt ({@link com.siaws.siawsmod.content.llm.LlmPrompts}).
 *
 * <p>Card JSON: {@code {name, style_tags:[], size:"WxHxL"|{w,h,l}, materials_list:{id:count},
 * palette:[], step_summary:"", source_note:""}}.
 */
public final class BuildCards extends SimplePreparableReloadListener<Map<ResourceLocation, BuildCard>> {
    public static final BuildCards INSTANCE = new BuildCards();
    public static final String FOLDER = "siasworkshop_build_cards";

    private volatile Map<ResourceLocation, BuildCard> cards = Map.of();

    private BuildCards() {}

    /** All loaded cards, in datapack order. */
    public List<BuildCard> getCards() {
        return List.copyOf(cards.values());
    }

    /** Ranked retrieval: most style-tag overlap first, then resource id for stability. */
    public List<BuildCard> retrieve(Set<String> styleTags, int max) {
        record Hit(ResourceLocation id, BuildCard card, int overlap) {}
        return cards.entrySet().stream()
                .map(entry -> new Hit(entry.getKey(), entry.getValue(),
                        (int) entry.getValue().styleTags().stream().filter(styleTags::contains).count()))
                .filter(hit -> hit.overlap() > 0)
                .sorted(Comparator.comparingInt(Hit::overlap).reversed().thenComparing(Hit::id))
                .map(Hit::card)
                .limit(max)
                .toList();
    }

    @Override
    protected Map<ResourceLocation, BuildCard> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, BuildCard> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource> entry
                : resourceManager.listResources(FOLDER, id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation id = entry.getKey();
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.put(id, parseCard(JsonParser.parseReader(reader), id.toString()));
            } catch (RuntimeException | IOException e) {
                SiasWorkshop.LOGGER.warn("[BuildCards] skipping {}: {}", id, e.getMessage());
            }
        }
        return loaded;
    }

    @Override
    protected void apply(Map<ResourceLocation, BuildCard> prepared, ResourceManager resourceManager,
            ProfilerFiller profiler) {
        this.cards = Map.copyOf(prepared);
        if (!cards.isEmpty()) {
            SiasWorkshop.LOGGER.info("[BuildCards] loaded {} build card(s)", cards.size());
        }
    }

    /**
     * Parses one card from a JSON string (tests, or callers holding raw text). Throws
     * {@link IllegalArgumentException} naming the source when anything required is missing
     * or malformed.
     */
    public static BuildCard parseCard(String json, String sourceName) {
        final JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(sourceName + ": invalid JSON (" + e.getMessage() + ")");
        }
        return parseCard(element, sourceName);
    }

    /**
     * Parses one card from an already-parsed JSON element (the datapack reload path parses
     * once and passes the element straight through). Throws {@link IllegalArgumentException}
     * naming the source when anything required is missing or malformed.
     */
    public static BuildCard parseCard(JsonElement element, String sourceName) {
        final String source = sourceName;
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(source + ": not a JSON object");
        }
        JsonObject root = element.getAsJsonObject();

        String name = requiredString(root, "name", source);
        List<String> styleTags = stringArray(root, "style_tags", source);
        Vec3i size = parseSize(root.get("size"), source);
        Map<String, Integer> materials = new LinkedHashMap<>();
        if (root.has("materials_list") && root.get("materials_list").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("materials_list").entrySet()) {
                try {
                    materials.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(source + ": materials_list." + entry.getKey() + " is not an int");
                }
            }
        }
        List<String> palette = stringArray(root, "palette", source);
        String stepSummary = root.has("step_summary") && root.get("step_summary").isJsonPrimitive()
                ? root.get("step_summary").getAsString()
                : "";
        String sourceNote = root.has("source_note") && root.get("source_note").isJsonPrimitive()
                ? root.get("source_note").getAsString()
                : "";
        return new BuildCard(name, styleTags, size, materials, palette, stepSummary, sourceNote);
    }

    private static String requiredString(JsonObject root, String key, String source) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(source + ": missing " + key);
        }
        return root.get(key).getAsString();
    }

    private static List<String> stringArray(JsonObject root, String key, String source) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            throw new IllegalArgumentException(source + ": missing " + key);
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static Vec3i parseSize(@Nullable JsonElement element, String source) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(source + ": missing size");
        }
        final Vec3i size;
        try {
            if (element.isJsonPrimitive()) {
                String[] parts = element.getAsString().split("x");
                if (parts.length != 3) {
                    throw new IllegalArgumentException(source + ": size must be WxHxL");
                }
                size = new Vec3i(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
            } else {
                JsonObject object = element.getAsJsonObject();
                size = new Vec3i(object.get("w").getAsInt(), object.get("h").getAsInt(), object.get("l").getAsInt());
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(source + ": malformed size (" + e.getMessage() + ")");
        }
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IllegalArgumentException(source + ": non-positive size " + size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
        return size;
    }
}
