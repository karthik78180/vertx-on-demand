package com.example.di;

import com.example.service.InitializationService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guice module that configures dependency injection for verticles.
 * <p>
 * This module defines how dependencies are created and injected into verticles.
 * The InitializationService is configured as a singleton, ensuring it's created once
 * and shared across all verticles.
 */
public class VerticleModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(VerticleModule.class);
    private final Vertx vertx;
    private final JsonObject globalConfig;

    /**
     * Constructor accepting Vertx instance and global configuration.
     *
     * @param vertx The Vert.x instance
     * @param globalConfig Global configuration for the application
     */
    public VerticleModule(Vertx vertx, JsonObject globalConfig) {
        this.vertx = vertx;
        this.globalConfig = globalConfig != null ? globalConfig : new JsonObject();
        logger.info("📦 VerticleModule initialized with Vertx and global configuration");
    }

    /**
     * Provides the Vertx instance as a singleton dependency.
     */
    @Provides
    @Singleton
    public Vertx provideVertx() {
        logger.debug("Providing Vertx instance");
        return vertx;
    }

    /**
     * Provides the global configuration as a singleton dependency.
     */
    @Provides
    @Singleton
    public JsonObject provideGlobalConfig() {
        logger.debug("Providing global configuration");
        return globalConfig;
    }

    /**
     * Provides the InitializationService as a singleton.
     * This is the most important provider - it ensures that all common initialization
     * happens exactly once, and the same instance is reused across all verticles.
     *
     * @param vertx The Vertx instance (injected)
     * @param config The global configuration (injected)
     * @return A singleton InitializationService instance
     */
    @Provides
    @Singleton
    public InitializationService provideInitializationService(Vertx vertx, JsonObject config) {
        logger.info("🔧 Creating singleton InitializationService");
        return new InitializationService(vertx, config);
    }
}
