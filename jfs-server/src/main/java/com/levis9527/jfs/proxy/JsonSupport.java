package com.levis9527.jfs.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.levis9527.jfs.meta.MetaTypes.FileMeta;
import com.levis9527.jfs.meta.MetaTypes.UploadResult;

/**
 * JSON encoding for HTTP. Snowflake keys are serialized as strings so browsers
 * do not lose precision past {@code Number.MAX_SAFE_INTEGER}.
 */
final class JsonSupport {
    private JsonSupport() {
    }

    static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(FileMeta.class, (JsonSerializer<FileMeta>) (src, type, ctx) -> {
                JsonObject o = new JsonObject();
                o.addProperty("bucket", src.bucket);
                o.addProperty("filename", src.filename);
                o.addProperty("mime", src.mime);
                o.addProperty("key", String.valueOf(src.key));
                o.addProperty("cookie", src.cookie);
                o.addProperty("vid", src.vid);
                o.addProperty("size", src.size);
                o.addProperty("created", src.created);
                o.addProperty("deleted", src.deleted);
                o.addProperty("auth", src.auth);
                return o;
            })
            .registerTypeAdapter(UploadResult.class, (JsonSerializer<UploadResult>) (src, type, ctx) -> {
                JsonObject o = new JsonObject();
                o.addProperty("bucket", src.bucket);
                o.addProperty("filename", src.filename);
                o.addProperty("key", String.valueOf(src.key));
                o.addProperty("cookie", src.cookie);
                o.addProperty("vid", src.vid);
                o.addProperty("size", src.size);
                o.addProperty("url", src.url);
                o.addProperty("auth", src.auth);
                return o;
            })
            .create();
}
