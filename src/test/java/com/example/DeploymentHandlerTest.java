package com.example;

import com.example.api.VerticleLifecycle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RequestBody;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeploymentHandlerTest {

    @Mock
    private Vertx vertx;

    @Mock
    private RoutingContext context;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @Mock
    private RequestBody requestBody;

    @Mock
    private VerticleLifecycle<?> mockVerticle;

    private DeploymentHandler handler;
    private MultiMap headers;

    @BeforeEach
    void setUp() {
        handler = new DeploymentHandler(vertx);
        headers = MultiMap.caseInsensitiveMultiMap();
        
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(request.headers()).thenReturn(headers);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(context.pathParam("address")).thenReturn("test.v1");
    }

    @Test
    void whenLargeProtobufRequest_shouldRejectRequest() {
        // Setup
        headers.add("Content-Type", "application/octet-stream");
        Buffer largeBuffer = Buffer.buffer(new byte[2 * 1024 * 1024]); // 2MB
        when(requestBody.buffer()).thenReturn(largeBuffer);
        
        // Add mock verticle to deployed map through reflection
        try {
            var deployedField = DeploymentHandler.class.getDeclaredField("deployed");
            deployedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var deployed = (java.util.Map<String, VerticleLifecycle<?>>) deployedField.get(handler);
            deployed.put("test.v1", mockVerticle);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Execute
        handler.handle(context);

        // Verify
        verify(response).setStatusCode(413);
        verify(mockVerticle, never()).handle(any());
    }

    @Test
    void whenValidProtobufRequest_shouldProcessRequest() {
        // Setup
        headers.add("Content-Type", "application/octet-stream");
        Buffer validBuffer = Buffer.buffer(new byte[100 * 1024]); // 100KB
        when(requestBody.buffer()).thenReturn(validBuffer);
        
        // Add mock verticle to deployed map
        try {
            var deployedField = DeploymentHandler.class.getDeclaredField("deployed");
            deployedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var deployed = (java.util.Map<String, VerticleLifecycle<?>>) deployedField.get(handler);
            deployed.put("test.v1", mockVerticle);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Execute
        handler.handle(context);

        // Verify
        verify(mockVerticle).handle(context);
        verify(response, never()).setStatusCode(413);
    }

    @Test
    void whenNonProtobufRequest_shouldProcessNormally() {
        // Setup
        headers.add("Content-Type", "application/json");
        when(requestBody.buffer()).thenReturn(Buffer.buffer("{}"));
        
        // Add mock verticle to deployed map
        try {
            var deployedField = DeploymentHandler.class.getDeclaredField("deployed");
            deployedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var deployed = (java.util.Map<String, VerticleLifecycle<?>>) deployedField.get(handler);
            deployed.put("test.v1", mockVerticle);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Execute
        handler.handle(context);

        // Verify
        verify(mockVerticle).handle(context);
        verify(response, never()).setStatusCode(anyInt());
    }
}
