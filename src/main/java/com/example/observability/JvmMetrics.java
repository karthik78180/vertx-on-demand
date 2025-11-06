package com.example.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableLongGauge;

import java.lang.management.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JVM metrics for heap memory, metaspace, GC, threads, and CPU.
 * Critical for capacity planning and detecting memory leaks.
 */
public class JvmMetrics {

    private static final AttributeKey<String> POOL_KEY = AttributeKey.stringKey("pool");
    private static final AttributeKey<String> STATE_KEY = AttributeKey.stringKey("state");
    private static final AttributeKey<String> GC_KEY = AttributeKey.stringKey("gc");

    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final OperatingSystemMXBean osBean;

    // Metrics
    private final ObservableDoubleGauge heapUsed;
    private final ObservableDoubleGauge heapCommitted;
    private final ObservableDoubleGauge heapMax;
    private final ObservableDoubleGauge heapUtilization;

    private final ObservableDoubleGauge metaspaceUsed;
    private final ObservableDoubleGauge metaspaceCommitted;
    private final ObservableDoubleGauge metaspaceMax;

    private final ObservableLongGauge gcCollections;
    private final ObservableDoubleGauge gcPauseTime;

    private final ObservableLongGauge threadCount;
    private final ObservableLongGauge threadStates;

    private final ObservableDoubleGauge processCpuUsage;
    private final ObservableDoubleGauge systemCpuUsage;

    public JvmMetrics(Meter meter) {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        // Heap Memory Metrics
        heapUsed = meter.gaugeBuilder("jvm.memory.heap.used")
            .setDescription("Used heap memory in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                measurement.record(heapUsage.getUsed(),
                    Attributes.of(POOL_KEY, "heap"));
            });

        heapCommitted = meter.gaugeBuilder("jvm.memory.heap.committed")
            .setDescription("Committed heap memory in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                measurement.record(heapUsage.getCommitted(),
                    Attributes.of(POOL_KEY, "heap"));
            });

        heapMax = meter.gaugeBuilder("jvm.memory.heap.max")
            .setDescription("Maximum heap memory in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                measurement.record(heapUsage.getMax(),
                    Attributes.of(POOL_KEY, "heap"));
            });

        heapUtilization = meter.gaugeBuilder("jvm.memory.heap.utilization")
            .setDescription("Heap memory utilization percentage (0-100)")
            .setUnit("percent")
            .buildWithCallback(measurement -> {
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                double utilization = (double) heapUsage.getUsed() / heapUsage.getMax() * 100.0;
                measurement.record(utilization,
                    Attributes.of(POOL_KEY, "heap"));
            });

        // Metaspace Metrics
        metaspaceUsed = meter.gaugeBuilder("jvm.memory.metaspace.used")
            .setDescription("Used metaspace memory in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getName().equals("Metaspace")) {
                        measurement.record(pool.getUsage().getUsed(),
                            Attributes.of(POOL_KEY, "metaspace"));
                    }
                }
            });

        metaspaceCommitted = meter.gaugeBuilder("jvm.memory.metaspace.committed")
            .setDescription("Committed metaspace memory in bytes")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getName().equals("Metaspace")) {
                        measurement.record(pool.getUsage().getCommitted(),
                            Attributes.of(POOL_KEY, "metaspace"));
                    }
                }
            });

        metaspaceMax = meter.gaugeBuilder("jvm.memory.metaspace.max")
            .setDescription("Maximum metaspace memory in bytes (-1 if unbounded)")
            .setUnit("bytes")
            .buildWithCallback(measurement -> {
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getName().equals("Metaspace")) {
                        long max = pool.getUsage().getMax();
                        // If unbounded, report committed size instead
                        if (max == -1) {
                            max = pool.getUsage().getCommitted();
                        }
                        measurement.record(max,
                            Attributes.of(POOL_KEY, "metaspace"));
                    }
                }
            });

        // GC Metrics
        gcCollections = meter.gaugeBuilder("jvm.gc.collections.total")
            .ofLongs()
            .setDescription("Total number of garbage collections")
            .setUnit("collections")
            .buildWithCallback(measurement -> {
                for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long count = gcBean.getCollectionCount();
                    if (count >= 0) {
                        measurement.record(count,
                            Attributes.of(GC_KEY, gcBean.getName()));
                    }
                }
            });

        gcPauseTime = meter.gaugeBuilder("jvm.gc.pause.total")
            .setDescription("Total garbage collection pause time in milliseconds")
            .setUnit("ms")
            .buildWithCallback(measurement -> {
                for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long time = gcBean.getCollectionTime();
                    if (time >= 0) {
                        measurement.record(time,
                            Attributes.of(GC_KEY, gcBean.getName()));
                    }
                }
            });

        // Thread Metrics
        threadCount = meter.gaugeBuilder("jvm.threads.count")
            .ofLongs()
            .setDescription("Current number of threads")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                measurement.record(threadBean.getThreadCount(),
                    Attributes.of(STATE_KEY, "all"));
                measurement.record(threadBean.getDaemonThreadCount(),
                    Attributes.of(STATE_KEY, "daemon"));
                measurement.record(threadBean.getPeakThreadCount(),
                    Attributes.of(STATE_KEY, "peak"));
            });

        threadStates = meter.gaugeBuilder("jvm.threads.states")
            .ofLongs()
            .setDescription("Number of threads by state")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                long[] threadIds = threadBean.getAllThreadIds();
                ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

                Map<Thread.State, Long> stateCounts = Arrays.stream(threadInfos)
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(
                        ThreadInfo::getThreadState,
                        Collectors.counting()
                    ));

                for (Map.Entry<Thread.State, Long> entry : stateCounts.entrySet()) {
                    measurement.record(entry.getValue(),
                        Attributes.of(STATE_KEY, entry.getKey().name()));
                }
            });

        // CPU Metrics
        processCpuUsage = meter.gaugeBuilder("process.cpu.usage")
            .setDescription("Process CPU usage percentage (0-100)")
            .setUnit("percent")
            .buildWithCallback(measurement -> {
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsBean =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                    double cpuUsage = sunOsBean.getProcessCpuLoad() * 100.0;
                    if (cpuUsage >= 0) {
                        measurement.record(cpuUsage);
                    }
                }
            });

        systemCpuUsage = meter.gaugeBuilder("system.cpu.usage")
            .setDescription("System CPU usage percentage (0-100)")
            .setUnit("percent")
            .buildWithCallback(measurement -> {
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsBean =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                    double cpuUsage = sunOsBean.getCpuLoad() * 100.0;
                    if (cpuUsage >= 0) {
                        measurement.record(cpuUsage);
                    }
                }
            });

        System.out.println("[JvmMetrics] Initialized JVM metrics (heap, metaspace, GC, threads, CPU)");
    }

    /**
     * Get current heap utilization percentage.
     */
    public double getHeapUtilization() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax() * 100.0;
    }

    /**
     * Get current metaspace usage in bytes.
     */
    public long getMetaspaceUsed() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace")) {
                return pool.getUsage().getUsed();
            }
        }
        return 0;
    }

    /**
     * Check if heap usage is critically high (>90%).
     */
    public boolean isHeapCritical() {
        return getHeapUtilization() > 90.0;
    }

    /**
     * Check if heap usage is elevated (>80%).
     */
    public boolean isHeapElevated() {
        return getHeapUtilization() > 80.0;
    }
}
