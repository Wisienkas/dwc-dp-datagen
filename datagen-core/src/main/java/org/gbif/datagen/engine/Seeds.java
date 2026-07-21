package org.gbif.datagen.engine;

import java.nio.charset.StandardCharsets;

/**
 * Per-cell seed derivation.
 *
 * <p>Seeds come from {@code hash(masterSeed, resource, field, rowIndex)} rather than from a
 * shared sequential {@link java.util.Random}. The consequence that matters: adding a field to one
 * resource, or reordering resources, leaves every other cell's value untouched. With a shared
 * stream, any such change reshuffles everything downstream and every golden file churns for no
 * semantic reason.
 *
 * <p>FNV-1a over UTF-8 rather than {@link String#hashCode()} — the latter is specified and stable,
 * but only for {@code String}; hashing structured input via {@code Objects.hash} is not portable.
 */
public final class Seeds {

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  private Seeds() {
  }

  public static long cell(long masterSeed, String resource, String field, int rowIndex) {
    long h = FNV_OFFSET;
    h = mixLong(h, masterSeed);
    h = mixString(h, resource);
    h = mixString(h, field);
    h = mixLong(h, rowIndex);
    return mix64(h);
  }

  /** Derives an independent stream from an existing seed, named by {@code salt}. */
  public static long salt(long seed, String salt) {
    long h = FNV_OFFSET;
    h = mixLong(h, seed);
    h = mixString(h, salt);
    return mix64(h);
  }

  public static long salt(long seed, String salt, int index) {
    long h = FNV_OFFSET;
    h = mixLong(h, seed);
    h = mixString(h, salt);
    h = mixLong(h, index);
    return mix64(h);
  }

  private static long mixString(long h, String s) {
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= FNV_PRIME;
    }
    h ^= 0xff;
    return h * FNV_PRIME;
  }

  private static long mixLong(long h, long v) {
    for (int i = 0; i < 8; i++) {
      h ^= ((v >>> (i * 8)) & 0xff);
      h *= FNV_PRIME;
    }
    return h;
  }

  /** SplitMix64 finalizer — FNV alone has weak avalanche in the high bits. */
  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
