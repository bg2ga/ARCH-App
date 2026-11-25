# Native JNI namespace maintenance

`patch_elf_jni_prefix.py` renames a JNI export prefix in an ELF shared library while preserving:

- the byte length of every affected string;
- the GNU hash of every affected symbol;
- the original native machine code and ELF hash tables.

This is required for prebuilt ARM libraries whose JNI class namespace must match the Java class exactly. Any replacement prefix must be generated and checked so that every resulting symbol has exactly the same GNU hash as its original symbol. The script refuses to write a library if the hash lookup verification fails.

Example:

```bash
python tools/patch_elf_jni_prefix.py \
  app/src/main/jniLibs/arm64-v8a/library.so OLD_PREFIX NEW_PREFIX
```