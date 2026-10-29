package com.example.wallet.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Serialises response maps (keys are already snake_case, values are primitives/strings). */
public final class Json {
    private static final ObjectMapper OM = new ObjectMapper();
    private Json() {}
    public static String write(Object o) {
        try { return OM.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
