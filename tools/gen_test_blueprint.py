#!/usr/bin/env python3
"""Generate a vanilla-structure .nbt blueprint (3x3x3 stone-bricks cube) readable by
StructureNbtParser, without external NBT libraries. Same minimal subset as the
gametest fixtures: size/palette/blocks/entities/DataVersion, gzipped."""
import gzip
import io
import struct

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARR, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARR, TAG_LONG_ARR = 9, 10, 11, 12

def tag_string(name, value):
    data = value.encode("utf-8")
    return bytes([TAG_STRING]) + name_bytes(name) + struct.pack(">H", len(data)) + data

def name_bytes(name):
    data = name.encode("utf-8")
    return struct.pack(">H", len(data)) + data

def tag_int(name, value):
    return bytes([TAG_INT]) + name_bytes(name) + struct.pack(">i", value)

def tag_int_array(name, values):
    out = bytes([TAG_INT_ARR]) + name_bytes(name) + struct.pack(">i", len(values))
    for v in values:
        out += struct.pack(">i", v)
    return out

def tag_list(name, elem_type, payload):
    return (bytes([TAG_LIST]) + name_bytes(name) + bytes([elem_type])
            + struct.pack(">i", len(payload)) + b"".join(payload))

def compound(payload):
    return b"".join(payload) + bytes([TAG_END])

def palette_entry(block_name):
    return compound([tag_string("Name", block_name)])

def block_entry(x, y, z, state):
    return compound([
        tag_int_array("pos", [x, y, z]),
        tag_int("state", state),
    ])

SIZE = 3
blocks = [block_entry(x, y, z, 0)
          for x in range(SIZE) for y in range(SIZE) for z in range(SIZE)]

root = compound([
    tag_int_array("size", [SIZE, SIZE, SIZE]),
    tag_list("palette", TAG_COMPOUND, [palette_entry("minecraft:stone_bricks")]),
    tag_list("blocks", TAG_COMPOUND, blocks),
    tag_list("entities", TAG_COMPOUND, []),
    tag_int("DataVersion", 3955),
])

import os
os.makedirs("run/config/siasworkshop/blueprints", exist_ok=True)
path = "run/config/siasworkshop/blueprints/mazons.nbt"
# NBT file framing: root type byte + (empty) root name + compound payload
with gzip.open(path, "wb") as fh:
    fh.write(bytes([TAG_COMPOUND]) + name_bytes("") + root)
print(f"wrote {path} ({len(blocks)} blocks)")

# round-trip validation: gunzip and check the root header
with gzip.open(path, "rb") as fh:
    raw = fh.read()
assert raw[0] == TAG_COMPOUND, "root must be a compound"
assert raw[1:3] == b"\x00\x00", "root name must be empty"
print(f"round-trip OK, {len(raw)} uncompressed bytes, root tag {raw[0]}")
