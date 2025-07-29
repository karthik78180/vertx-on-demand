package com.example.api;

import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.json.JsonObject;

/**
 * Interface for verticles that can be dynamically deployed and undeployed.
 * @param <T> The type of request payload this verticle handles
 */
public interface VerticleLifecycle<T> {
  /**
   * Called when the verticle is deployed
   * @param vertx The Vert.x instance
   * @param config The verticle configuration
   */
  void start(Vertx vertx, JsonObject config);
  
  /**
   * Handle an incoming request
   * @param context The routing context for the request
   */
  void handle(RoutingContext context);
  
  /**
   * Called when the verticle is undeployed
   */
  void stop();
}
