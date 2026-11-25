#!/usr/bin/env python3
"""Rename same-length, same-GNU-hash JNI prefixes in ELF shared libraries."""

from __future__ import annotations

import argparse
import io
import struct
from pathlib import Path

from elftools.elf.elffile import ELFFile


def gnu_hash(name: str) -> int:
    value = 5381
    for byte in name.encode("utf-8"):
        value = ((value * 33) + byte) & 0xFFFFFFFF
    return value


def hash_lookup(names: list[str], gnu_hash_data: bytes, target: str, elfclass: int) -> bool:
    nbuckets, symoffset, bloom_size, bloom_shift = struct.unpack_from("<IIII", gnu_hash_data, 0)
    word_bits = 64 if elfclass == 64 else 32
    word_fmt = "Q" if word_bits == 64 else "I"
    offset = 16
    bloom = list(struct.unpack_from(f"<{bloom_size}{word_fmt}", gnu_hash_data, offset))
    offset += bloom_size * (word_bits // 8)
    buckets = list(struct.unpack_from(f"<{nbuckets}I", gnu_hash_data, offset))
    offset += nbuckets * 4
    chain_count = len(names) - symoffset
    chains = list(struct.unpack_from(f"<{chain_count}I", gnu_hash_data, offset))

    value = gnu_hash(target)
    word = bloom[(value // word_bits) % bloom_size]
    if not (word & (1 << (value % word_bits))):
        return False
    if not (word & (1 << ((value >> bloom_shift) % word_bits))):
        return False

    symbol_index = buckets[value % nbuckets]
    if symbol_index == 0:
        return False
    while symbol_index < len(names):
        chain_hash = chains[symbol_index - symoffset]
        if (chain_hash | 1) == (value | 1) and names[symbol_index] == target:
            return True
        if chain_hash & 1:
            return False
        symbol_index += 1
    return False


def patch_file(path: Path, old_prefix: str, new_prefix: str) -> int:
    if len(old_prefix) != len(new_prefix):
        raise ValueError("old and new prefixes must have identical byte lengths")

    data = bytearray(path.read_bytes())
    old_bytes = old_prefix.encode("utf-8")
    new_bytes = new_prefix.encode("utf-8")
    replacements = data.count(old_bytes)
    if replacements == 0:
        raise ValueError(f"prefix not found in {path}")

    with ELFFile(io.BytesIO(data)) as elf:
        dynsym = elf.get_section_by_name(".dynsym")
        gnu_hash_section = elf.get_section_by_name(".gnu.hash")
        if dynsym is None or gnu_hash_section is None:
            raise ValueError(f"missing .dynsym or .gnu.hash in {path}")
        old_names = [symbol.name for symbol in dynsym.iter_symbols()]
        affected = [name for name in old_names if name.startswith(old_prefix)]
        if not affected:
            raise ValueError(f"no dynamic symbols use prefix in {path}")
        for old_name in affected:
            suffix = old_name[len(old_prefix) :]
            if gnu_hash(old_prefix + suffix) != gnu_hash(new_prefix + suffix):
                raise ValueError(f"hash would change for {old_name}")

    data = data.replace(old_bytes, new_bytes)

    with ELFFile(io.BytesIO(data)) as elf:
        dynsym = elf.get_section_by_name(".dynsym")
        gnu_hash_section = elf.get_section_by_name(".gnu.hash")
        names = [symbol.name for symbol in dynsym.iter_symbols()]
        gnu_hash_data = gnu_hash_section.data()
        for old_name in affected:
            new_name = new_prefix + old_name[len(old_prefix) :]
            if not hash_lookup(names, gnu_hash_data, new_name, elf.elfclass):
                raise ValueError(f"GNU hash lookup failed for {new_name}")

    path.write_bytes(data)
    return replacements


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("library", type=Path)
    parser.add_argument("old_prefix")
    parser.add_argument("new_prefix")
    args = parser.parse_args()
    replacements = patch_file(args.library, args.old_prefix, args.new_prefix)
    print(f"{args.library}: replaced={replacements}, hash verification=OK")


if __name__ == "__main__":
    main()