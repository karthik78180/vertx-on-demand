package com.example;

import com.example.api.VerticleLifecycle;
import com.example.validator.RequestValidator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;

public class DeploymentHandler {
    private final Vertx vertx;
    private final Map<String, VerticleLifecycle<?>> deployed = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();

    public DeploymentHandler(Vertx vertx) {
        this.vertx = vertx;
    }  public void deploy(RoutingContext ctx) {
    try {
            JsonObject body = ctx.body().asJsonObject();
            String repo = body.getString("repo");
            File configDir = new File("../" + repo + "/config");

            if (!configDir.exists() || !configDir.isDirectory()) {
                ctx.response().setStatusCode(400).end("Config folder not found for repo: " + repo);
                return;
      }

            File jarFile = new File("../" + repo + "/build/libs/" + repo + "-1.0.0-all.jar");
            if (!jarFile.exists()) {
                ctx.response().setStatusCode(400).end("Jar not found: " + jarFile.getAbsolutePath());
                return;
            }

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

                ctx.response().end("✅ Deployed verticles from " + repo);
            } catch (Exception e) {
                // If deployment fails, close the classloader
                loader.close();
                classLoaders.remove(repo);
                throw e;
            }
    } catch (Exception e) {
      e.printStackTrace();
            ctx.response().setStatusCode(500).end("Failed to deploy: " + e.getMessage());
    }
  }

  public void undeploy(RoutingContext ctx) {
        try {
            deployed.values().forEach(VerticleLifecycle::stop);
            deployed.clear();
            
            // Close all classloaders
            for (URLClassLoader loader : classLoaders.values()) {
                try {
                    loader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            classLoaders.clear();
            
            ctx.response().end("✅ Undeployed all verticles");
        } catch (Exception e) {
            ctx.response().setStatusCode(500).end("Failed to undeploy: " + e.getMessage());
        }
    }

  public void handle(RoutingContext ctx) {
    String address = ctx.pathParam("address");
    VerticleLifecycle<?> verticle = deployed.get(address);
    if (verticle == null) {
      ctx.response().setStatusCode(404).end("No verticle deployed for address: " + address);
      return;
    }
    
    // Validate protobuf request size before processing
    if (RequestValidator.validateProtobufRequest(ctx)) {
      verticle.handle(ctx);
    }
  }
}
