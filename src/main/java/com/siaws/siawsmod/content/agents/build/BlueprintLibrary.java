package com.siaws.siawsmod.content.agents.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.siaws.siawsmod.SiasWorkshop;

import net.neoforged.fml.loading.FMLPaths;

/**
 * The blueprint library: everything the build queue can build, scanned once per server start
 * from {@code config/siasworkshop/blueprints/} (player-imported .schem/.nbt files — the
 * BYO-library model from the design's build-knowledge table). Malformed files are skipped
 * with one WARN line each, never fatal. Singleton: scanned from ServerStartingEvent, read by
 * the build queue and the decision loop.
 */
public final class BlueprintLibrary {
    public static final BlueprintLibrary INSTANCE = new BlueprintLibrary();

    private final Map<String, Blueprint> blueprints = new LinkedHashMap<>();

    private BlueprintLibrary() {}

    /** Rescans the import folder. Call on server start (and after dropping new files, via restart). */
    public void reload() {
        blueprints.clear();
        Path folder = FMLPaths.CONFIGDIR.get().resolve("siasworkshop/blueprints");
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                try {
                    Blueprint blueprint = fileName.endsWith(".schem")
                            ? SchemParser.parse(file)
                            : fileName.endsWith(".nbt")
                                    ? StructureNbtParser.parse(file)
                                    : null;
                    if (blueprint != null) {
                        // spaces break the task-detail round-trip (detail is space-separated);
                        // normalize at load so file names and board keys always agree
                        String normalized = blueprint.name().replace(' ', '_');
                        if (!normalized.equals(blueprint.name())) {
                            SiasWorkshop.LOGGER.warn("[BlueprintLibrary] renamed '{}' to '{}'", blueprint.name(), normalized);
                            blueprint = new Blueprint(normalized, blueprint.size(), blueprint.placements());
                        }
                        blueprints.put(normalized.toLowerCase(Locale.ROOT), blueprint);
                    }
                } catch (BlueprintParseException e) {
                    SiasWorkshop.LOGGER.warn("[BlueprintLibrary] skipping {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            SiasWorkshop.LOGGER.warn("[BlueprintLibrary] could not scan {}: {}", folder, e.getMessage());
        }
        if (!blueprints.isEmpty()) {
            SiasWorkshop.LOGGER.info("[BlueprintLibrary] loaded {} blueprint(s) from {}", blueprints.size(), folder);
        }
    }

    /** Case-insensitive lookup by blueprint name; null when unknown. */
    @Nullable
    public Blueprint get(String name) {
        return blueprints.get(name.toLowerCase(Locale.ROOT));
    }

    /** All loaded blueprints, in load order. */
    public List<Blueprint> all() {
        return List.copyOf(blueprints.values());
    }

    public int size() {
        return blueprints.size();
    }

    /** Test/dev hook: register a blueprint without an import file on disk. */
    public void registerForTest(Blueprint blueprint) {
        blueprints.put(blueprint.name().toLowerCase(Locale.ROOT), blueprint);
    }
}
