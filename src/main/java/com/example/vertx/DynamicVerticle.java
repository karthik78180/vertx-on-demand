package com.example.vertx;

import io.vertx.core.Promise;

public interface DynamicVerticle {
    /**
     * Called when the verticle is first initialized.
     * Use this to set up any resources needed by the verticle.
     * @param startPromise Promise to complete when initialization is done
     */
    void start(Promise<Void> startPromise);

    /**
     * Called to handle incoming requests.
     * @param request The incoming request data
     * @param responsePromise Promise to complete with the response
     */
    void handle(String request, Promise<String> responsePromise);

    /**
     * Called when the verticle is being stopped.
     * Use this to clean up any resources.
     * @param stopPromise Promise to complete when cleanup is done
     */
    void stop(Promise<Void> stopPromise);
}
