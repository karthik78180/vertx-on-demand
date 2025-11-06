package com.example;

import com.example.observability.MetricsRegistry;
import com.example.observability.OpenTelemetryConfig;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class MainServer {
  public static void main(String[] args) {
    // Initialize OpenTelemetry first
    System.out.println("Initializing OpenTelemetry...");
    OpenTelemetryConfig.initialize();

    // Initialize metrics registry
    System.out.println("Initializing metrics...");
    MetricsRegistry metricsRegistry = MetricsRegistry.initialize();

    // Create Vert.x and router
    Vertx vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    DeploymentHandler handler = new DeploymentHandler(vertx, metricsRegistry);

    router.route().handler(BodyHandler.create());

    // Health check endpoint
    router.get("/health").handler(ctx -> {
      boolean healthy = metricsRegistry.performHealthCheck();
      ctx.response()
        .setStatusCode(healthy ? 200 : 503)
        .putHeader("content-type", "application/json")
        .end(String.format("{\"status\":\"%s\",\"heap_utilization\":%.2f,\"active_verticles\":%d}",
          healthy ? "healthy" : "unhealthy",
          metricsRegistry.jvm().getHeapUtilization(),
          metricsRegistry.deployment().getActiveVerticleCount()));
    });

    // Application endpoints
    router.post("/deploy").handler(handler::deploy);
    router.post("/undeploy").handler(handler::undeploy);
    router.post("/:address").handler(handler::handle);

    HttpServer server = vertx.createHttpServer().requestHandler(router);
    server.listen(8080).onComplete(ar -> {
      if (ar.succeeded()) {
        System.out.println("✅ Server started on http://localhost:8080");
        System.out.println("📊 Metrics available at http://localhost:9090/metrics");
        System.out.println("💚 Health check at http://localhost:8080/health");
      } else {
        System.err.println("❌ Failed to start server: " + ar.cause());
      }
    });
  }
}
