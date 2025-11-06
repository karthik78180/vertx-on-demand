package com.example.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * HTTP request metrics for tracking request rate, latency, errors, and payload sizes.
 */
public class HttpMetrics {

    private static final AttributeKey<String> ENDPOINT_KEY = AttributeKey.stringKey("endpoint");
    private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
    private static final AttributeKey<String> STATUS_CODE_KEY = AttributeKey.stringKey("status_code");
    private static final AttributeKey<String> CONTENT_TYPE_KEY = AttributeKey.stringKey("content_type");

    // Metrics
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDuration;
    private final LongUpDownCounter activeRequests;
    private final DoubleHistogram requestSize;
    private final DoubleHistogram responseSize;
    private final LongCounter validationFailures;

    public HttpMetrics(Meter meter) {
        // Request counter
        requestsTotal = meter.counterBuilder("http.server.requests.total")
            .setDescription("Total number of HTTP requests")
            .setUnit("requests")
            .build();

        // Request duration histogram
        requestDuration = meter.histogramBuilder("http.server.duration")
            .setDescription("HTTP request duration")
            .setUnit("ms")
            .build();

        // Active requests gauge
        activeRequests = meter.upDownCounterBuilder("http.server.active_requests")
            .setDescription("Number of active HTTP requests")
            .setUnit("requests")
            .build();

        // Request size histogram
        requestSize = meter.histogramBuilder("http.server.request.size")
            .setDescription("HTTP request body size")
            .setUnit("bytes")
            .build();

        // Response size histogram
        responseSize = meter.histogramBuilder("http.server.response.size")
            .setDescription("HTTP response body size")
            .setUnit("bytes")
            .build();

        // Validation failures counter
        validationFailures = meter.counterBuilder("request.validation.failures.total")
            .setDescription("Total request validation failures")
            .setUnit("failures")
            .build();

        System.out.println("[HttpMetrics] Initialized HTTP metrics");
    }

    /**
     * Record the start of an HTTP request (increments active requests).
     */
    public void recordRequestStart(String endpoint, String method) {
        activeRequests.add(1, Attributes.of(
            ENDPOINT_KEY, endpoint,
            METHOD_KEY, method
        ));
    }

    /**
     * Record the completion of an HTTP request.
     *
     * @param endpoint HTTP endpoint path
     * @param method HTTP method (GET, POST, etc.)
     * @param statusCode HTTP response status code
     * @param durationMs request duration in milliseconds
     */
    public void recordRequest(String endpoint, String method, int statusCode, long durationMs) {
        recordRequest(endpoint, method, statusCode, durationMs, null);
    }

    /**
     * Record the completion of an HTTP request with content type.
     *
     * @param endpoint HTTP endpoint path
     * @param method HTTP method
     * @param statusCode HTTP response status code
     * @param durationMs request duration in milliseconds
     * @param contentType content type (nullable)
     */
    public void recordRequest(String endpoint, String method, int statusCode, long durationMs, String contentType) {
        Attributes.Builder attributesBuilder = Attributes.builder()
            .put(ENDPOINT_KEY, endpoint)
            .put(METHOD_KEY, method)
            .put(STATUS_CODE_KEY, String.valueOf(statusCode));

        if (contentType != null) {
            attributesBuilder.put(CONTENT_TYPE_KEY, contentType);
        }

        Attributes attributes = attributesBuilder.build();

        // Increment request counter
        requestsTotal.add(1, attributes);

        // Record duration
        requestDuration.record(durationMs, attributes);

        // Decrement active requests
        activeRequests.add(-1, Attributes.of(
            ENDPOINT_KEY, endpoint,
            METHOD_KEY, method
        ));
    }

    /**
     * Record request body size.
     *
     * @param endpoint HTTP endpoint
     * @param sizeBytes request body size in bytes
     * @param contentType content type
     */
    public void recordRequestSize(String endpoint, long sizeBytes, String contentType) {
        requestSize.record(sizeBytes, Attributes.of(
            ENDPOINT_KEY, endpoint,
            CONTENT_TYPE_KEY, contentType
        ));
    }

    /**
     * Record response body size.
     *
     * @param endpoint HTTP endpoint
     * @param sizeBytes response body size in bytes
     */
    public void recordResponseSize(String endpoint, long sizeBytes) {
        responseSize.record(sizeBytes, Attributes.of(
            ENDPOINT_KEY, endpoint
        ));
    }

    /**
     * Record a validation failure.
     *
     * @param endpoint HTTP endpoint
     * @param reason failure reason (e.g., "payload_too_large", "missing_body")
     */
    public void recordValidationFailure(String endpoint, String reason) {
        validationFailures.add(1, Attributes.of(
            ENDPOINT_KEY, endpoint,
            AttributeKey.stringKey("reason"), reason
        ));
    }

    /**
     * Helper to normalize endpoint paths with dynamic segments.
     * E.g., "/greet" -> "/greet", "/unknown" -> "/:address"
     */
    public static String normalizeEndpoint(String path) {
        if (path == null) {
            return "unknown";
        }

        // Known endpoints
        if (path.equals("/deploy") || path.equals("/undeploy") || path.equals("/health") || path.equals("/metrics")) {
            return path;
        }

        // Dynamic verticle address
        if (path.startsWith("/")) {
            return "/:address";
        }

        return path;
    }

    /**
     * Helper to determine content type from header.
     */
    public static String getContentType(String contentTypeHeader) {
        if (contentTypeHeader == null) {
            return "none";
        }

        if (contentTypeHeader.contains("application/json")) {
            return "application/json";
        } else if (contentTypeHeader.contains("application/octet-stream")) {
            return "application/octet-stream";
        } else {
            return "other";
        }
    }
}
