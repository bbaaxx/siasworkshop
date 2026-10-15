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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Interpreter for the vanilla single-structure format saved by structure blocks (.nbt,
 * gzipped NBT): {@code size} int list, a {@code palette} list of block-state compounds (or
 * {@code palettes}, of which the first is used), and a {@code blocks} list of
 * {@code {pos: int[3], state: int}} entries. Entities are ignored. Same licensing stance as
 * {@link SchemParser}: own interpretation, vanilla NBT classes only.
 */
public final class StructureNbtParser {
    /** Blueprints are small structures; beyond this volume the file is rejected as hostile. */
    private static final long MAX_VOLUME = 512_000L;
    /** Bounded NBT read: 64 MiB of decompressed NBT is far beyond any real structure file. */
    private static final NbtAccounter ACCOUNTER = NbtAccounter.create(64L * 1024 * 1024);

    private StructureNbtParser() {}

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
        int[] size = root.getIntArray("size");
        if (size.length != 3 || size[0] <= 0 || size[1] <= 0 || size[2] <= 0) {
            throw new BlueprintParseException(name, "missing or invalid size");
        }
        long volume = (long) size[0] * size[1] * size[2];
        if (volume > MAX_VOLUME) {
            throw new BlueprintParseException(name, "volume " + volume + " exceeds cap " + MAX_VOLUME);
        }

        ListTag paletteTag = paletteList(name, root);
        List<String> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(paletteTag.getCompound(i).getString("Name"));
        }
        if (palette.isEmpty() || palette.stream().anyMatch(String::isEmpty)) {
            throw new BlueprintParseException(name, "palette has unset entries");
        }

        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.isEmpty()) {
            throw new BlueprintParseException(name, "missing blocks list");
        }
        List<Blueprint.Placement> placements = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            int[] pos = block.getIntArray("pos");
            if (pos.length != 3) {
                throw new BlueprintParseException(name, "block " + i + " has invalid pos");
            }
            int state = block.getInt("state");
            if (state < 0 || state >= palette.size()) {
                throw new BlueprintParseException(name, "block " + i + " state out of range: " + state);
            }
            placements.add(new Blueprint.Placement(palette.get(state), new BlockPos(pos[0], pos[1], pos[2])));
        }
        return new Blueprint(name, new Vec3i(size[0], size[1], size[2]), placements);
    }

    private static ListTag paletteList(String name, CompoundTag root) throws BlueprintParseException {
        if (root.contains("palette", Tag.TAG_LIST)) {
            return root.getList("palette", Tag.TAG_COMPOUND);
        }
        if (root.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = root.getList("palettes", Tag.TAG_LIST);
            if (!palettes.isEmpty()) {
                return palettes.getList(0);
            }
        }
        throw new BlueprintParseException(name, "missing palette");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
