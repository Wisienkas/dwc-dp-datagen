package org.gbif.datagen.gen;

import java.util.Random;

/**
 * Distributions for relation cardinality — how many children a parent row gets.
 *
 * <p>This is a property of the relation, not of any field, and it is where fixture realism lives.
 * Uniform fan-out gives every event ~8 occurrences; real data is heavy-tailed, with most events
 * holding 1-2 records and a few holding hundreds. That difference decides whether a fixture
 * actually exercises a join or merely touches it.
 */
@FunctionalInterface
public interface Dist {

  int sample(Random random);

  /** Every parent gets exactly {@code n} children. */
  static Dist exactly(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("exactly(" + n + "): must be non-negative");
    }
    return r -> n;
  }

  /** Uniform over [min, max] inclusive. */
  static Dist uniform(int min, int max) {
    if (min < 0 || min > max) {
      throw new IllegalArgumentException("uniform(" + min + ", " + max + "): invalid range");
    }
    return r -> min + r.nextInt(max - min + 1);
  }

  /** Normal, rounded and clamped to [min, max]. */
  static Dist gaussian(double mean, double stdDev, int min, int max) {
    return r -> {
      long v = Math.round(mean + r.nextGaussian() * stdDev);
      return (int) Math.max(min, Math.min(max, v));
    };
  }

  /**
   * Zipf over 1..max with exponent {@code s}: P(k) proportional to 1/k^s.
   *
   * <p>Lower {@code s} is flatter, higher is more skewed; ~1.2 gives a long tail with most mass
   * on small counts. The CDF is precomputed once, so sampling is a binary search.
   */
  static Dist zipf(double s, int max) {
    if (max < 1) {
      throw new IllegalArgumentException("zipf max must be >= 1, got " + max);
    }
    if (s <= 0) {
      throw new IllegalArgumentException("zipf exponent must be > 0, got " + s);
    }
    double[] cumulative = new double[max];
    double total = 0;
    for (int k = 1; k <= max; k++) {
      total += 1.0 / Math.pow(k, s);
      cumulative[k - 1] = total;
    }
    double norm = total;
    return r -> {
      double target = r.nextDouble() * norm;
      int lo = 0;
      int hi = cumulative.length - 1;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (cumulative[mid] < target) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo + 1;
    };
  }

  /** Zipf builder reading as {@code zipf(1.2).max(200)}. */
  static ZipfBuilder zipf(double s) {
    return new ZipfBuilder(s);
  }

  /** Fluent tail of {@link #zipf(double)}. */
  final class ZipfBuilder {
    private final double s;

    ZipfBuilder(double s) {
      this.s = s;
    }

    public Dist max(int max) {
      return zipf(s, max);
    }
  }
}
