package com.example;

import com.example.api.VerticleLifecycle;
import com.example.api.VerticleInitializer;
import com.example.api.VerticleInitializable;
import com.example.di.VerticleModule;
import com.example.service.InitializationService;
import com.example.validator.RequestValidator;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;

/**
 * Handles deployment and undeployment of dynamic verticles with Guice DI integration.
 *
 * This handler:
 * - Creates a singleton Guice Injector configured with VerticleModule
 * - Uses Guice to instantiate verticles with automatic dependency injection
 * - Calls init() on verticles that implement VerticleInitializer before first request
 * - Calls shutdown() on verticles during undeployment
 * - Manages class loaders for dynamically loaded JAR files
 */
public class DeploymentHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);

    private final Vertx vertx;
    private final Injector injector;
    private final InitializationService initializationService;
    private final Map<String, VerticleLifecycle<?>> deployed = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final Map<String, Object> initializables = new ConcurrentHashMap<>();

    /**
     * Constructor that initializes Guice DI.
     *
     * @param vertx The Vert.x instance
     * @param globalConfig Global application configuration
     */
    public DeploymentHandler(Vertx vertx, JsonObject globalConfig) {
        this.vertx = vertx;
        logger.info("🚀 Initializing DeploymentHandler with Guice DI");

        // Create Guice Injector with VerticleModule
        this.injector = Guice.createInjector(new VerticleModule(vertx, globalConfig));

        // Get the singleton InitializationService
        this.initializationService = injector.getInstance(InitializationService.class);
        logger.info("✅ DeploymentHandler initialized. Guice DI is ready for verticle instantiation");
    }

    /**
     * Deploy verticles from a specified repository.
     *
     * This method:
     * 1. Loads the JAR file for the repository
     * 2. Reads configuration files
     * 3. Uses Guice to instantiate verticles with automatic DI
     * 4. Calls start() on each verticle
     * 5. Calls init() on verticles that implement VerticleInitializer
     */
    public void deploy(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            String repo = body.getString("repo");
            File configDir = new File("../" + repo + "/config");

            if (!configDir.exists() || !configDir.isDirectory()) {
                ctx.response().setStatusCode(400).end("❌ Config folder not found for repo: " + repo);
                return;
            }

            File jarFile = new File("../" + repo + "/build/libs/" + repo + "-1.0.0-all.jar");
            if (!jarFile.exists()) {
                ctx.response().setStatusCode(400).end("❌ Jar not found: " + jarFile.getAbsolutePath());
                return;
            }

            // Close any existing classloader for this repo
            if (classLoaders.containsKey(repo)) {
                try {
                    classLoaders.get(repo).close();
                    logger.info("Closed existing classloader for repo: {}", repo);
                } catch (IOException e) {
                    logger.warn("Error closing existing classloader for repo: {}", repo, e);
                }
            }

            URLClassLoader loader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, this.getClass().getClassLoader());
            classLoaders.put(repo, loader);
            logger.info("📦 Created URLClassLoader for repo: {}", repo);

            try {
                File[] configFiles = configDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (configFiles == null || configFiles.length == 0) {
                    ctx.response().setStatusCode(400).end("❌ No configuration files found in " + configDir);
                    return;
                }

                // Load and initialize the initClass ONCE for this repo
                String initKey = repo + "_init";
                if (!initializables.containsKey(initKey)) {
                    // Get initClass from first config (all configs should reference the same initClass)
                    JsonObject firstConfig = new JsonObject(Files.readString(configFiles[0].toPath()));
                    String initClass = firstConfig.getString("initClass");

                    if (initClass != null && !initClass.isEmpty()) {
                        try {
                            logger.info("📥 Loading initClass: {}", initClass);
                            Class<?> initClazz = loader.loadClass(initClass);
                            Object initInstance = instantiateWithGuice(initClazz);

                            if (initInstance instanceof VerticleInitializable) {
                                try {
                                    logger.info("🔧 Initializing shared resources with: {}", initClass);
                                    ((VerticleInitializable) initInstance).initialize(vertx, firstConfig);
                                    initializables.put(initKey, initInstance);
                                    logger.info("✅ Shared initialization complete");
                                } catch (Exception e) {
                                    logger.error("Error initializing with {}", initClass, e);
                                    throw e;
                                }
                            } else {
                                logger.warn("initClass {} does not implement VerticleInitializable", initClass);
                            }
                        } catch (ClassNotFoundException e) {
                            logger.error("initClass not found: {}", initClass, e);
                            throw e;
                        }
                    }
                }

                // Deploy all verticles
                for (File file : configFiles) {
                    JsonObject config = new JsonObject(Files.readString(file.toPath()));
                    String verticleClass = config.getString("verticleClass");
                    String address = config.getString("address");

                    logger.info("📥 Deploying verticle: {} at address: {}", verticleClass, address);

                    Class<?> clazz = loader.loadClass(verticleClass);

                    // Instantiate verticle using Guice DI
                    VerticleLifecycle<?> instance = instantiateWithGuice(clazz);

                    instance.start(vertx, config);

                    // Call init() if verticle implements VerticleInitializer
                    if (instance instanceof VerticleInitializer) {
                        try {
                            logger.info("Calling init() for verticle: {}", verticleClass);
                            ((VerticleInitializer) instance).init();
                            logger.info("✅ Initialization complete for verticle: {}", verticleClass);
                        } catch (Exception e) {
                            logger.error("Error initializing verticle: {}", verticleClass, e);
                            throw e;
                        }
                    }

                    deployed.put(address, instance);
                    logger.info("✅ Verticle deployed: {} -> {}", address, verticleClass);
                }

                ctx.response().end("✅ Deployed verticles from " + repo);
            } catch (Exception e) {
                logger.error("Error deploying verticles from repo: {}", repo, e);
                loader.close();
                classLoaders.remove(repo);
                throw e;
            }
        } catch (Exception e) {
            logger.error("Failed to deploy verticles", e);
            ctx.response().setStatusCode(500).end("❌ Failed to deploy: " + e.getMessage());
        }
    }

    /**
     * Instantiate a verticle using Guice DI.
     * This allows verticles to declare dependencies that Guice will inject.
     */
    private VerticleLifecycle<?> instantiateWithGuice(Class<?> clazz) throws Exception {
        try {
            // Create a child injector for this verticle class
            // This allows the verticle to have its own DI context
            Injector verticleInjector = injector.createChildInjector();
            VerticleLifecycle<?> instance = (VerticleLifecycle<?>) verticleInjector.getInstance(clazz);
            logger.debug("Verticle instantiated with Guice DI: {}", clazz.getName());
            return instance;
        } catch (Exception e) {
            logger.error("Failed to instantiate verticle with Guice: {}", clazz.getName(), e);
            // Fallback to no-arg constructor if Guice fails
            try {
                logger.debug("Attempting no-arg constructor for: {}", clazz.getName());
                return (VerticleLifecycle<?>) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                logger.error("Verticle must either have injectable constructor or no-arg constructor: {}", clazz.getName());
                throw new RuntimeException("Cannot instantiate verticle: " + clazz.getName(), ex);
            }
        }
    }

    /**
     * Undeploy all verticles and cleanup resources.
     * Calls shutdown() on verticles that implement VerticleInitializer.
     * Calls shutdown() on initializables that implement VerticleInitializable.
     */
    public void undeploy(RoutingContext ctx) {
        try {
            logger.info("🛑 Undeploying all verticles");

            // First, shutdown all verticles
            deployed.values().forEach(verticle -> {
                try {
                    // Call shutdown() if verticle implements VerticleInitializer
                    if (verticle instanceof VerticleInitializer) {
                        try {
                            logger.info("Calling shutdown() for verticle: {}", verticle.getClass().getName());
                            ((VerticleInitializer) verticle).shutdown();
                            logger.debug("Shutdown complete for verticle: {}", verticle.getClass().getName());
                        } catch (Exception e) {
                            logger.error("Error during shutdown of verticle: {}", verticle.getClass().getName(), e);
                        }
                    }

                    verticle.stop();
                    logger.debug("Stopped verticle: {}", verticle.getClass().getName());
                } catch (Exception e) {
                    logger.error("Error stopping verticle", e);
                }
            });
            deployed.clear();

            // Then, shutdown all initializables
            initializables.values().forEach(initializable -> {
                try {
                    if (initializable instanceof VerticleInitializable) {
                        try {
                            logger.info("Calling shutdown() for initClass: {}", initializable.getClass().getName());
                            ((VerticleInitializable) initializable).shutdown();
                            logger.debug("Shutdown complete for initClass: {}", initializable.getClass().getName());
                        } catch (Exception e) {
                            logger.error("Error during shutdown of initClass: {}", initializable.getClass().getName(), e);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error shutting down initClass", e);
                }
            });
            initializables.clear();

            // Close all classloaders
            for (Map.Entry<String, URLClassLoader> entry : classLoaders.entrySet()) {
                try {
                    entry.getValue().close();
                    logger.debug("Closed classloader for repo: {}", entry.getKey());
                } catch (IOException e) {
                    logger.warn("Error closing classloader for repo: {}", entry.getKey(), e);
                }
            }
            classLoaders.clear();

            ctx.response().end("✅ Undeployed all verticles");
            logger.info("✅ All verticles undeployed");
        } catch (Exception e) {
            logger.error("Failed to undeploy verticles", e);
            ctx.response().setStatusCode(500).end("❌ Failed to undeploy: " + e.getMessage());
        }
    }

    /**
     * Handle a request for a deployed verticle.
     *
     * @param ctx The routing context
     */
    public void handle(RoutingContext ctx) {
        String address = ctx.pathParam("address");
        VerticleLifecycle<?> verticle = deployed.get(address);
        if (verticle == null) {
            logger.warn("No verticle deployed for address: {}", address);
            ctx.response().setStatusCode(404).end("❌ No verticle deployed for address: " + address);
            return;
        }

        // Validate protobuf request size before processing
        if (RequestValidator.validateProtobufRequest(ctx)) {
            verticle.handle(ctx);
        }
    }

}
