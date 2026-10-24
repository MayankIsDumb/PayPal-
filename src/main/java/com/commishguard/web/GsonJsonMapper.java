package com.commishguard.web;

import com.commishguard.util.Json;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

/** Binds Javalin's JSON pipeline to the same Gson instance the rest of the app uses. */
public final class GsonJsonMapper implements JsonMapper {

    @Override
    public String toJsonString(Object obj, Type type) {
        return Json.GSON.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return Json.GSON.fromJson(json, targetType);
    }
}
