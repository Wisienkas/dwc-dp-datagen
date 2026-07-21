package org.gbif.datagen.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared, thread-safe {@link ObjectMapper} plus the two operations datagen needs: read a JSON
 * object into a plain {@code Map<String, Object>}, and pretty-print one back out.
 *
 * <p>{@code ObjectMapper} is expensive to build and is thread-safe once configured, so it's
 * created once here rather than per call site — dependencies shared, not re-instantiated.
 *
 * <p>Values from {@link #readObject} come back as plain JDK types: {@link java.util.LinkedHashMap}
 * for objects (Jackson's default untyped binding uses {@code LinkedHashMap}, preserving key
 * order) and {@link java.util.ArrayList} for arrays. Numbers keep their original lexical type —
 * a constraint written as {@code "minimum": 0} round-trips as an integer, not {@code 0.0} — which
 * is actually an improvement over the hand-rolled parser this replaces; that one always produced
 * {@code Double} and needed a workaround to avoid a spurious ".0" on whole numbers when writing
 * the descriptor back out. That workaround is gone; it's no longer needed.
 */
public final class JsonSupport {

  public static final ObjectMapper MAPPER = new ObjectMapper()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(SerializationFeature.INDENT_OUTPUT);

  private JsonSupport() {
  }

  public static Map<String, Object> readObject(InputStream in) {
    try {
      return MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new UncheckedIOException("Failed parsing JSON", e);
    }
  }

  public static Map<String, Object> readObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new UncheckedIOException("Failed parsing JSON", e);
    }
  }

  public static String writePretty(Object value) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing JSON", e);
    }
  }
}
