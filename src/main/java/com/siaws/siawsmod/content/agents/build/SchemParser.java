package com.siaws.siawsmod.content.agents.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;

/**
 * Interpreter for the Sponge Schematic format, versions 2 and 3 (.schem, gzipped NBT) — our
 * own reading of the publicly documented format on top of vanilla NBT classes; no parser code
 * is copied from WorldEdit or Litematica (licensing stance in the mod's AGENTS.md).
 *
 * <p>Handled: Width/Height/Length, the palette (v2: name→index compound; v3: ordered list of
 * {@code {Name, Properties}} compounds), BlockData (unsigned-LEB128 palette indices ordered
 * x-fastest, {@code (y * Length + z) * Width + x}), and Offset. Ignored gracefully: block
 * entities, entities, biomes, DataVersion; v3 palette {@code Properties} (placements store
 * plain block ids — default states). Schematics store air explicitly, so air palette entries
 * (air/cave_air/void_air) are skipped — agents must never "place air". Offset follows the
 * WorldEdit paste convention: {@code rel = dataPos - offset}, so the block at the schematic's
 * origin lands on the anchor. Anything missing, malformed, or unreasonably large throws
 * {@link BlueprintParseException} naming the file.
 */
public final class SchemParser {
    /** Blueprints are small structures; beyond this volume the file is rejected as hostile. */
    private static final long MAX_VOLUME = 512_000L;
    /** Palette sanity bound (a hostile PaletteMax must not make us allocate huge lists). */
    private static final int MAX_PALETTE_ENTRIES = 65_536;
    /** Bounded NBT read: 64 MiB of decompressed NBT is far beyond any real schematic. */
    private static final NbtAccounter ACCOUNTER = NbtAccounter.create(64L * 1024 * 1024);

    private SchemParser() {}

    public static Blueprint parse(Path file) throws BlueprintParseException {
        String name = stripExtension(file.getFileName().toString());
        try (InputStream in = Files.newInputStream(file)) {
            // NbtIo.readCompressed applies the gzip layer itself
            return parse(name, NbtIo.readCompressed(in, ACCOUNTER));
        } catch (IOException | RuntimeException e) {
            throw new BlueprintParseException(name, "unreadable gzipped NBT (" + e.getMessage() + ")");
        }
    }

    public static Blueprint parse(String name, CompoundTag root) throws BlueprintParseException {
        if (!root.contains("Width", Tag.TAG_SHORT) || !root.contains("Height", Tag.TAG_SHORT)
                || !root.contains("Length", Tag.TAG_SHORT)) {
            throw new BlueprintParseException(name, "missing Width/Height/Length");
        }
        int width = root.getShort("Width");
        int height = root.getShort("Height");
        int length = root.getShort("Length");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new BlueprintParseException(name, "non-positive size " + width + "x" + height + "x" + length);
        }
        long volume = (long) width * height * length;
        if (volume > MAX_VOLUME) {
            throw new BlueprintParseException(name, "volume " + volume + " exceeds cap " + MAX_VOLUME);
        }

        List<String> palette = readPalette(name, root);
        byte[] blockData = root.getByteArray("BlockData");
        if (blockData.length == 0 && volume > 0) {
            throw new BlueprintParseException(name, "missing BlockData");
        }

        int offsetX = 0;
        int offsetY = 0;
        int offsetZ = 0;
        if (root.contains("Offset")) {
            int[] offset = root.getIntArray("Offset");
            if (offset.length == 3) {
                offsetX = offset[0];
                offsetY = offset[1];
                offsetZ = offset[2];
            }
        }

        List<Blueprint.Placement> placements = new ArrayList<>();
        VarintReader reader = new VarintReader(blockData);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    Integer paletteIndex = reader.next();
                    if (paletteIndex == null) {
                        throw new BlueprintParseException(name, "BlockData truncated at " + placements.size() + " blocks");
                    }
                    if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                        throw new BlueprintParseException(name, "palette index out of range: " + paletteIndex);
                    }
                    String blockId = palette.get(paletteIndex);
                    if (isAir(blockId)) {
                        continue; // schematics store air explicitly; agents must not place it
                    }
                    BlockPos rel = new BlockPos(x - offsetX, y - offsetY, z - offsetZ);
                    placements.add(new Blueprint.Placement(blockId, rel));
                }
            }
        }
        return new Blueprint(name, new Vec3i(width, height, length), placements);
    }

    private static boolean isAir(String blockId) {
        return blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air")
                || blockId.equals("minecraft:void_air");
    }

    private static List<String> readPalette(String name, CompoundTag root) throws BlueprintParseException {
        List<String> palette = new ArrayList<>();
        if (root.contains("Palette", Tag.TAG_COMPOUND)) {
            // v2: name → index compound; indices must be dense and in range (never grown to fit)
            CompoundTag paletteTag = root.getCompound("Palette");
            int maxIndex = -1;
            for (String key : paletteTag.getAllKeys()) {
                int index = paletteTag.getInt(key);
                if (index < 0) {
                    throw new BlueprintParseException(name, "palette index " + index + " for " + key);
                }
                maxIndex = Math.max(maxIndex, index);
            }
            int size = root.contains("PaletteMax", Tag.TAG_INT) ? root.getInt("PaletteMax") : maxIndex + 1;
            if (size <= 0 || size > MAX_PALETTE_ENTRIES || maxIndex >= size) {
                throw new BlueprintParseException(name, "palette size " + size + " with max index " + maxIndex);
            }
            for (int i = 0; i < size; i++) {
                palette.add(null);
            }
            for (String key : paletteTag.getAllKeys()) {
                palette.set(paletteTag.getInt(key), key);
            }
        } else if (root.contains("Palette", Tag.TAG_LIST)) {
            // v3: ordered list of {Name, Properties} compounds
            ListTag paletteList = root.getList("Palette", Tag.TAG_COMPOUND);
            for (int i = 0; i < paletteList.size(); i++) {
                palette.add(paletteList.getCompound(i).getString("Name"));
            }
        } else {
            throw new BlueprintParseException(name, "missing Palette");
        }
        if (palette.isEmpty() || palette.stream().anyMatch(entry -> entry == null || entry.isEmpty())) {
            throw new BlueprintParseException(name, "palette has unset entries");
        }
        return palette;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Unsigned-LEB128 reader over the BlockData byte array; null when the stream is exhausted. */
    private static final class VarintReader {
        private final byte[] data;
        private int cursor;

        private VarintReader(byte[] data) {
            this.data = data;
        }

        @javax.annotation.Nullable
        Integer next() {
            int value = 0;
            int shift = 0;
            while (cursor < data.length) {
                int current = data[cursor++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 28) {
                    return null; // malformed varint
                }
            }
            return null;
        }
    }
}
