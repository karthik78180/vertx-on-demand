package com.example.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ResourceAttributes;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry configuration and initialization.
 * Configures Prometheus metrics exporter and OTLP trace exporter.
 */
public class OpenTelemetryConfig {

    private static final String SERVICE_NAME = "vertx-on-demand";
    private static final String SERVICE_VERSION = "1.0.0";
    private static final String INSTRUMENTATION_SCOPE = "com.example.vertx";

    private static volatile OpenTelemetry openTelemetry;
    private static volatile Meter meter;
    private static volatile Tracer tracer;
    private static volatile PrometheusHttpServer prometheusServer;

    /**
     * Initialize OpenTelemetry SDK with Prometheus metrics exporter on port 9090.
     * This should be called once at application startup.
     *
     * @return configured OpenTelemetry instance
     */
    public static synchronized OpenTelemetry initialize() {
        if (openTelemetry != null) {
            return openTelemetry;
        }

        // Create resource attributes (service metadata)
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.builder()
                .put(ResourceAttributes.SERVICE_NAME, SERVICE_NAME)
                .put(ResourceAttributes.SERVICE_VERSION, SERVICE_VERSION)
                .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, getEnvironment())
                .build()));

        // Configure Prometheus HTTP server for metrics (port 9090)
        prometheusServer = PrometheusHttpServer.builder()
            .setHost("0.0.0.0")
            .setPort(9090)
            .build();

        // Optional: OTLP exporter for metrics (to send to external collector)
        // Uncomment if you have an OTLP collector endpoint
        /*
        OtlpGrpcMetricExporter otlpMetricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint("http://localhost:4317")
            .setTimeout(Duration.ofSeconds(10))
            .build();

        MetricReader otlpReader = PeriodicMetricReader.builder(otlpMetricExporter)
            .setInterval(Duration.ofSeconds(60))
            .build();
        */

        // Optional: Logging exporter for development/debugging
        LoggingMetricExporter loggingExporter = LoggingMetricExporter.create();
        MetricReader loggingReader = PeriodicMetricReader.builder(loggingExporter)
            .setInterval(Duration.ofMinutes(1))
            .build();

        // Build MeterProvider with Prometheus exporter
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(prometheusServer)
            .registerMetricReader(loggingReader)  // Comment out in production
            .build();

        // Configure trace provider with OTLP exporter
        // Uncomment if you have Jaeger/Tempo/etc running
        /*
        OtlpGrpcSpanExporter otlpSpanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:4317")
            .setTimeout(Duration.ofSeconds(10))
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(otlpSpanExporter)
                .setScheduleDelay(5, TimeUnit.SECONDS)
                .build())
            .setSampler(Sampler.traceIdRatioBased(0.1))  // Sample 10% of traces
            .build();
        */

        // Build OpenTelemetry SDK
        openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            // .setTracerProvider(tracerProvider)  // Uncomment when using tracing
            .buildAndRegisterGlobal();

        // Create meter for application metrics
        meter = openTelemetry.getMeterProvider()
            .meterBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(SERVICE_VERSION)
            .build();

        // Create tracer for distributed tracing
        tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE, SERVICE_VERSION);

        System.out.println("[OpenTelemetry] Initialized successfully");
        System.out.println("[OpenTelemetry] Prometheus metrics available at http://localhost:9090/metrics");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (Exception e) {
                System.err.println("[OpenTelemetry] Error during shutdown: " + e.getMessage());
            }
        }));

        return openTelemetry;
    }

    /**
     * Get the configured OpenTelemetry instance.
     *
     * @return OpenTelemetry instance
     * @throws IllegalStateException if not initialized
     */
    public static OpenTelemetry getOpenTelemetry() {
        if (openTelemetry == null) {
            throw new IllegalStateException("OpenTelemetry not initialized. Call initialize() first.");
        }
        return openTelemetry;
    }

    /**
     * Get the Meter for creating metrics.
     *
     * @return Meter instance
     * @throws IllegalStateException if not initialized
     */
    public static Meter getMeter() {
        if (meter == null) {
            throw new IllegalStateException("OpenTelemetry not initialized. Call initialize() first.");
        }
        return meter;
    }

    /**
     * Get the Tracer for creating spans.
     *
     * @return Tracer instance
     * @throws IllegalStateException if not initialized
     */
    public static Tracer getTracer() {
        if (tracer == null) {
            throw new IllegalStateException("OpenTelemetry not initialized. Call initialize() first.");
        }
        return tracer;
    }

    /**
     * Shutdown OpenTelemetry SDK and flush metrics/traces.
     */
    public static synchronized void shutdown() {
        if (openTelemetry != null) {
            System.out.println("[OpenTelemetry] Shutting down...");

            // Close Prometheus HTTP server
            if (prometheusServer != null) {
                try {
                    prometheusServer.close();
                } catch (Exception e) {
                    System.err.println("[OpenTelemetry] Error closing Prometheus server: " + e.getMessage());
                }
            }

            // Shutdown SDK
            if (openTelemetry instanceof OpenTelemetrySdk) {
                ((OpenTelemetrySdk) openTelemetry).close();
            }

            openTelemetry = null;
            meter = null;
            tracer = null;
            prometheusServer = null;

            System.out.println("[OpenTelemetry] Shutdown complete");
        }
    }

    /**
     * Get deployment environment from environment variable or default to "development".
     */
    private static String getEnvironment() {
        return System.getenv().getOrDefault("ENVIRONMENT", "development");
    }
}
