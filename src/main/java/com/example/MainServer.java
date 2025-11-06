package com.example;

import com.example.observability.MetricsRegistry;
import com.example.observability.OpenTelemetryConfig;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.concurrent.TimeUnit;

public class MainServer {
  public static void main(String[] args) {
    // Initialize OpenTelemetry first
    System.out.println("Initializing OpenTelemetry...");
    OpenTelemetryConfig.initialize();

    // Initialize metrics registry
    System.out.println("Initializing metrics...");
    MetricsRegistry metricsRegistry = MetricsRegistry.initialize();

    // Create Vert.x with blocked thread checker enabled
    VertxOptions options = new VertxOptions()
        // Enable blocked thread checker
        .setBlockedThreadCheckInterval(1000) // Check every 1 second
        .setBlockedThreadCheckIntervalUnit(TimeUnit.MILLISECONDS)
        // Warn if event loop blocked for more than 2 seconds
        .setMaxEventLoopExecuteTime(2)
        .setMaxEventLoopExecuteTimeUnit(TimeUnit.SECONDS)
        // Warn if worker thread blocked for more than 10 seconds
        .setMaxWorkerExecuteTime(10)
        .setMaxWorkerExecuteTimeUnit(TimeUnit.SECONDS)
        // Enable warnings
        .setWarningExceptionTime(5)
        .setWarningExceptionTimeUnit(TimeUnit.SECONDS);

    Vertx vertx = Vertx.vertx(options);
    Router router = Router.router(vertx);
    DeploymentHandler handler = new DeploymentHandler(vertx, metricsRegistry);

    router.route().handler(BodyHandler.create());

    // Health check endpoint
    router.get("/health").handler(ctx -> {
      boolean healthy = metricsRegistry.performHealthCheck();
      ctx.response()
        .setStatusCode(healthy ? 200 : 503)
        .putHeader("content-type", "application/json")
        .end(String.format("{\"status\":\"%s\"," +
            "\"heap_utilization\":%.2f," +
            "\"active_verticles\":%d," +
            "\"blocked_threads\":%d," +
            "\"blocked_event_loops\":%d," +
            "\"using_virtual_threads\":%b}",
          healthy ? "healthy" : "unhealthy",
          metricsRegistry.jvm().getHeapUtilization(),
          metricsRegistry.deployment().getActiveVerticleCount(),
          metricsRegistry.threads().getBlockedThreadCount(),
          metricsRegistry.threads().getBlockedEventLoopCount(),
          metricsRegistry.threads().isUsingVirtualThreads()));
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
        System.out.println("🧵 Thread monitoring enabled:");
        System.out.println("   - Event loop blocked check: every 1s (warn if > 2s)");
        System.out.println("   - Worker thread blocked check: warn if > 10s");
        System.out.println("   - Virtual thread detection: enabled (Java 21+)");
      } else {
        System.err.println("❌ Failed to start server: " + ar.cause());
      }
    });
  }
}
