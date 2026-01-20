package com.example;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class MainServer {
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    Router router = Router.router(vertx);

    // Create global configuration
    JsonObject globalConfig = new JsonObject()
        .put("app_name", "Vert.x On-Demand Server")
        .put("greeting_prefix", "Hello");

    DeploymentHandler handler = new DeploymentHandler(vertx, globalConfig);

    router.route().handler(BodyHandler.create());
    router.post("/deploy").handler(handler::deploy);
    router.post("/undeploy").handler(handler::undeploy);
        router.post("/:address").handler(handler::handle);

    HttpServer server = vertx.createHttpServer().requestHandler(router);
    server.listen(8080).onComplete(ar -> {
      if (ar.succeeded()) {
        System.out.println("✅ Server started on http://localhost:8080");
      } else {
        System.err.println("❌ Failed to start server: " + ar.cause());
      }
    });
  }
}
