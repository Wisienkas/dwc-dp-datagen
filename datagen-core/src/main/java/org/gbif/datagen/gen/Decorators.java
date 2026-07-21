package org.gbif.datagen.gen;

import java.util.Random;

import org.gbif.datagen.engine.Seeds;

/**
 * Support for decorator randomness.
 *
 * <p>Each decorator draws from its own stream, derived from the cell seed plus a salt naming the
 * decorator. Without this, adding {@code .nullChance(0.1)} to a field would consume a draw from
 * the shared stream and shift every subsequent value — meaning a cosmetic recipe tweak churns
 * unrelated golden output.
 */
final class Decorators {

  private Decorators() {
  }

  static double roll(GenContext ctx, String salt) {
    return new Random(Seeds.salt(ctx.seed(), salt)).nextDouble();
  }

  static int index(GenContext ctx, String salt, int bound) {
    return new Random(Seeds.salt(ctx.seed(), salt)).nextInt(bound);
  }
}
