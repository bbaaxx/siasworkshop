package com.siaws.siawsmod.content.agents.build;

import java.util.List;
import java.util.Map;

import net.minecraft.core.Vec3i;

/**
 * One build card from the RAG corpus (design pillar 4 "Build cards"): a distilled build idea
 * datapacks can add under {@code data/<ns>/siasworkshop_build_cards/*.json}. The card is
 * grounded facts only — sizes, materials, palettes — never copied prose; the LLM selects and
 * adapts, it never fetches (retrieve-and-inject).
 *
 * @param name           unique-ish display name
 * @param styleTags      retrieval tags (e.g. defense, storage, cherry)
 * @param size           footprint width×height×length
 * @param materialsList  block id → count
 * @param palette        block ids the build uses
 * @param stepSummary    short procedural summary in our own words
 * @param sourceNote     where the idea came from (guide, player, own design)
 */
public record BuildCard(
        String name,
        List<String> styleTags,
        Vec3i size,
        Map<String, Integer> materialsList,
        List<String> palette,
        String stepSummary,
        String sourceNote) {

    public BuildCard {
        styleTags = List.copyOf(styleTags);
        materialsList = Map.copyOf(materialsList);
        palette = List.copyOf(palette);
    }
}
