package com.example.validator;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import io.vertx.core.MultiMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestValidatorTest {

    @Mock
    private RoutingContext context;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @Mock
    private io.vertx.ext.web.RequestBody requestBody;

    private MultiMap headers;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        headers = MultiMap.caseInsensitiveMultiMap();
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(request.headers()).thenReturn(headers);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
    }

    @Test
    void whenNotProtobufRequest_shouldReturnTrue() {
        headers.add("Content-Type", "application/json");

        boolean result = RequestValidator.validateProtobufRequest(context);

        assertTrue(result);
        verify(response, never()).setStatusCode(anyInt());
    }

    @Test
    void whenProtobufRequestWithNoBody_shouldReturnFalse() {
        headers.add("Content-Type", "application/octet-stream");
        when(requestBody.buffer()).thenReturn(null);

        boolean result = RequestValidator.validateProtobufRequest(context);

        assertFalse(result);
        verify(response).setStatusCode(400);
        verify(response).end((Buffer) argThat(json ->
            new JsonObject((String) json).getString("error").equals("Missing protobuf payload")
        ));
    }

    @Test
    void whenProtobufRequestExceedsMaxSize_shouldReturnFalse() {
        headers.add("Content-Type", "application/octet-stream");
        Buffer largeBuffer = Buffer.buffer(new byte[2 * 1024 * 1024]); // 2MB
        when(requestBody.buffer()).thenReturn(largeBuffer);

        boolean result = RequestValidator.validateProtobufRequest(context);

        assertFalse(result);
        verify(response).setStatusCode(413);
        verify(response).end((String) argThat(json -> {
            JsonObject error = new JsonObject((String) json);
            return error.getString("error").equals("Protobuf payload too large") &&
                   error.getLong("maxSize") == 1024 * 1024 &&
                   error.getLong("actualSize") == largeBuffer.length();
        }));
    }

    @Test
    void whenValidProtobufRequest_shouldReturnTrue() {
        headers.add("Content-Type", "application/octet-stream");
        Buffer validBuffer = Buffer.buffer(new byte[100 * 1024]); // 100KB
        when(requestBody.buffer()).thenReturn(validBuffer);

        boolean result = RequestValidator.validateProtobufRequest(context);

        assertTrue(result);
        verify(response, never()).setStatusCode(anyInt());
    }
}
