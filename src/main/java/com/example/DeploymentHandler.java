package com.example;

import com.example.api.VerticleLifecycle;
import com.example.observability.HttpMetrics;
import com.example.observability.MetricsRegistry;
import com.example.validator.RequestValidator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.io.File;
import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;

public class DeploymentHandler {
    private final Vertx vertx;
    private final Map<String, VerticleLifecycle<?>> deployed = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final MetricsRegistry metricsRegistry;
    private final ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();

    public DeploymentHandler(Vertx vertx, MetricsRegistry metricsRegistry) {
        this.vertx = vertx;
        this.metricsRegistry = metricsRegistry;
    }  public void deploy(RoutingContext ctx) {
    long startTime = System.currentTimeMillis();
    String repo = null;
    int statusCode = 200;

    try {
            // Record request start
            metricsRegistry.http().recordRequestStart("/deploy", "POST");

            JsonObject body = ctx.body().asJsonObject();
            repo = body.getString("repo");
            File configDir = new File("../" + repo + "/config");

            if (!configDir.exists() || !configDir.isDirectory()) {
                statusCode = 400;
                ctx.response().setStatusCode(statusCode).end("Config folder not found for repo: " + repo);
                metricsRegistry.deployment().recordDeploymentFailure(repo, System.currentTimeMillis() - startTime, "config_not_found");
                return;
      }

            File jarFile = new File("../" + repo + "/build/libs/" + repo + "-1.0.0-all.jar");
            if (!jarFile.exists()) {
                statusCode = 400;
                ctx.response().setStatusCode(statusCode).end("Jar not found: " + jarFile.getAbsolutePath());
                metricsRegistry.deployment().recordDeploymentFailure(repo, System.currentTimeMillis() - startTime, "jar_not_found");
                return;
            }

            // Track classes before loading
            long classesBefore = classLoadingBean.getLoadedClassCount();

            // Close any existing classloader for this repo
            if (classLoaders.containsKey(repo)) {
                try {
                    classLoaders.get(repo).close();
                } catch (IOException e) {
                    // Log error but continue with deployment
                    e.printStackTrace();
                }
            }

            URLClassLoader loader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, this.getClass().getClassLoader());
            classLoaders.put(repo, loader);

            try {
                for (File file : Objects.requireNonNull(configDir.listFiles((dir, name) -> name.endsWith(".json")))) {
                    JsonObject config = new JsonObject(Files.readString(file.toPath()));
                    String verticleClass = config.getString("verticleClass");
                    String address = config.getString("address");

                    Class<?> clazz = loader.loadClass(verticleClass);
                    VerticleLifecycle<?> instance = (VerticleLifecycle<?>) clazz.getDeclaredConstructor().newInstance();
                    instance.start(vertx, config);
                    deployed.put(address, instance);
                }

                // Track classes after loading
                long classesAfter = classLoadingBean.getLoadedClassCount();
                long classesLoaded = classesAfter - classesBefore;

                // Record successful deployment
                long duration = System.currentTimeMillis() - startTime;
                metricsRegistry.deployment().recordDeploymentSuccess(repo, duration, classesLoaded);
                metricsRegistry.classloader().recordClassloaderCreated(repo, classesLoaded);

                ctx.response().end("✅ Deployed verticles from " + repo);
            } catch (Exception e) {
                // If deployment fails, close the classloader
                loader.close();
                classLoaders.remove(repo);
                statusCode = 500;
                metricsRegistry.deployment().recordDeploymentFailure(repo, System.currentTimeMillis() - startTime, "verticle_init_error");
                throw e;
            }
    } catch (Exception e) {
      e.printStackTrace();
            statusCode = 500;
            ctx.response().setStatusCode(statusCode).end("Failed to deploy: " + e.getMessage());
            if (repo != null) {
                metricsRegistry.deployment().recordDeploymentFailure(repo, System.currentTimeMillis() - startTime, "unknown_error");
            }
    } finally {
            // Record HTTP metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsRegistry.http().recordRequest("/deploy", "POST", statusCode, duration, "application/json");
    }
  }

  public void undeploy(RoutingContext ctx) {
        long startTime = System.currentTimeMillis();
        int statusCode = 200;

        try {
            // Record request start
            metricsRegistry.http().recordRequestStart("/undeploy", "POST");

            int verticleCount = deployed.size();
            deployed.values().forEach(VerticleLifecycle::stop);
            deployed.clear();

            // Close all classloaders and record metrics
            for (Map.Entry<String, URLClassLoader> entry : classLoaders.entrySet()) {
                try {
                    entry.getValue().close();
                    // Record classloader removed (estimate classes unloaded)
                    metricsRegistry.classloader().recordClassloaderRemoved(entry.getKey(), 0);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            classLoaders.clear();

            // Record undeployment
            metricsRegistry.deployment().recordUndeployment(verticleCount);

            ctx.response().end("✅ Undeployed all verticles");
        } catch (Exception e) {
            statusCode = 500;
            ctx.response().setStatusCode(statusCode).end("Failed to undeploy: " + e.getMessage());
        } finally {
            // Record HTTP metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsRegistry.http().recordRequest("/undeploy", "POST", statusCode, duration);
        }
    }

  public void handle(RoutingContext ctx) {
    long startTime = System.currentTimeMillis();
    String address = ctx.pathParam("address");
    String endpoint = "/:address";
    int statusCode = 200;

    try {
      // Record request start
      metricsRegistry.http().recordRequestStart(endpoint, "POST");

      VerticleLifecycle<?> verticle = deployed.get(address);
      if (verticle == null) {
        statusCode = 404;
        ctx.response().setStatusCode(statusCode).end("No verticle deployed for address: " + address);
        return;
      }

      // Get content type and request size
      String contentType = HttpMetrics.getContentType(ctx.request().getHeader("Content-Type"));
      if (ctx.body() != null && ctx.body().buffer() != null) {
        metricsRegistry.http().recordRequestSize(endpoint, ctx.body().buffer().length(), contentType);
      }

      // Validate protobuf request size before processing
      if (!RequestValidator.validateProtobufRequest(ctx)) {
        // Validation failed, record metrics
        String failureReason = "unknown";
        if (ctx.response().getStatusCode() == 413) {
          failureReason = "payload_too_large";
        } else if (ctx.response().getStatusCode() == 400) {
          failureReason = "missing_body";
        }
        metricsRegistry.http().recordValidationFailure(endpoint, failureReason);
        statusCode = ctx.response().getStatusCode();
        return;
      }

      // Handle request through verticle
      verticle.handle(ctx);

      // Record response status (assuming success if no exception)
      statusCode = ctx.response().getStatusCode() > 0 ? ctx.response().getStatusCode() : 200;

    } catch (Exception e) {
      statusCode = 500;
      ctx.response().setStatusCode(statusCode).end("Error handling request: " + e.getMessage());
    } finally {
      // Record HTTP metrics
      long duration = System.currentTimeMillis() - startTime;
      String contentType = HttpMetrics.getContentType(ctx.request().getHeader("Content-Type"));
      metricsRegistry.http().recordRequest(endpoint, "POST", statusCode, duration, contentType);
    }
  }
}
