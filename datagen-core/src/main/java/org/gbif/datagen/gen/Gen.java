package org.gbif.datagen.gen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gbif.datagen.engine.Seeds;

/** Static factory for generator primitives. Intended for static import in recipes. */
public final class Gen {

  private Gen() {
  }

  // ---------------------------------------------------------------- constants and identity

  public static <T> Generator<T> constant(T value) {
    return ctx -> value;
  }

  public static Generator<String> uuid() {
    return ctx -> {
      Random r = ctx.random();
      // Build a v4 UUID from the seeded stream; UUID.randomUUID() would ignore the seed.
      byte[] bytes = new byte[16];
      r.nextBytes(bytes);
      bytes[6] &= 0x0f;
      bytes[6] |= 0x40;
      bytes[8] &= 0x3f;
      bytes[8] |= 0x80;
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++) {
        msb = (msb << 8) | (bytes[i] & 0xff);
      }
      for (int i = 8; i < 16; i++) {
        lsb = (lsb << 8) | (bytes[i] & 0xff);
      }
      return new UUID(msb, lsb).toString();
    };
  }

  /** Ordered integers starting at {@code start} — catalog numbers are 1,2,3, not UUIDs. */
  public static Generator<Long> sequence(long start) {
    return ctx -> start + ctx.rowIndex();
  }

  public static Generator<Long> sequence() {
    return sequence(1);
  }

  // ---------------------------------------------------------------- numeric

  /** Uniform over [min, max]; integer-typed fields get a whole number. */
  public static Generator<Number> between(double min, double max) {
    if (min > max) {
      throw new IllegalArgumentException("between(" + min + ", " + max + "): min exceeds max");
    }
    return ctx -> {
      double v = min + ctx.random().nextDouble() * (max - min);
      boolean asInt = ctx.fieldSchema().map(f -> f.isInteger()).orElse(false);
      return asInt ? (Number) Math.round(v) : (Number) v;
    };
  }

  public static Generator<Number> between(double min, double max, int decimals) {
    Generator<Number> inner = between(min, max);
    double factor = Math.pow(10, decimals);
    return ctx -> Math.round(inner.generate(ctx).doubleValue() * factor) / factor;
  }

  /** Normal distribution, clamped to [min, max] so schema constraints still hold. */
  public static Generator<Number> gaussian(double mean, double stdDev, double min, double max) {
    return ctx -> {
      double v = mean + ctx.random().nextGaussian() * stdDev;
      v = Math.max(min, Math.min(max, v));
      boolean asInt = ctx.fieldSchema().map(f -> f.isInteger()).orElse(false);
      return asInt ? (Number) Math.round(v) : (Number) v;
    };
  }

  public static Generator<Number> gaussian(double mean, double stdDev) {
    return gaussian(mean, stdDev, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
  }

  // ---------------------------------------------------------------- sampling

  /** Uniform draw from a fixed pool, with replacement. */
  public static <T> Generator<T> sample(List<T> pool) {
    if (pool.isEmpty()) {
      throw new IllegalArgumentException("sample() pool is empty");
    }
    List<T> copy = List.copyOf(pool);
    return ctx -> copy.get(ctx.random().nextInt(copy.size()));
  }

  @SafeVarargs
  public static <T> Generator<T> sample(T... pool) {
    return sample(List.of(pool));
  }

  /**
   * Pool materialized once from another generator, then sampled with replacement.
   *
   * <p>The pool is built lazily on first use and cached, from a seed stream independent of the
   * per-cell stream — otherwise pool contents would shift with row index.
   */
  public static <T> Generator<T> sample(Generator<T> source, int poolSize) {
    AtomicReference<List<T>> cache = new AtomicReference<>();
    return ctx -> {
      List<T> pool = cache.get();
      if (pool == null) {
        pool = materialize(source, poolSize, ctx);
        cache.compareAndSet(null, pool);
        pool = cache.get();
      }
      return pool.get(ctx.random().nextInt(pool.size()));
    };
  }

  private static <T> List<T> materialize(Generator<T> source, int poolSize, GenContext ctx) {
    List<T> pool = new ArrayList<>(poolSize);
    for (int i = 0; i < poolSize; i++) {
      long poolSeed = Seeds.salt(Seeds.cell(0, ctx.resource(), ctx.field(), 0), "pool", i);
      pool.add(source.generate(new FixedContext(ctx, poolSeed, i)));
    }
    return List.copyOf(pool);
  }

  /**
   * Draws without replacement, so each row gets a distinct value.
   *
   * <p>Requires {@code pool.size() >= rowCount}; exhaustion fails loudly rather than silently
   * repeating. Implemented as a seeded shuffle indexed by row, which keeps determinism without
   * any mutable draw state.
   */
  public static <T> Generator<T> sampleWithoutReplacement(List<T> pool) {
    if (pool.isEmpty()) {
      throw new IllegalArgumentException("sampleWithoutReplacement() pool is empty");
    }
    List<T> copy = List.copyOf(pool);
    AtomicReference<List<T>> shuffled = new AtomicReference<>();
    return ctx -> {
      List<T> order = shuffled.get();
      if (order == null) {
        List<T> tmp = new ArrayList<>(copy);
        java.util.Collections.shuffle(tmp, new Random(Seeds.salt(ctx.seed(), "swor:" + ctx.resource())));
        shuffled.compareAndSet(null, List.copyOf(tmp));
        order = shuffled.get();
      }
      if (ctx.rowIndex() >= order.size()) {
        throw new IllegalStateException("sampleWithoutReplacement exhausted on " + ctx.resource() + "."
            + ctx.field() + ": pool has " + order.size() + " values but row index " + ctx.rowIndex()
            + " was requested");
      }
      return order.get(ctx.rowIndex());
    };
  }

  // ---------------------------------------------------------------- weighted

  /**
   * Weighted choice. Weights need not sum to 1.
   *
   * <p>Entries are canonicalized by sorting on the key's string form before the cumulative weight
   * array is built. This matters more than it looks: {@code Map.of()} randomizes iteration order
   * per JVM run, so building the array in encounter order would produce different data from the
   * same seed on every run. Sorting also makes two maps with identical content but different
   * insertion order generate identically — determinism keyed on content, not construction.
   */
  public static <T> Generator<T> weighted(Map<T, Double> weights) {
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("weighted() requires at least one entry");
    }
    List<Map.Entry<T, Double>> entries = new ArrayList<>(weights.entrySet());
    entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));

    List<T> values = new ArrayList<>(entries.size());
    double[] cumulative = new double[entries.size()];
    double total = 0;
    for (int i = 0; i < entries.size(); i++) {
      double w = entries.get(i).getValue();
      if (w < 0) {
        throw new IllegalArgumentException("weighted() has negative weight for " + entries.get(i).getKey());
      }
      total += w;
      values.add(entries.get(i).getKey());
      cumulative[i] = total;
    }
    if (total <= 0) {
      throw new IllegalArgumentException("weighted() weights sum to zero");
    }
    double sum = total;
    return ctx -> {
      double r = ctx.random().nextDouble() * sum;
      for (int i = 0; i < cumulative.length; i++) {
        if (r < cumulative[i]) {
          return values.get(i);
        }
      }
      return values.get(values.size() - 1);
    };
  }

  /** Inline weighted choice: {@code weighted("present", .85, "absent", .15)}. */
  public static <T> Generator<T> weighted(Object... alternating) {
    if (alternating.length == 0 || alternating.length % 2 != 0) {
      throw new IllegalArgumentException("weighted(...) needs alternating value/weight pairs");
    }
    Map<T, Double> map = new LinkedHashMap<>();
    for (int i = 0; i < alternating.length; i += 2) {
      @SuppressWarnings("unchecked")
      T value = (T) alternating[i];
      if (!(alternating[i + 1] instanceof Number w)) {
        throw new IllegalArgumentException("weighted(...) expected a numeric weight after " + value);
      }
      map.put(value, w.doubleValue());
    }
    return weighted(map);
  }

  // ---------------------------------------------------------------- correlation

  /** Derives from the partially-built current row: {@code derive(row -> year(row.get("eventDate")))}. */
  public static <T> Generator<T> derive(Function<Row, T> fn) {
    return ctx -> fn.apply(ctx.row());
  }

  /**
   * Like {@link #derive}, but declares which fields it needs first. If any aren't generated yet,
   * this requests a retry via {@link FieldNotReadyException} automatically, instead of the
   * supplied function needing to check {@link Row#isGenerated} itself.
   */
  public static <T> Generator<T> deriveAfter(List<String> requiredFields, Function<Row, T> fn) {
    return new DeriveAfterGenerator<>(requiredFields, fn);
  }

  private static final class DeriveAfterGenerator<T> implements Generator<T>, FieldReference {
    private final List<String> requiredFields;
    private final Function<Row, T> fn;

    DeriveAfterGenerator(List<String> requiredFields, Function<Row, T> fn) {
      this.requiredFields = List.copyOf(requiredFields);
      this.fn = fn;
    }

    @Override
    public List<String> referencedFields() {
      return requiredFields;
    }

    @Override
    public T generate(GenContext ctx) {
      for (String req : requiredFields) {
        if (!ctx.isGenerated(req)) {
          throw new FieldNotReadyException(req);
        }
      }
      return fn.apply(ctx.row());
    }
  }

  /** Copies the same-named field from the anchoring parent row. */
  public static Generator<Object> inherit(String parentResource) {
    return ctx -> ctx.parentRow(parentResource)
        .map(parent -> parent.get(ctx.field()))
        .orElse(null);
  }

  /** Copies a named field from the anchoring parent row. */
  public static Generator<Object> inherit(String parentResource, String parentField) {
    return ctx -> ctx.parentRow(parentResource).map(parent -> parent.get(parentField)).orElse(null);
  }

  /** Full escape hatch. */
  public static <T> Generator<T> lambda(Function<GenContext, T> fn) {
    return fn::apply;
  }

  // ---------------------------------------------------------------- templates

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

  /**
   * Composes a string from other fields on the same row: {@code "{institutionCode}:{catalogNumber}"}.
   * Unresolved placeholders fail loudly rather than emitting a literal brace.
   */
  public static Generator<String> template(String pattern) {
    return new TemplateGenerator(pattern);
  }

  private static final class TemplateGenerator implements Generator<String>, FieldReference {
    private final String pattern;
    private final List<String> referencedFields;

    TemplateGenerator(String pattern) {
      this.pattern = pattern;
      List<String> refs = new ArrayList<>();
      Matcher m = PLACEHOLDER.matcher(pattern);
      while (m.find()) {
        refs.add(m.group(1));
      }
      this.referencedFields = List.copyOf(refs);
    }

    @Override
    public List<String> referencedFields() {
      return referencedFields;
    }

    @Override
    public String generate(GenContext ctx) {
      Matcher m = PLACEHOLDER.matcher(pattern);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        String ref = m.group(1);
        if (!ctx.isGenerated(ref)) {
          throw new FieldNotReadyException(ref);
        }
        Object value = ctx.get(ref);
        if (value == null) {
          throw new IllegalStateException("template on " + ctx.resource() + "." + ctx.field()
                                          + " references '" + ref + "', which generated to null on this row. template()"
                                          + " requires a non-null value — either the referenced field's generator needs to"
                                          + " never emit null (e.g. drop a nullChance() on it), or don't reference it here.");
        }
        m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
      }
      m.appendTail(sb);
      return sb.toString();
    }
  }

  // ---------------------------------------------------------------- lookup

  public static Lookup lookup(String resource, String field) {
    return new Lookup(resource, field, 0);
  }

  /** A reference draw from an already-generated resource's column. */
  public static final class Lookup implements Generator<Object> {
    private final String resource;
    private final String field;
    private final double violateChance;

    Lookup(String resource, String field, double violateChance) {
      this.resource = resource;
      this.field = field;
      this.violateChance = violateChance;
    }

    /** Emits a value guaranteed absent from the target pool, with probability {@code p}. */
    public Lookup violate(double p) {
      if (p < 0 || p > 1) {
        throw new IllegalArgumentException("violate must be in [0,1], got " + p);
      }
      return new Lookup(resource, field, p);
    }

    public String resource() {
      return resource;
    }

    public String field() {
      return field;
    }

    @Override
    public Object generate(GenContext ctx) {
      if (violateChance > 0 && Decorators.roll(ctx, "violate") < violateChance) {
        return "DANGLING-" + UUID.nameUUIDFromBytes(
            String.valueOf(Seeds.salt(ctx.seed(), "dangling")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      List<Object> pool = ctx.pool(resource, field);
      if (pool.isEmpty()) {
        return null;
      }
      return pool.get(ctx.random().nextInt(pool.size()));
    }
  }

  /** A context that overrides seed and row index, used for materializing sample pools. */
  private record FixedContext(GenContext delegate, long seed, int rowIndex) implements GenContext {
    @Override
    public Random random() {
      return new Random(seed);
    }

    @Override
    public String resource() {
      return delegate.resource();
    }

    @Override
    public String field() {
      return delegate.field();
    }

    @Override
    public java.util.Optional<org.gbif.datagen.schema.Field> fieldSchema() {
      return delegate.fieldSchema();
    }

    @Override
    public Object get(String field) {
      return null;
    }

    @Override
    public Row row() {
      return new Row();
    }

    @Override
    public List<Object> pool(String resource, String field) {
      return delegate.pool(resource, field);
    }

    @Override
    public java.util.Optional<Row> parentRow(String resource) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<Row> parentRow() {
      return java.util.Optional.empty();
    }

    @Override
    public boolean isGenerated(String field) {
      return false; // synthetic context for pool materialization; nothing on a real row exists yet
    }
  }
}
