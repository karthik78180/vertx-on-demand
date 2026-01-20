package com.example.api;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Optional interface for centralized initialization of shared resources across verticles.
 *
 * One instance of the implementation class should be loaded and initialized ONCE
 * before any verticles are deployed. This allows you to:
 * - Initialize connection pools
 * - Load configurations
 * - Set up shared caches
 * - Initialize logging
 * - Any other global setup needed by verticles
 *
 * Specify the initClass in your verticle configuration:
 * <pre>
 * {
 *   "verticleClass": "com.example.verticles.MyVerticle",
 *   "initClass": "com.example.init.CommonInit",
 *   "address": "/my-endpoint.v1"
 * }
 * </pre>
 *
 * Usage in implementation:
 * <pre>
 * public class CommonInit implements VerticleInitializable {
 *     private static DatabasePool dbPool;
 *
 *     @Override
 *     public void initialize(Vertx vertx, JsonObject config) throws Exception {
 *         // Initialize shared resources ONCE
 *         String dbUrl = config.getString("db_url");
 *         dbPool = new DatabasePool(dbUrl);
 *         dbPool.connect();
 *     }
 *
 *     @Override
 *     public void shutdown() throws Exception {
 *         // Cleanup when undeployed
 *         if (dbPool != null) {
 *             dbPool.disconnect();
 *         }
 *     }
 *
 *     // Provide static access to shared resources
 *     public static DatabasePool getDbPool() {
 *         return dbPool;
 *     }
 * }
 * </pre>
 *
 * Then in your verticles:
 * <pre>
 * public class MyVerticle implements VerticleLifecycle<JsonObject> {
 *     @Override
 *     public void handle(RoutingContext context) {
 *         // Use shared resource initialized in CommonInit
 *         CommonInit.getDbPool().query(...)
 *             .onSuccess(result -> context.response().end(result.toJson()))
 *             .onFailure(err -> context.response().setStatusCode(500).end(err.getMessage()));
 *     }
 * }
 * </pre>
 */
public interface VerticleInitializable {

  /**
   * Initialize shared resources.
   * Called ONCE per repository deployment, before any verticles are instantiated.
   *
   * @param vertx The Vert.x instance
   * @param config The global configuration for this repository
   * @throws Exception if initialization fails (deployment will fail)
   */
  void initialize(Vertx vertx, JsonObject config) throws Exception;

  /**
   * Shutdown and cleanup shared resources.
   * Called during undeployment.
   *
   * @throws Exception if shutdown fails (logged but doesn't prevent undeployment)
   */
  void shutdown() throws Exception;
}
