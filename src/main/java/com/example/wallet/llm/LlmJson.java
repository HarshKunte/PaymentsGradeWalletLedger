package com.example.wallet.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class LlmJson {
    private static final ObjectMapper OM = new ObjectMapper();
    private LlmJson() {}

    /** Parses a JSON object from model output (tolerates ``` fences). Returns null if it is not a JSON object. */
    public static JsonNode parseObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.trim();
        }
        try {
            JsonNode n = OM.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
