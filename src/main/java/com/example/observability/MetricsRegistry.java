package com.example.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;

/**
 * Central registry for all application metrics.
 * Initializes and provides access to JVM, Classloader, HTTP, Deployment, and Thread metrics.
 */
public class MetricsRegistry {

    private static volatile MetricsRegistry instance;

    private final JvmMetrics jvmMetrics;
    private final ClassloaderMetrics classloaderMetrics;
    private final HttpMetrics httpMetrics;
    private final DeploymentMetrics deploymentMetrics;
    private final ThreadMetrics threadMetrics;

    private MetricsRegistry(Meter meter) {
        // Initialize metrics in order (JVM first, as others may depend on it)
        this.jvmMetrics = new JvmMetrics(meter);
        this.classloaderMetrics = new ClassloaderMetrics(meter, jvmMetrics);
        this.httpMetrics = new HttpMetrics(meter);
        this.deploymentMetrics = new DeploymentMetrics(meter);
        this.threadMetrics = new ThreadMetrics(meter);

        System.out.println("[MetricsRegistry] All metrics initialized successfully");
    }

    /**
     * Initialize the metrics registry with OpenTelemetry.
     * This should be called once at application startup after OpenTelemetry is initialized.
     *
     * @return MetricsRegistry instance
     */
    public static synchronized MetricsRegistry initialize() {
        if (instance != null) {
            return instance;
        }

        OpenTelemetry openTelemetry = OpenTelemetryConfig.getOpenTelemetry();
        Meter meter = OpenTelemetryConfig.getMeter();

        instance = new MetricsRegistry(meter);
        return instance;
    }

    /**
     * Get the singleton MetricsRegistry instance.
     *
     * @return MetricsRegistry instance
     * @throws IllegalStateException if not initialized
     */
    public static MetricsRegistry getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MetricsRegistry not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Get JVM metrics (heap, GC, threads, CPU).
     */
    public JvmMetrics jvm() {
        return jvmMetrics;
    }

    /**
     * Get classloader metrics (leak detection).
     */
    public ClassloaderMetrics classloader() {
        return classloaderMetrics;
    }

    /**
     * Get HTTP metrics (requests, latency, errors).
     */
    public HttpMetrics http() {
        return httpMetrics;
    }

    /**
     * Get deployment metrics (verticle lifecycle).
     */
    public DeploymentMetrics deployment() {
        return deploymentMetrics;
    }

    /**
     * Get thread metrics (event loop, worker, blocked, virtual).
     */
    public ThreadMetrics threads() {
        return threadMetrics;
    }

    /**
     * Perform comprehensive health checks.
     * Logs warnings for any detected issues.
     *
     * @return true if all health checks pass
     */
    public boolean performHealthCheck() {
        boolean healthy = true;

        // Check heap usage
        if (jvmMetrics.isHeapCritical()) {
            System.err.println("[HealthCheck] CRITICAL: Heap usage > 90%");
            healthy = false;
        } else if (jvmMetrics.isHeapElevated()) {
            System.err.println("[HealthCheck] WARNING: Heap usage > 80%");
        }

        // Check for classloader leaks
        if (classloaderMetrics.isLeakDetected()) {
            System.err.println("[HealthCheck] CRITICAL: Classloader leak detected");
            classloaderMetrics.performLeakCheck();
            healthy = false;
        }

        // Check for blocked event loop threads (CRITICAL!)
        if (threadMetrics.isEventLoopBlocked()) {
            System.err.println("[HealthCheck] CRITICAL: Event loop thread(s) blocked!");
            System.err.println("[HealthCheck] Blocked event loops: " + threadMetrics.getBlockedEventLoopCount());
            healthy = false;
        }

        // Check for blocked threads
        int blockedThreads = threadMetrics.getBlockedThreadCount();
        if (blockedThreads > 10) {
            System.err.println("[HealthCheck] WARNING: " + blockedThreads + " threads blocked");
            System.err.println(threadMetrics.getBlockedThreadReport());
        }

        // Log virtual thread usage
        if (threadMetrics.isUsingVirtualThreads()) {
            System.out.println("[HealthCheck] INFO: Virtual threads detected (Java 21+)");
        }

        return healthy;
    }

    /**
     * Reset the singleton instance (for testing).
     */
    static synchronized void reset() {
        instance = null;
    }
}
