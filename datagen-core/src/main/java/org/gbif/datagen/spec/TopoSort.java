package org.gbif.datagen.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gbif.datagen.schema.ForeignKey;

/**
 * Orders resources so every reference target is generated before anything pointing at it.
 *
 * <p>The graph is the <em>merged</em> one: schema-declared foreign keys (strong and weak, via
 * {@code TableSchema.allForeignKeys()}) plus recipe-declared refs plus {@code per()} anchors.
 * Recipe refs can introduce cycles the schema's strong graph does not have, so cycle detection
 * is mandatory rather than theoretical.
 *
 * <p>A weak FK with no {@code reference} ({@code ForeignKey.hasTarget() == false}) contributes
 * nothing to the graph — there's no target to order against — and is simply skipped.
 *
 * <p>Self-references are excluded from the graph — they are handled within a resource by drawing
 * only from earlier rows, so they never constrain resource ordering.
 */
final class TopoSort {

  private TopoSort() {
  }

  static List<String> order(List<ResourceSpec> specs) {
    Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
    for (ResourceSpec spec : specs) {
      dependsOn.put(spec.name(), new LinkedHashSet<>());
    }

    for (ResourceSpec spec : specs) {
      Set<String> deps = dependsOn.get(spec.name());
      Set<String> selected = new LinkedHashSet<>(spec.fieldNames());

      for (RefSpec ref : spec.refs()) {
        if (!ref.targetResource().equals(spec.name())) {
          deps.add(ref.targetResource());
        }
      }
      for (ForeignKey fk : spec.schema().allForeignKeys()) {
        if (fk.hasTarget()
            && dependsOn.containsKey(fk.refResource())
            && !fk.refResource().equals(spec.name())
            && selected.contains(fk.field())) {
          deps.add(fk.refResource());
        }
      }
      spec.anchor().ifPresent(a -> deps.add(a.parentResource()));
    }

    List<String> order = new ArrayList<>();
    Set<String> visited = new LinkedHashSet<>();
    Set<String> visiting = new LinkedHashSet<>();
    for (String name : dependsOn.keySet()) {
      visit(name, dependsOn, visited, visiting, order, new ArrayList<>());
    }
    return List.copyOf(order);
  }

  private static void visit(String node, Map<String, Set<String>> dependsOn, Set<String> visited,
                            Set<String> visiting, List<String> order, List<String> path) {
    if (visited.contains(node)) {
      return;
    }
    if (!visiting.add(node)) {
      List<String> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
      cycle.add(node);
      throw new IllegalStateException("Reference cycle across resources: " + String.join(" -> ", cycle)
                                      + ". Schema foreign keys and recipe refs together form a cycle; break it by removing a ref"
                                      + " or pointing one at a different table.");
    }
    path.add(node);
    for (String dep : dependsOn.getOrDefault(node, Set.of())) {
      visit(dep, dependsOn, visited, visiting, order, path);
    }
    path.remove(path.size() - 1);
    visiting.remove(node);
    visited.add(node);
    order.add(node);
  }
}
