package com.example.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deployment lifecycle metrics for verticle deployments and undeployments.
 */
public class DeploymentMetrics {

    private static final AttributeKey<String> REPO_KEY = AttributeKey.stringKey("repo");
    private static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("status");
    private static final AttributeKey<String> FAILURE_REASON_KEY = AttributeKey.stringKey("failure_reason");

    // Track active verticles
    private final Map<String, AtomicInteger> activeVerticlesByRepo = new ConcurrentHashMap<>();
    private final AtomicInteger totalActiveVerticles = new AtomicInteger(0);

    // Metrics
    private final LongCounter deploymentTotal;
    private final DoubleHistogram deploymentDuration;
    private final ObservableLongGauge activeVerticles;
    private final LongCounter undeploymentTotal;
    private final DoubleHistogram classesLoadedPerDeployment;

    public DeploymentMetrics(Meter meter) {
        // Deployment counter
        deploymentTotal = meter.counterBuilder("verticle.deployment.total")
            .setDescription("Total verticle deployment attempts")
            .setUnit("deployments")
            .build();

        // Deployment duration histogram
        deploymentDuration = meter.histogramBuilder("verticle.deployment.duration")
            .setDescription("Verticle deployment duration")
            .setUnit("ms")
            .build();

        // Active verticles gauge
        activeVerticles = meter.gaugeBuilder("verticle.active.count")
            .ofLongs()
            .setDescription("Number of currently deployed verticles")
            .setUnit("verticles")
            .buildWithCallback(measurement -> {
                // Total active
                measurement.record(totalActiveVerticles.get(),
                    Attributes.of(REPO_KEY, "all"));

                // Per-repo counts
                for (Map.Entry<String, AtomicInteger> entry : activeVerticlesByRepo.entrySet()) {
                    measurement.record(entry.getValue().get(),
                        Attributes.of(REPO_KEY, entry.getKey()));
                }
            });

        // Undeployment counter
        undeploymentTotal = meter.counterBuilder("verticle.undeployment.total")
            .setDescription("Total verticle undeployment operations")
            .setUnit("undeployments")
            .build();

        // Classes loaded per deployment
        classesLoadedPerDeployment = meter.histogramBuilder("verticle.deployment.classes_loaded")
            .setDescription("Number of classes loaded per deployment")
            .setUnit("classes")
            .build();

        System.out.println("[DeploymentMetrics] Initialized deployment lifecycle metrics");
    }

    /**
     * Record a successful deployment.
     *
     * @param repo verticle repository name
     * @param durationMs deployment duration in milliseconds
     * @param classesLoaded number of classes loaded
     */
    public void recordDeploymentSuccess(String repo, long durationMs, long classesLoaded) {
        recordDeployment(repo, true, durationMs, null);

        if (classesLoaded > 0) {
            classesLoadedPerDeployment.record(classesLoaded,
                Attributes.of(REPO_KEY, repo));
        }

        // Increment active verticle count
        totalActiveVerticles.incrementAndGet();
        activeVerticlesByRepo.computeIfAbsent(repo, k -> new AtomicInteger(0))
            .incrementAndGet();
    }

    /**
     * Record a failed deployment.
     *
     * @param repo verticle repository name
     * @param durationMs deployment duration in milliseconds
     * @param failureReason reason for failure
     */
    public void recordDeploymentFailure(String repo, long durationMs, String failureReason) {
        recordDeployment(repo, false, durationMs, failureReason);
    }

    /**
     * Record a deployment attempt (success or failure).
     */
    private void recordDeployment(String repo, boolean success, long durationMs, String failureReason) {
        Attributes.Builder attributesBuilder = Attributes.builder()
            .put(REPO_KEY, repo)
            .put(STATUS_KEY, success ? "success" : "failure");

        if (failureReason != null) {
            attributesBuilder.put(FAILURE_REASON_KEY, failureReason);
        }

        Attributes attributes = attributesBuilder.build();

        // Increment counter
        deploymentTotal.add(1, attributes);

        // Record duration
        deploymentDuration.record(durationMs, attributes);
    }

    /**
     * Record an undeployment operation.
     *
     * @param verticleCount number of verticles undeployed
     */
    public void recordUndeployment(int verticleCount) {
        undeploymentTotal.add(1, Attributes.of(
            AttributeKey.longKey("count"), (long) verticleCount
        ));

        // Decrement active verticle count
        totalActiveVerticles.addAndGet(-verticleCount);
    }

    /**
     * Record undeployment of a specific verticle.
     *
     * @param repo verticle repository name
     */
    public void recordVerticleUndeployed(String repo) {
        totalActiveVerticles.decrementAndGet();
        activeVerticlesByRepo.computeIfPresent(repo, (k, v) -> {
            v.decrementAndGet();
            return v.get() > 0 ? v : null;
        });
    }

    /**
     * Get total number of active verticles.
     */
    public int getActiveVerticleCount() {
        return totalActiveVerticles.get();
    }

    /**
     * Get active verticle count by repository.
     */
    public Map<String, Integer> getActiveVerticlesByRepo() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        activeVerticlesByRepo.forEach((repo, count) -> result.put(repo, count.get()));
        return result;
    }

    /**
     * Calculate deployment success rate (last N deployments).
     * Note: This is a simple in-memory calculation. For production,
     * use PromQL queries on the deployment_total counter.
     */
    public double getDeploymentSuccessRate() {
        // This would require maintaining a rolling window of deployment results
        // For now, this is just a placeholder
        // In practice, use PromQL:
        // rate(verticle_deployment_total{status="success"}[1h]) /
        // rate(verticle_deployment_total[1h])
        return 0.0;
    }
}
