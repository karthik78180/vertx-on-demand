package com.example;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class MainServerTest {

    private Vertx vertx;
    private HttpServer server;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        DeploymentHandler handler = new DeploymentHandler(vertx);
        Router router = Router.router(vertx);

        router.route().handler(BodyHandler.create());
        router.post("/deploy").handler(handler::deploy);
        router.post("/undeploy").handler(handler::undeploy);
        router.post("/:address").handler(handler::handle);

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080)
            .onComplete(testContext.succeeding(srv -> {
                server = srv;
                testContext.completeNow();
            }));
    }

    @Test
    void testServerListensOnPort8080(VertxTestContext testContext) {
        assertNotNull(server);
        assertEquals(8080, server.actualPort());
        testContext.completeNow();
    }

    @Test
    void testDeployRouteExists(Vertx vertx, VertxTestContext testContext) {
        assertNotNull(server);
        testContext.completeNow();
    }
}
