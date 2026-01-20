package com.example.service;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Core DI container for providing common dependencies to verticles.
 *
 * This service provides:
 * - Vertx instance
 * - Global configuration
 *
 * Each verticle is responsible for its own initialization and resource management.
 * Use this service to access shared Vertx and configuration resources only.
 */
public class InitializationService {

    private final Vertx vertx;
    private final JsonObject globalConfig;

    public InitializationService(Vertx vertx, JsonObject globalConfig) {
        this.vertx = vertx;
        this.globalConfig = globalConfig != null ? globalConfig : new JsonObject();
    }

    /**
     * Get the Vertx instance.
     */
    public Vertx getVertx() {
        return vertx;
    }

    /**
     * Get the global configuration.
     */
    public JsonObject getGlobalConfig() {
        return globalConfig;
    }
}
