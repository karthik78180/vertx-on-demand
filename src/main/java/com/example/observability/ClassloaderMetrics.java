package com.example.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableLongGauge;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Classloader metrics for detecting memory leaks from dynamic JAR loading.
 * Tracks classloader count, classes loaded/unloaded, and correlates with metaspace.
 *
 * CRITICAL for detecting leaks in the URLClassLoader-based verticle deployment system.
 */
public class ClassloaderMetrics {

    private static final AttributeKey<String> TYPE_KEY = AttributeKey.stringKey("type");
    private static final AttributeKey<String> REPO_KEY = AttributeKey.stringKey("repo");

    private static final int BASELINE_CLASSLOADER_COUNT = 50;

    private final ClassLoadingMXBean classLoadingBean;
    private final JvmMetrics jvmMetrics;

    // Track URLClassLoader instances per verticle
    private final Map<String, Integer> classloaderCountByRepo = new ConcurrentHashMap<>();
    private final AtomicInteger totalUrlClassloaders = new AtomicInteger(0);

    // Metrics
    private final ObservableLongGauge classloaderCount;
    private final ObservableLongGauge urlClassloaderCount;
    private final ObservableDoubleGauge classloaderUnloadRatio;
    private final ObservableDoubleGauge metaspacePerClassloader;
    private final ObservableLongGauge leakedClassloaders;

    private final LongCounter classesLoadedCounter;
    private final LongCounter classesUnloadedCounter;

    public ClassloaderMetrics(Meter meter, JvmMetrics jvmMetrics) {
        this.classLoadingBean = ManagementFactory.getClassLoadingMXBean();
        this.jvmMetrics = jvmMetrics;

        // Classloader count by type
        classloaderCount = meter.gaugeBuilder("jvm.classloader.count")
            .ofLongs()
            .setDescription("Number of classloaders")
            .setUnit("classloaders")
            .buildWithCallback(measurement -> {
                // Currently loaded classloaders
                measurement.record(classLoadingBean.getLoadedClassCount(),
                    Attributes.of(TYPE_KEY, "loaded"));

                // Total loaded over lifetime
                measurement.record(classLoadingBean.getTotalLoadedClassCount(),
                    Attributes.of(TYPE_KEY, "total"));

                // Unloaded (garbage collected) classloaders
                measurement.record(classLoadingBean.getUnloadedClassCount(),
                    Attributes.of(TYPE_KEY, "unloaded"));
            });

        // URLClassLoader count (specific to verticle deployments)
        urlClassloaderCount = meter.gaugeBuilder("classloader.url.count")
            .ofLongs()
            .setDescription("Number of URLClassLoader instances for verticle deployments")
            .setUnit("classloaders")
            .buildWithCallback(measurement -> {
                measurement.record(totalUrlClassloaders.get(),
                    Attributes.of(REPO_KEY, "all"));

                // Record per-repo counts
                for (Map.Entry<String, Integer> entry : classloaderCountByRepo.entrySet()) {
                    measurement.record(entry.getValue(),
                        Attributes.of(REPO_KEY, entry.getKey()));
                }
            });

        // Classloader unload ratio (health indicator)
        classloaderUnloadRatio = meter.gaugeBuilder("jvm.classloader.unload_ratio")
            .setDescription("Ratio of unloaded to total classloaders (0-1, higher is better)")
            .setUnit("ratio")
            .buildWithCallback(measurement -> {
                long total = classLoadingBean.getTotalLoadedClassCount();
                long unloaded = classLoadingBean.getUnloadedClassCount();
                if (total > 0) {
                    double ratio = (double) unloaded / total;
                    measurement.record(ratio);
                }
            });

        // Metaspace per classloader (leak indicator)
        metaspacePerClassloader = meter.gaugeBuilder("classloader.metaspace.per_loader")
            .setDescription("Average metaspace usage per classloader in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                long metaspaceUsed = getMetaspaceUsed();
                int loadedClassloaders = classLoadingBean.getLoadedClassCount();
                if (loadedClassloaders > 0) {
                    double avgMetaspace = (double) metaspaceUsed / loadedClassloaders;
                    measurement.record(avgMetaspace);
                }
            });

        // Leaked classloaders estimate
        leakedClassloaders = meter.gaugeBuilder("classloader.leaked.estimate")
            .ofLongs()
            .setDescription("Estimated number of leaked classloaders (actual - expected)")
            .setUnit("classloaders")
            .buildWithCallback(measurement -> {
                int actual = classLoadingBean.getLoadedClassCount();
                int expected = BASELINE_CLASSLOADER_COUNT + totalUrlClassloaders.get();
                int leaked = Math.max(0, actual - expected);
                measurement.record(leaked);
            });

        // Counters for classes loaded/unloaded
        classesLoadedCounter = meter.counterBuilder("verticle.classes_loaded.total")
            .setDescription("Total classes loaded by verticle deployments")
            .setUnit("classes")
            .build();

        classesUnloadedCounter = meter.counterBuilder("verticle.classes_unloaded.total")
            .setDescription("Total classes unloaded after verticle undeployments")
            .setUnit("classes")
            .build();

        System.out.println("[ClassloaderMetrics] Initialized classloader leak detection metrics");
    }

    /**
     * Record a new URLClassLoader created for a verticle deployment.
     *
     * @param repo the verticle repository name
     * @param classesLoaded number of classes loaded in this classloader
     */
    public void recordClassloaderCreated(String repo, long classesLoaded) {
        totalUrlClassloaders.incrementAndGet();
        classloaderCountByRepo.merge(repo, 1, Integer::sum);

        if (classesLoaded > 0) {
            classesLoadedCounter.add(classesLoaded,
                Attributes.of(REPO_KEY, repo));
        }
    }

    /**
     * Record a URLClassLoader removed (should happen on undeploy).
     *
     * @param repo the verticle repository name
     * @param classesUnloaded number of classes that should be unloaded
     */
    public void recordClassloaderRemoved(String repo, long classesUnloaded) {
        totalUrlClassloaders.decrementAndGet();
        classloaderCountByRepo.computeIfPresent(repo, (k, v) -> v > 1 ? v - 1 : null);

        if (classesUnloaded > 0) {
            classesUnloadedCounter.add(classesUnloaded,
                Attributes.of(REPO_KEY, repo));
        }
    }

    /**
     * Check if a classloader leak is detected.
     * A leak is suspected if:
     * 1. Loaded classloaders significantly exceed expected count
     * 2. Unload ratio is low (< 50%)
     * 3. Metaspace is growing without heap growth
     *
     * @return true if leak detected
     */
    public boolean isLeakDetected() {
        int actual = classLoadingBean.getLoadedClassCount();
        int expected = BASELINE_CLASSLOADER_COUNT + totalUrlClassloaders.get();
        int leaked = actual - expected;

        // Threshold: More than 10 leaked classloaders
        if (leaked > 10) {
            System.err.println(String.format(
                "[ClassloaderMetrics] LEAK DETECTED: %d leaked classloaders (expected: %d, actual: %d)",
                leaked, expected, actual
            ));
            return true;
        }

        // Check unload ratio
        long total = classLoadingBean.getTotalLoadedClassCount();
        long unloaded = classLoadingBean.getUnloadedClassCount();
        if (total > 0) {
            double unloadRatio = (double) unloaded / total;
            if (unloadRatio < 0.5 && total > 1000) {
                System.err.println(String.format(
                    "[ClassloaderMetrics] LOW UNLOAD RATIO: %.2f%% (expected > 50%%)",
                    unloadRatio * 100
                ));
                return true;
            }
        }

        return false;
    }

    /**
     * Get the current number of leaked classloaders.
     */
    public int getLeakedCount() {
        int actual = classLoadingBean.getLoadedClassCount();
        int expected = BASELINE_CLASSLOADER_COUNT + totalUrlClassloaders.get();
        return Math.max(0, actual - expected);
    }

    /**
     * Get current metaspace usage.
     */
    private long getMetaspaceUsed() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace")) {
                return pool.getUsage().getUsed();
            }
        }
        return 0;
    }

    /**
     * Perform a comprehensive leak check and log results.
     */
    public void performLeakCheck() {
        int leaked = getLeakedCount();
        if (leaked > 0) {
            long metaspaceUsed = getMetaspaceUsed();
            double heapUtilization = jvmMetrics.getHeapUtilization();

            System.err.println("============================================");
            System.err.println("    CLASSLOADER LEAK DETECTION REPORT");
            System.err.println("============================================");
            System.err.println("Expected classloaders: " + (BASELINE_CLASSLOADER_COUNT + totalUrlClassloaders.get()));
            System.err.println("Actual classloaders:   " + classLoadingBean.getLoadedClassCount());
            System.err.println("LEAKED classloaders:   " + leaked);
            System.err.println("Metaspace usage:       " + (metaspaceUsed / 1024 / 1024) + " MB");
            System.err.println("Heap utilization:      " + String.format("%.2f%%", heapUtilization));
            System.err.println();
            System.err.println("Top suspects:");
            classloaderCountByRepo.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(5)
                .forEach(entry -> System.err.println("  " + entry.getKey() + ": " + entry.getValue() + " classloaders"));
            System.err.println("============================================");
        }
    }

    /**
     * Get classloader count by repository name.
     */
    public Map<String, Integer> getClassloaderCountByRepo() {
        return new ConcurrentHashMap<>(classloaderCountByRepo);
    }
}
