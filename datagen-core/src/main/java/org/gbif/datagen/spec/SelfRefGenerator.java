package org.gbif.datagen.spec;

import java.util.List;
import java.util.Random;

import org.gbif.datagen.engine.Seeds;
import org.gbif.datagen.gen.GenContext;
import org.gbif.datagen.gen.Generator;

/**
 * Generates a self-reference by drawing only from rows already generated.
 *
 * <p>Row {@code i} may only point at rows {@code 0..i-1}, so acyclicity is a structural property
 * rather than something the recipe has to be careful about. Roots fall out for free — row 0 has
 * nothing to point at — and {@code roots(fraction)} only tunes the shape of the resulting forest.
 */
final class SelfRefGenerator implements Generator<Object> {

  private final String resource;
  private final String targetField;
  private final double rootFraction;

  SelfRefGenerator(String resource, String targetField, double rootFraction) {
    this.resource = resource;
    this.targetField = targetField;
    this.rootFraction = rootFraction;
  }

  @Override
  public Object generate(GenContext ctx) {
    if (ctx.rowIndex() == 0) {
      return null;
    }
    if (new Random(Seeds.salt(ctx.seed(), "selfRefRoot")).nextDouble() < rootFraction) {
      return null;
    }
    List<Object> earlier = ctx.pool(resource, targetField);
    if (earlier.isEmpty()) {
      return null;
    }
    int bound = Math.min(earlier.size(), ctx.rowIndex());
    if (bound <= 0) {
      return null;
    }
    return earlier.get(ctx.random().nextInt(bound));
  }
}
