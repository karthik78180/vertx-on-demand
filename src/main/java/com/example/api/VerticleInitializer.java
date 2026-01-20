package com.example.api;

/**
 * Optional interface for verticles that need initialization and shutdown hooks.
 *
 * Verticles can implement this interface to have control over resource lifecycle:
 * 1. init() - called once before first request, after all dependencies are injected
 * 2. shutdown() - called when verticle is undeployed, for cleanup
 *
 * This is separate from VerticleLifecycle to keep concerns separated:
 * - VerticleLifecycle: Request handling interface
 * - VerticleInitializer: Resource lifecycle management
 *
 * Usage in a verticle:
 * <pre>
 * public class MyVerticle implements VerticleLifecycle, VerticleInitializer {
 *     private DatabaseConnection dbConnection;
 *     private DatabaseConnectionPool pool;
 *
 *     public MyVerticle(DatabaseConnectionPool pool) {
 *         this.pool = pool; // Injected by Guice
 *     }
 *
 *     @Override
 *     public void init() {
 *         // Initialize once before first request
 *         this.dbConnection = pool.getConnection();
 *     }
 *
 *     @Override
 *     public void handle(RoutingContext context) {
 *         // Use initialized resource
 *         dbConnection.query(...);
 *     }
 *
 *     @Override
 *     public void shutdown() {
 *         // Cleanup when verticle is undeployed
 *         dbConnection.close();
 *     }
 * }
 * </pre>
 */
public interface VerticleInitializer {

  /**
   * Called once per verticle deployment, before the first request.
   * All Guice dependencies are already injected at this point.
   *
   * Use this to:
   * - Initialize database connections
   * - Load configuration files
   * - Set up caches
   * - Initialize service clients
   * - Prepare resources needed for handle()
   *
   * @throws Exception if initialization fails (verticle deployment will fail)
   */
  void init() throws Exception;

  /**
   * Called when the verticle is being undeployed.
   * Use this to clean up resources initialized in init().
   * <p>
   * Use this to:
   * - Close database connections
   * - Close HTTP clients
   * - Cancel scheduled tasks
   * - Release other resources
   *
   * @throws Exception if shutdown fails (logged but doesn't prevent undeployment)
   */
  void shutdown() throws Exception;
}
