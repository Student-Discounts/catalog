package org.studentdiscounts.catalog.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

public final class SubmissionPayloadHash {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private SubmissionPayloadHash() {}

  public static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }

  public static JsonNode normalize(JsonNode payload) {
    return normalizeValue(payload, false);
  }

  private static JsonNode normalizeValue(JsonNode value, boolean offer) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return NullNode.getInstance();
    }
    if (value.isObject()) return normalizeObject(value, offer);
    if (value.isArray()) return normalizeArray(value, false);
    if (value.isString()) return NODES.stringNode(value.asText().strip());
    if (value.isIntegralNumber()) return NODES.numberNode(value.bigIntegerValue());
    if (value.isNumber()) return NODES.numberNode(value.decimalValue().stripTrailingZeros());
    return value.deepCopy();
  }

  private static ObjectNode normalizeObject(JsonNode value, boolean offer) {
    ObjectNode result = NODES.objectNode();
    TreeSet<String> names = new TreeSet<>();
    value.properties().forEach(entry -> names.add(entry.getKey()));
    if (offer) names.add("discountType");
    for (String name : names) {
      JsonNode raw = value.get(name);
      JsonNode child;
      if (offer && name.equals("discountType") && blank(raw)) {
        child = NODES.stringNode("OTHER");
      } else if (name.equals("offers") && raw != null && raw.isArray()) {
        child = normalizeArray(raw, true);
      } else {
        child = normalizeValue(raw, false);
      }
      if (!child.isNull() && !(child.isString() && child.asText().isEmpty())) {
        result.set(name, child);
      }
    }
    return result;
  }

  private static boolean blank(JsonNode value) {
    return value == null || value.isNull() || (value.isString() && value.asText().isBlank());
  }

  private static ArrayNode normalizeArray(JsonNode value, boolean offers) {
    ArrayNode result = NODES.arrayNode();
    for (JsonNode child : value) {
      result.add(normalizeValue(child, offers));
    }
    return result;
  }

  public static String canonical(JsonNode value) {
    StringBuilder output = new StringBuilder();
    appendCanonical(normalize(value), output);
    return output.toString();
  }

  private static void appendCanonical(JsonNode value, StringBuilder output) {
    if (value.isObject()) {
      appendCanonicalObject(value, output);
    } else if (value.isArray()) {
      appendCanonicalArray(value, output);
    } else if (value.isNumber()) {
      output.append(value.decimalValue().stripTrailingZeros().toPlainString());
    } else {
      output.append(value.toString());
    }
  }

  private static void appendCanonicalObject(JsonNode value, StringBuilder output) {
    output.append('{');
    boolean first = true;
    for (Map.Entry<String, JsonNode> entry : value.properties()) {
      if (!first) output.append(',');
      first = false;
      output.append(MAPPER.writeValueAsString(entry.getKey())).append(':');
      appendCanonical(entry.getValue(), output);
    }
    output.append('}');
  }

  private static void appendCanonicalArray(JsonNode value, StringBuilder output) {
    output.append('[');
    boolean first = true;
    for (JsonNode child : value) {
      if (!first) output.append(',');
      first = false;
      appendCanonical(child, output);
    }
    output.append(']');
  }

  public static String hash(JsonNode payload) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical(payload).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
