package org.gbif.datagen.gen;

/**
 * Produces one cell value.
 *
 * <p>Single abstract method by design: every capability discussed — lookup, derivation,
 * correlation, constants — is expressible through {@link GenContext} rather than through
 * additional interface shapes. Decorators wrap a {@code Generator<T>} and return a
 * {@code Generator<T>}, which keeps the primitive set small and composition open-ended.
 */
@FunctionalInterface
public interface Generator<T> {

  T generate(GenContext ctx);

  /**
   * Emits null with probability {@code p}, simulating missing data.
   *
   * <p>Drawn from a stream derived from the cell seed but distinct from the wrapped generator's,
   * so toggling a null chance does not reshuffle the underlying values.
   */
  default Generator<T> nullChance(double p) {
    if (p < 0 || p > 1) {
      throw new IllegalArgumentException("nullChance must be in [0,1], got " + p);
    }
    Generator<T> inner = this;
    return ctx -> Decorators.roll(ctx, "nullChance") < p ? null : inner.generate(ctx);
  }

  /** Corrupts the produced value with probability {@code p}, for exercising validators. */
  default Generator<T> corrupt(double p, Corruption... kinds) {
    if (kinds.length == 0) {
      throw new IllegalArgumentException("corrupt() requires at least one Corruption kind");
    }
    Generator<T> inner = this;
    return ctx -> {
      T value = inner.generate(ctx);
      if (value == null || Decorators.roll(ctx, "corrupt") >= p) {
        return value;
      }
      Corruption kind = kinds[Decorators.index(ctx, "corruptKind", kinds.length)];
      @SuppressWarnings("unchecked")
      T corrupted = (T) kind.apply(value, ctx);
      return corrupted;
    };
  }

  /** Maps the output through {@code fn}, keeping the wrapped generator's stream intact. */
  default <R> Generator<R> map(java.util.function.Function<T, R> fn) {
    Generator<T> inner = this;
    return ctx -> {
      T value = inner.generate(ctx);
      return value == null ? null : fn.apply(value);
    };
  }
}
