package com.example;

import com.example.api.VerticleLifecycle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RequestBody;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        
        // lenient stubbing so tests that don't exercise every interaction don't fail
        lenient().when(context.request()).thenReturn(request);
        lenient().when(context.response()).thenReturn(response);
        lenient().when(context.body()).thenReturn(requestBody);
        lenient().when(request.headers()).thenReturn(headers);
        // mark as lenient to avoid unnecessary stubbing failures when response isn't used
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
        // ensure chained calls like response.setStatusCode(...).putHeader(...) don't NPE
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(context.pathParam("address")).thenReturn("test.v1");
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
