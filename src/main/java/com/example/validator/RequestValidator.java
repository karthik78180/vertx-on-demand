package com.example.validator;

import io.vertx.ext.web.RoutingContext;
import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;

public class RequestValidator {
    /**
     * Maximum allowed size for protobuf requests in bytes (1MB)
     */
    private static final long MAX_PROTOBUF_SIZE = 1024 * 1024;

    /**
     * Validates the request payload size for protobuf requests by checking actual buffer size
     * @param context The routing context containing the request
     * @return true if the request is valid, false otherwise
     */
    public static boolean validateProtobufRequest(RoutingContext context) {
        if (!context.request().headers().get("Content-Type").contains("octet-stream")) {
            return true; // Not a protobuf request, skip validation
        }

        Buffer requestBody = context.body().buffer();
        if (requestBody == null) {
            context.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Missing protobuf payload")
                    .encode());
            return false;
        }

        long actualSize = requestBody.length();
        if (actualSize > MAX_PROTOBUF_SIZE) {
            context.response()
                .setStatusCode(413)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Protobuf payload too large")
                    .put("maxSize", MAX_PROTOBUF_SIZE)
                    .put("actualSize", actualSize)
                    .encode());
            return false;
        }

        return true;
    }
}
