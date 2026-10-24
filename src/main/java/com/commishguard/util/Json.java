package com.commishguard.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Central Gson configuration. */
public final class Json {

    /** Compact writer for HTTP responses. */
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** Human-readable writer for the on-disk ledger. */
    public static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static String writePretty(Object value) {
        return PRETTY.toJson(value);
    }

    public static JsonObject parseObject(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }
}
