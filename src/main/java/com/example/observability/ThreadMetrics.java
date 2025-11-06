package com.example.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableLongGauge;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread metrics with focus on Vert.x event loop, worker threads, blocked threads,
 * and virtual threads (Java 21).
 *
 * CRITICAL for detecting:
 * - Event loop blocking (kills performance)
 * - Deadlocks
 * - Thread pool exhaustion
 * - Virtual thread usage (Java 21+)
 */
public class ThreadMetrics {

    private static final AttributeKey<String> THREAD_TYPE_KEY = AttributeKey.stringKey("thread_type");
    private static final AttributeKey<String> STATE_KEY = AttributeKey.stringKey("state");
    private static final AttributeKey<String> THREAD_NAME_KEY = AttributeKey.stringKey("thread_name");
    private static final AttributeKey<String> VIRTUAL_KEY = AttributeKey.stringKey("virtual");

    // Vert.x thread name patterns
    private static final String EVENT_LOOP_PATTERN = "vert.x-eventloop-thread-";
    private static final String WORKER_PATTERN = "vert.x-worker-thread-";
    private static final String INTERNAL_PATTERN = "vert.x-internal-blocking-";
    private static final String ACCEPTOR_PATTERN = "vert.x-acceptor-thread-";

    // Blocked thread tracking
    private static final long BLOCKED_THRESHOLD_MS = 100; // Consider blocked if > 100ms
    private static final long CRITICAL_BLOCKED_THRESHOLD_MS = 5000; // Critical if blocked > 5s

    private final ThreadMXBean threadBean;
    private final Map<Long, BlockedThreadInfo> blockedThreads = new ConcurrentHashMap<>();

    // Metrics
    private final ObservableLongGauge vertxEventLoopThreads;
    private final ObservableLongGauge vertxWorkerThreads;
    private final ObservableLongGauge vertxInternalThreads;
    private final ObservableLongGauge virtualThreadCount;

    private final ObservableLongGauge threadsByState;
    private final ObservableLongGauge blockedThreadCount;
    private final ObservableDoubleGauge blockedThreadDuration;

    private final LongCounter eventLoopBlockedTotal;
    private final LongCounter criticalBlockedThreadsTotal;

    public ThreadMetrics(Meter meter) {
        this.threadBean = ManagementFactory.getThreadMXBean();

        // Enable thread contention monitoring if supported
        if (threadBean.isThreadContentionMonitoringSupported()) {
            threadBean.setThreadContentionMonitoringEnabled(true);
        }

        // Vert.x-specific thread counts
        vertxEventLoopThreads = meter.gaugeBuilder("vertx.threads.eventloop")
            .ofLongs()
            .setDescription("Number of Vert.x event loop threads")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                Map<String, Long> threadCounts = categorizeVertxThreads();
                measurement.record(threadCounts.getOrDefault("eventloop", 0L),
                    Attributes.of(STATE_KEY, "all"));

                // Count by state
                Map<String, Long> eventLoopStates = getEventLoopThreadStates();
                eventLoopStates.forEach((state, count) ->
                    measurement.record(count, Attributes.of(STATE_KEY, state)));
            });

        vertxWorkerThreads = meter.gaugeBuilder("vertx.threads.worker")
            .ofLongs()
            .setDescription("Number of Vert.x worker threads")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                Map<String, Long> threadCounts = categorizeVertxThreads();
                measurement.record(threadCounts.getOrDefault("worker", 0L),
                    Attributes.of(STATE_KEY, "all"));

                // Count by state
                Map<String, Long> workerStates = getWorkerThreadStates();
                workerStates.forEach((state, count) ->
                    measurement.record(count, Attributes.of(STATE_KEY, state)));
            });

        vertxInternalThreads = meter.gaugeBuilder("vertx.threads.internal")
            .ofLongs()
            .setDescription("Number of Vert.x internal blocking threads")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                Map<String, Long> threadCounts = categorizeVertxThreads();
                measurement.record(threadCounts.getOrDefault("internal", 0L));
            });

        // Virtual thread tracking (Java 21+)
        virtualThreadCount = meter.gaugeBuilder("jvm.threads.virtual.count")
            .ofLongs()
            .setDescription("Number of virtual threads (Java 21+)")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                long virtualCount = countVirtualThreads();
                measurement.record(virtualCount);
            });

        // Thread states with thread type breakdown
        threadsByState = meter.gaugeBuilder("jvm.threads.by_state")
            .ofLongs()
            .setDescription("Thread count by state and type")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                Map<ThreadStateInfo, Long> stateBreakdown = getThreadStateBreakdown();
                stateBreakdown.forEach((info, count) ->
                    measurement.record(count, Attributes.of(
                        STATE_KEY, info.state.name(),
                        THREAD_TYPE_KEY, info.threadType,
                        VIRTUAL_KEY, String.valueOf(info.isVirtual)
                    )));
            });

        // Blocked thread detection
        blockedThreadCount = meter.gaugeBuilder("jvm.threads.blocked.count")
            .ofLongs()
            .setDescription("Number of currently blocked threads")
            .setUnit("threads")
            .buildWithCallback(measurement -> {
                updateBlockedThreads();
                measurement.record(blockedThreads.size(),
                    Attributes.of(THREAD_TYPE_KEY, "all"));

                // Count blocked event loop threads (CRITICAL!)
                long blockedEventLoop = blockedThreads.values().stream()
                    .filter(info -> info.threadName.contains(EVENT_LOOP_PATTERN))
                    .count();
                if (blockedEventLoop > 0) {
                    measurement.record(blockedEventLoop,
                        Attributes.of(THREAD_TYPE_KEY, "eventloop"));
                }

                // Count blocked worker threads
                long blockedWorker = blockedThreads.values().stream()
                    .filter(info -> info.threadName.contains(WORKER_PATTERN))
                    .count();
                if (blockedWorker > 0) {
                    measurement.record(blockedWorker,
                        Attributes.of(THREAD_TYPE_KEY, "worker"));
                }
            });

        blockedThreadDuration = meter.gaugeBuilder("jvm.threads.blocked.duration")
            .setDescription("Maximum blocked thread duration in milliseconds")
            .setUnit("ms")
            .buildWithCallback(measurement -> {
                updateBlockedThreads();
                if (!blockedThreads.isEmpty()) {
                    long maxDuration = blockedThreads.values().stream()
                        .mapToLong(info -> info.blockedDurationMs)
                        .max()
                        .orElse(0);
                    measurement.record(maxDuration);

                    // Max blocked event loop duration (CRITICAL)
                    long maxEventLoopBlocked = blockedThreads.values().stream()
                        .filter(info -> info.threadName.contains(EVENT_LOOP_PATTERN))
                        .mapToLong(info -> info.blockedDurationMs)
                        .max()
                        .orElse(0);
                    if (maxEventLoopBlocked > 0) {
                        measurement.record(maxEventLoopBlocked,
                            Attributes.of(THREAD_TYPE_KEY, "eventloop"));
                    }
                }
            });

        // Event loop blocked counter (CRITICAL metric)
        eventLoopBlockedTotal = meter.counterBuilder("vertx.eventloop.blocked.total")
            .setDescription("Total number of times event loop threads were blocked")
            .setUnit("incidents")
            .build();

        // Critical blocked threads counter
        criticalBlockedThreadsTotal = meter.counterBuilder("jvm.threads.blocked.critical.total")
            .setDescription("Total number of critically blocked threads (>5s)")
            .setUnit("incidents")
            .build();

        System.out.println("[ThreadMetrics] Initialized thread monitoring (event loop, worker, blocked, virtual)");
    }

    /**
     * Categorize threads by Vert.x type.
     */
    private Map<String, Long> categorizeVertxThreads() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("eventloop", 0L);
        counts.put("worker", 0L);
        counts.put("internal", 0L);
        counts.put("acceptor", 0L);

        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;

            String name = info.getThreadName();
            if (name.contains(EVENT_LOOP_PATTERN)) {
                counts.merge("eventloop", 1L, Long::sum);
            } else if (name.contains(WORKER_PATTERN)) {
                counts.merge("worker", 1L, Long::sum);
            } else if (name.contains(INTERNAL_PATTERN)) {
                counts.merge("internal", 1L, Long::sum);
            } else if (name.contains(ACCEPTOR_PATTERN)) {
                counts.merge("acceptor", 1L, Long::sum);
            }
        }

        return counts;
    }

    /**
     * Get event loop thread states.
     */
    private Map<String, Long> getEventLoopThreadStates() {
        return getThreadStatesByPattern(EVENT_LOOP_PATTERN);
    }

    /**
     * Get worker thread states.
     */
    private Map<String, Long> getWorkerThreadStates() {
        return getThreadStatesByPattern(WORKER_PATTERN);
    }

    /**
     * Get thread states for a specific name pattern.
     */
    private Map<String, Long> getThreadStatesByPattern(String pattern) {
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

        return Arrays.stream(threadInfos)
            .filter(Objects::nonNull)
            .filter(info -> info.getThreadName().contains(pattern))
            .collect(Collectors.groupingBy(
                info -> info.getThreadState().name(),
                Collectors.counting()
            ));
    }

    /**
     * Count virtual threads (Java 21+).
     * Uses reflection to maintain Java 21 compatibility.
     */
    private long countVirtualThreads() {
        try {
            // Java 21: Thread.isVirtual() method
            long[] threadIds = threadBean.getAllThreadIds();
            ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

            long virtualCount = 0;
            for (ThreadInfo info : threadInfos) {
                if (info != null && isVirtualThread(info)) {
                    virtualCount++;
                }
            }
            return virtualCount;
        } catch (Exception e) {
            // Virtual threads not available (Java < 21)
            return 0;
        }
    }

    /**
     * Check if thread is virtual (Java 21+).
     */
    private boolean isVirtualThread(ThreadInfo threadInfo) {
        try {
            // In Java 21, virtual threads have specific naming patterns
            // and can be detected via Thread.isVirtual() on Thread object
            // For now, detect by thread name patterns
            String name = threadInfo.getThreadName();
            return name != null && (
                name.startsWith("VirtualThread-") ||
                name.contains("virtual") ||
                // Virtual threads created by Executors.newVirtualThreadPerTaskExecutor()
                name.matches(".*virtual.*\\d+.*")
            );
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get detailed thread state breakdown by type.
     */
    private Map<ThreadStateInfo, Long> getThreadStateBreakdown() {
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

        Map<ThreadStateInfo, Long> breakdown = new HashMap<>();

        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;

            String threadType = determineThreadType(info.getThreadName());
            boolean isVirtual = isVirtualThread(info);
            Thread.State state = info.getThreadState();

            ThreadStateInfo key = new ThreadStateInfo(state, threadType, isVirtual);
            breakdown.merge(key, 1L, Long::sum);
        }

        return breakdown;
    }

    /**
     * Determine thread type from name.
     */
    private String determineThreadType(String threadName) {
        if (threadName.contains(EVENT_LOOP_PATTERN)) return "eventloop";
        if (threadName.contains(WORKER_PATTERN)) return "worker";
        if (threadName.contains(INTERNAL_PATTERN)) return "internal";
        if (threadName.contains(ACCEPTOR_PATTERN)) return "acceptor";
        if (threadName.contains("GC") || threadName.contains("gc")) return "gc";
        if (threadName.contains("Finalizer")) return "finalizer";
        if (threadName.contains("Reference Handler")) return "reference_handler";
        if (threadName.contains("main")) return "main";
        return "other";
    }

    /**
     * Update blocked threads tracking.
     */
    private void updateBlockedThreads() {
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds, Integer.MAX_VALUE);

        Set<Long> currentlyBlockedIds = new HashSet<>();

        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;

            // Check if thread is blocked
            if (info.getThreadState() == Thread.State.BLOCKED) {
                long blockedTime = info.getBlockedTime(); // -1 if not supported
                long blockedCount = info.getBlockedCount();

                // If blocked time is not available, estimate from lock info
                if (blockedTime == -1 && info.getLockInfo() != null) {
                    blockedTime = estimateBlockedTime(info);
                }

                if (blockedTime >= BLOCKED_THRESHOLD_MS || blockedCount > 0) {
                    long threadId = info.getThreadId();
                    currentlyBlockedIds.add(threadId);

                    BlockedThreadInfo blockedInfo = blockedThreads.computeIfAbsent(threadId,
                        id -> new BlockedThreadInfo(threadId, info.getThreadName()));

                    blockedInfo.update(blockedTime, info.getThreadState(), info.getStackTrace());

                    // Alert on critical conditions
                    if (blockedTime >= CRITICAL_BLOCKED_THRESHOLD_MS) {
                        criticalBlockedThreadsTotal.add(1);
                        logCriticalBlockedThread(info, blockedTime);
                    }

                    // CRITICAL: Alert on blocked event loop
                    if (info.getThreadName().contains(EVENT_LOOP_PATTERN)) {
                        eventLoopBlockedTotal.add(1);
                        logBlockedEventLoop(info, blockedTime);
                    }
                }
            }
        }

        // Remove threads that are no longer blocked
        blockedThreads.keySet().retainAll(currentlyBlockedIds);
    }

    /**
     * Estimate blocked time from thread info.
     */
    private long estimateBlockedTime(ThreadInfo info) {
        // Rough estimation based on blocked count and typical lock acquisition time
        // This is not accurate but better than nothing
        return Math.min(info.getBlockedCount() * 10, 10000);
    }

    /**
     * Log critical blocked thread.
     */
    private void logCriticalBlockedThread(ThreadInfo info, long blockedTime) {
        System.err.println("============================================");
        System.err.println("   CRITICAL: THREAD BLOCKED > 5 SECONDS");
        System.err.println("============================================");
        System.err.println("Thread: " + info.getThreadName());
        System.err.println("ID: " + info.getThreadId());
        System.err.println("State: " + info.getThreadState());
        System.err.println("Blocked Time: " + blockedTime + "ms");
        System.err.println("Blocked Count: " + info.getBlockedCount());

        if (info.getLockInfo() != null) {
            System.err.println("Lock: " + info.getLockInfo());
            System.err.println("Lock Owner: " + info.getLockOwnerName() +
                " (ID: " + info.getLockOwnerId() + ")");
        }

        System.err.println("\nStack Trace:");
        for (StackTraceElement element : info.getStackTrace()) {
            System.err.println("  at " + element);
        }
        System.err.println("============================================");
    }

    /**
     * Log blocked event loop thread (CRITICAL for Vert.x).
     */
    private void logBlockedEventLoop(ThreadInfo info, long blockedTime) {
        System.err.println("============================================");
        System.err.println("   🚨 CRITICAL: EVENT LOOP BLOCKED! 🚨");
        System.err.println("============================================");
        System.err.println("Event loop threads should NEVER block!");
        System.err.println("This will severely degrade application performance.");
        System.err.println();
        System.err.println("Thread: " + info.getThreadName());
        System.err.println("Blocked Time: " + blockedTime + "ms");
        System.err.println("State: " + info.getThreadState());

        if (info.getLockInfo() != null) {
            System.err.println("Lock: " + info.getLockInfo());
        }

        System.err.println("\nStack Trace:");
        for (int i = 0; i < Math.min(10, info.getStackTrace().length); i++) {
            System.err.println("  at " + info.getStackTrace()[i]);
        }

        System.err.println("\n⚠️  ACTION REQUIRED:");
        System.err.println("1. Move blocking operation to worker thread");
        System.err.println("2. Use executeBlocking() for blocking calls");
        System.err.println("3. Review code at: " + info.getStackTrace()[0]);
        System.err.println("============================================");
    }

    /**
     * Get detailed blocked thread report.
     */
    public String getBlockedThreadReport() {
        if (blockedThreads.isEmpty()) {
            return "No blocked threads detected.";
        }

        StringBuilder report = new StringBuilder();
        report.append("\n============================================\n");
        report.append("       BLOCKED THREADS REPORT\n");
        report.append("============================================\n");
        report.append("Total Blocked Threads: ").append(blockedThreads.size()).append("\n\n");

        blockedThreads.values().stream()
            .sorted((a, b) -> Long.compare(b.blockedDurationMs, a.blockedDurationMs))
            .forEach(info -> {
                report.append("Thread: ").append(info.threadName).append("\n");
                report.append("  ID: ").append(info.threadId).append("\n");
                report.append("  State: ").append(info.state).append("\n");
                report.append("  Blocked Duration: ").append(info.blockedDurationMs).append("ms\n");
                report.append("  First Detected: ").append(new Date(info.firstDetectedAt)).append("\n");

                if (info.stackTrace != null && info.stackTrace.length > 0) {
                    report.append("  Top Stack Frame: ").append(info.stackTrace[0]).append("\n");
                }

                report.append("\n");
            });

        report.append("============================================\n");
        return report.toString();
    }

    /**
     * Check if any event loop threads are blocked (CRITICAL).
     */
    public boolean isEventLoopBlocked() {
        return blockedThreads.values().stream()
            .anyMatch(info -> info.threadName.contains(EVENT_LOOP_PATTERN));
    }

    /**
     * Get count of blocked event loop threads.
     */
    public int getBlockedEventLoopCount() {
        return (int) blockedThreads.values().stream()
            .filter(info -> info.threadName.contains(EVENT_LOOP_PATTERN))
            .count();
    }

    /**
     * Get count of blocked threads.
     */
    public int getBlockedThreadCount() {
        return blockedThreads.size();
    }

    /**
     * Check if using virtual threads.
     */
    public boolean isUsingVirtualThreads() {
        return countVirtualThreads() > 0;
    }

    /**
     * Blocked thread information.
     */
    private static class BlockedThreadInfo {
        final long threadId;
        final String threadName;
        long blockedDurationMs;
        Thread.State state;
        StackTraceElement[] stackTrace;
        long firstDetectedAt;
        long lastUpdatedAt;

        BlockedThreadInfo(long threadId, String threadName) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.firstDetectedAt = System.currentTimeMillis();
            this.lastUpdatedAt = firstDetectedAt;
        }

        void update(long blockedTime, Thread.State state, StackTraceElement[] stackTrace) {
            this.blockedDurationMs = blockedTime;
            this.state = state;
            this.stackTrace = stackTrace;
            this.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    /**
     * Thread state information for grouping.
     */
    private static class ThreadStateInfo {
        final Thread.State state;
        final String threadType;
        final boolean isVirtual;

        ThreadStateInfo(Thread.State state, String threadType, boolean isVirtual) {
            this.state = state;
            this.threadType = threadType;
            this.isVirtual = isVirtual;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ThreadStateInfo that = (ThreadStateInfo) o;
            return isVirtual == that.isVirtual &&
                state == that.state &&
                Objects.equals(threadType, that.threadType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, threadType, isVirtual);
        }
    }
}
