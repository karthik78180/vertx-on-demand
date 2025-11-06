# OpenTelemetry Instrumentation Guide for Vert.x

## Table of Contents
1. [OpenTelemetry Fundamentals](#opentelemetry-fundamentals)
2. [Metric Types Explained](#metric-types-explained)
3. [Instrumentation Terminology](#instrumentation-terminology)
4. [Micrometer vs OpenTelemetry](#micrometer-vs-opentelemetry)
5. [JVM & System Metrics Deep Dive](#jvm--system-metrics-deep-dive)
6. [Capacity & Scaling Metrics](#capacity--scaling-metrics)
7. [Classloader Leak Detection](#classloader-leak-detection)
8. [Best Practices](#best-practices)
9. [Implementation Patterns](#implementation-patterns)

---

## OpenTelemetry Fundamentals

### What is OpenTelemetry?

OpenTelemetry (OTEL) is a **vendor-neutral observability framework** that provides:
- **APIs** for instrumenting code
- **SDKs** for collecting telemetry data
- **Exporters** for sending data to backends (Prometheus, Jaeger, Datadog, etc.)

### The Three Pillars of Observability

```
┌─────────────────────────────────────────────────────────────────┐
│                    OBSERVABILITY PILLARS                         │
├─────────────────┬─────────────────────┬─────────────────────────┤
│    METRICS      │       TRACES        │         LOGS            │
│  (Aggregated)   │   (Request Flow)    │     (Events)            │
├─────────────────┼─────────────────────┼─────────────────────────┤
│ • Counters      │ • Spans             │ • Structured events     │
│ • Gauges        │ • Parent-child      │ • Correlated with       │
│ • Histograms    │   relationships     │   traces via trace_id   │
│ • Summary       │ • Attributes        │ • Severity levels       │
│                 │ • Timeline          │ • Context propagation   │
├─────────────────┼─────────────────────┼─────────────────────────┤
│ WHEN TO USE:    │ WHEN TO USE:        │ WHEN TO USE:            │
│ • Alerting      │ • Debugging         │ • Detailed investigation│
│ • Dashboards    │ • Latency analysis  │ • Error messages        │
│ • Trends        │ • Bottlenecks       │ • Audit trails          │
│ • SLIs/SLOs     │ • Dependencies      │ • Business events       │
└─────────────────┴─────────────────────┴─────────────────────────┘
```

---

## Metric Types Explained

### 1. Counter (Monotonic)

**Definition**: A value that **only increases** over time (never decreases), reset only on restart.

**Characteristics**:
- Accumulates values
- Cannot go down
- Typically used with `_total` suffix
- Rate calculation: `rate(metric[time_range])`

**Examples**:
```java
// HTTP requests counter
LongCounter requestCounter = meter
    .counterBuilder("http.server.requests.total")
    .setDescription("Total number of HTTP requests")
    .setUnit("requests")
    .build();

// Increment on each request
requestCounter.add(1,
    Attributes.of(
        stringKey("endpoint"), "/deploy",
        stringKey("status_code"), "200"
    ));
```

**Use Cases**:
- Total HTTP requests
- Total errors
- Total bytes transferred
- Total deployments
- Total garbage collections

**PromQL Queries**:
```promql
# Request rate over last 5 minutes
rate(http_server_requests_total[5m])

# Total requests in last hour
increase(http_server_requests_total[1h])

# Error rate percentage
(rate(http_server_requests_total{status_code=~"5.."}[5m]) /
 rate(http_server_requests_total[5m])) * 100
```

**Visualization**: Best shown as **rate graphs** (requests/second) or **increase** over time.

---

### 2. UpDownCounter (Non-Monotonic Counter)

**Definition**: A value that can **increase or decrease** over time.

**Characteristics**:
- Can go up and down
- Current state/level
- Also called "gauge counter" in some contexts

**Examples**:
```java
// Active connections
LongUpDownCounter activeConnections = meter
    .upDownCounterBuilder("http.server.active_connections")
    .setDescription("Current number of active HTTP connections")
    .setUnit("connections")
    .build();

// Connection established
activeConnections.add(1);

// Connection closed
activeConnections.add(-1);
```

**Use Cases**:
- Active connections
- Items in queue
- In-progress requests
- Active threads

---

### 3. Gauge (Observable)

**Definition**: A value that represents a **snapshot** at a specific point in time.

**Characteristics**:
- Can go up or down
- Sampled periodically (not incremented manually)
- Represents "current state"
- Set via callback function

**Examples**:
```java
// JVM memory usage gauge
ObservableDoubleGauge memoryGauge = meter
    .gaugeBuilder("jvm.memory.used")
    .setDescription("Current JVM memory usage")
    .setUnit("bytes")
    .buildWithCallback(measurement -> {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        measurement.record(
            heapUsage.getUsed(),
            Attributes.of(stringKey("pool"), "heap")
        );
    });

// Active verticles gauge
ObservableLongGauge verticleGauge = meter
    .gaugeBuilder("verticle.active.count")
    .ofLongs()
    .setDescription("Number of currently deployed verticles")
    .setUnit("verticles")
    .buildWithCallback(measurement -> {
        measurement.record(
            deployedVerticles.size(),
            Attributes.empty()
        );
    });
```

**Use Cases**:
- JVM heap usage
- CPU usage
- Thread count
- Temperature
- Number of active verticles
- Queue length

**PromQL Queries**:
```promql
# Current heap usage
jvm_memory_used{pool="heap"}

# Heap usage as percentage of max
(jvm_memory_used{pool="heap"} / jvm_memory_max{pool="heap"}) * 100

# Average verticle count over 5 minutes
avg_over_time(verticle_active_count[5m])
```

**Visualization**: Best shown as **current value** or **time series** showing fluctuations.

---

### 4. Histogram (Distribution)

**Definition**: Captures the **distribution** of values in configurable buckets.

**Characteristics**:
- Records multiple values and counts them in buckets
- Calculates sum and count automatically
- Allows percentile calculation (P50, P95, P99)
- Pre-defined bucket boundaries

**Examples**:
```java
// Request duration histogram
DoubleHistogram requestDuration = meter
    .histogramBuilder("http.server.duration")
    .setDescription("HTTP request duration")
    .setUnit("ms")
    .build();

// Record request duration
long startTime = System.currentTimeMillis();
// ... handle request ...
long duration = System.currentTimeMillis() - startTime;

requestDuration.record(duration,
    Attributes.of(
        stringKey("endpoint"), "/deploy",
        stringKey("method"), "POST"
    ));
```

**Bucket Configuration**:
```java
// Custom buckets for request duration
// Buckets: [10ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s]
View durationView = View.builder()
    .setName("http.server.duration")
    .setAggregation(
        Aggregation.explicitBucketHistogram(
            List.of(10.0, 50.0, 100.0, 250.0, 500.0,
                    1000.0, 2500.0, 5000.0, 10000.0)
        )
    )
    .build();
```

**Prometheus Output**:
```prometheus
http_server_duration_bucket{endpoint="/deploy",le="10"} 5
http_server_duration_bucket{endpoint="/deploy",le="50"} 23
http_server_duration_bucket{endpoint="/deploy",le="100"} 45
http_server_duration_bucket{endpoint="/deploy",le="250"} 67
http_server_duration_bucket{endpoint="/deploy",le="500"} 89
http_server_duration_bucket{endpoint="/deploy",le="1000"} 95
http_server_duration_bucket{endpoint="/deploy",le="2500"} 98
http_server_duration_bucket{endpoint="/deploy",le="+Inf"} 100
http_server_duration_sum{endpoint="/deploy"} 15234.5
http_server_duration_count{endpoint="/deploy"} 100
```

**Use Cases**:
- Request latency/duration
- Response sizes
- Query execution time
- Deployment duration
- Garbage collection pauses

**PromQL Queries**:
```promql
# P95 latency
histogram_quantile(0.95,
  rate(http_server_duration_bucket[5m])
)

# P99 latency
histogram_quantile(0.99,
  rate(http_server_duration_bucket[5m])
)

# Average request duration
rate(http_server_duration_sum[5m]) /
rate(http_server_duration_count[5m])

# Apdex score (T=100ms)
(
  sum(rate(http_server_duration_bucket{le="100"}[5m])) +
  sum(rate(http_server_duration_bucket{le="400"}[5m])) * 0.5
) / sum(rate(http_server_duration_count[5m]))
```

**Visualization**: Best shown as **heatmaps** or **percentile graphs**.

---

### 5. Summary (Deprecated in OTEL)

**Definition**: Similar to histogram but calculates quantiles on the client side.

**Note**: OpenTelemetry **does not support Summary** metrics. Use **Histogram** instead, as server-side quantile calculation (in Prometheus) is more flexible and efficient.

---

### Metric Type Comparison Table

| Feature | Counter | UpDownCounter | Gauge | Histogram |
|---------|---------|---------------|-------|-----------|
| **Direction** | Up only | Up/Down | Up/Down | N/A |
| **Update Method** | Manual increment | Manual increment | Callback | Manual record |
| **Aggregation** | Sum | Sum | Last value | Buckets + Sum |
| **Reset on Restart** | Yes | Yes | No (recalculated) | Yes |
| **Percentiles** | No | No | No | Yes |
| **Memory Usage** | Low | Low | Low | Medium-High |
| **Best for** | Events | Current state | Sampled state | Distributions |

---

## Instrumentation Terminology

### Attributes (Labels)

**Definition**: Key-value pairs that provide additional context to metrics and traces.

**Example**:
```java
Attributes.of(
    stringKey("endpoint"), "/deploy",
    stringKey("method"), "POST",
    stringKey("status_code"), "200",
    stringKey("verticle.repo"), "micro-verticle-1"
)
```

**Cardinality Warning**: Attributes create **unique time series** combinations.
```
High cardinality = More memory usage in Prometheus

Examples:
✅ Good: endpoint, method, status_code (limited values)
❌ Bad: user_id, request_id, timestamp (unbounded values)
```

**Best Practices**:
- Keep attribute values bounded
- Use attribute values that repeat
- Avoid user-specific data in attributes
- Limit to 10-15 attributes per metric

---

### Instruments

**Definition**: The objects used to record measurements (counters, histograms, gauges).

```java
// Instrument creation
LongCounter counter = meter.counterBuilder("name").build();
DoubleHistogram histogram = meter.histogramBuilder("name").build();
```

---

### Meter

**Definition**: The factory for creating instruments, scoped to a library/component.

```java
Meter meter = openTelemetry
    .getMeterProvider()
    .meterBuilder("com.example.vertx")
    .setInstrumentationVersion("1.0.0")
    .build();
```

---

### Span

**Definition**: A single unit of work in a distributed trace, representing an operation.

**Span Anatomy**:
```
Span: http.request
├─ Span ID: 1a2b3c4d5e6f
├─ Trace ID: 9z8y7x6w5v4u (shared across all spans in trace)
├─ Parent Span ID: null (root span)
├─ Start Time: 2025-01-15T10:30:00.000Z
├─ End Time: 2025-01-15T10:30:00.234Z
├─ Duration: 234ms
├─ Status: OK
└─ Attributes:
   ├─ http.method: POST
   ├─ http.route: /deploy
   ├─ http.status_code: 200
   └─ user.id: user123
```

**Parent-Child Relationships**:
```
Trace ID: abc123
│
├─ Span: http.request (234ms)
│  │
│  ├─ Span: validate_request (5ms)
│  │
│  ├─ Span: verticle.deploy (200ms)
│  │  │
│  │  ├─ Span: load_jar (50ms)
│  │  ├─ Span: read_config (10ms)
│  │  └─ Span: instantiate_verticle (140ms)
│  │
│  └─ Span: send_response (2ms)
```

---

### Trace Context Propagation

**Definition**: Passing trace metadata across service boundaries.

**W3C Trace Context Headers**:
```http
traceparent: 00-abc123def456-1a2b3c4d-01
tracestate: vendor1=value1,vendor2=value2
```

**Format**: `version-trace_id-span_id-flags`

**Vert.x Context Propagation**:
```java
// Propagate context to event bus
Context context = Context.current();
vertx.eventBus().send(address, message, deliveryOptions, ar -> {
    try (Scope scope = context.makeCurrent()) {
        // This code runs with the propagated trace context
    }
});
```

---

### Resource

**Definition**: Immutable metadata about the entity producing telemetry (service, host, etc.).

```java
Resource resource = Resource.getDefault()
    .merge(Resource.create(Attributes.of(
        ResourceAttributes.SERVICE_NAME, "vertx-on-demand",
        ResourceAttributes.SERVICE_VERSION, "1.0.0",
        ResourceAttributes.SERVICE_NAMESPACE, "production",
        ResourceAttributes.HOST_NAME, "server-01",
        ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "production"
    )));
```

---

### Exporter

**Definition**: Component that sends telemetry data to a backend.

**Common Exporters**:
- **Prometheus**: Pull-based metrics (HTTP `/metrics` endpoint)
- **OTLP**: Push-based protocol (metrics, traces, logs)
- **Jaeger**: Distributed tracing
- **Zipkin**: Distributed tracing
- **Datadog, New Relic, Honeycomb**: Commercial APM platforms

---

### Processor

**Definition**: Pipeline component that transforms telemetry before export.

**Common Processors**:
- **BatchSpanProcessor**: Batches spans for efficient export
- **SimpleSpanProcessor**: Exports spans immediately (dev only)
- **FilteringSpanProcessor**: Filters spans by attributes

---

### Sampler

**Definition**: Determines which traces to record (sampling strategy).

**Sampling Strategies**:
```java
// Always sample (100%)
Sampler.alwaysOn()

// Never sample (0%)
Sampler.alwaysOff()

// Sample 10% of traces
Sampler.traceIdRatioBased(0.1)

// Parent-based sampling (follow parent decision)
Sampler.parentBased(Sampler.traceIdRatioBased(0.1))
```

**Why Sample?**
- Reduce storage costs
- Lower performance overhead
- Still maintain statistical accuracy

---

### Semantic Conventions

**Definition**: Standard naming and attribute conventions for interoperability.

**HTTP Semantic Conventions**:
```java
// Standard attribute keys
SemanticAttributes.HTTP_METHOD          // "GET", "POST"
SemanticAttributes.HTTP_STATUS_CODE     // 200, 404, 500
SemanticAttributes.HTTP_ROUTE           // "/deploy", "/:address"
SemanticAttributes.HTTP_REQUEST_BODY_SIZE
SemanticAttributes.HTTP_RESPONSE_BODY_SIZE
```

**Standard Metric Names**:
```
http.server.duration           # HTTP request duration
http.server.request.size       # Request body size
http.server.response.size      # Response body size
http.server.active_requests    # In-flight requests
```

**Benefits**:
- Tool compatibility
- Dashboard reusability
- Query portability

---

## Micrometer vs OpenTelemetry

### What is Micrometer?

**Micrometer** is a metrics facade/abstraction layer for Java applications, similar to SLF4J for logging.

```
┌─────────────────────────────────────────────────────────────┐
│                    Your Application                          │
└────────────────────┬────────────────────────────────────────┘
                     │
          ┌──────────┴──────────┐
          │                     │
    ┌─────▼──────┐      ┌───────▼──────┐
    │ Micrometer │      │ OpenTelemetry│
    │    API     │      │     API      │
    └─────┬──────┘      └───────┬──────┘
          │                     │
    ┌─────▼──────────────────────▼──────┐
    │ Prometheus, Datadog, InfluxDB,    │
    │ StatsD, New Relic, etc.           │
    └───────────────────────────────────┘
```

### Key Differences

| Feature | Micrometer | OpenTelemetry |
|---------|------------|---------------|
| **Scope** | Metrics only | Metrics + Traces + Logs |
| **Standard** | Spring Boot default | CNCF standard, vendor-neutral |
| **Adoption** | Java ecosystem | All languages (Java, Go, Python, etc.) |
| **Traces** | No | Yes (distributed tracing) |
| **Context Propagation** | Limited | Built-in W3C standard |
| **Industry Support** | Good | Excellent (industry standard) |
| **Spring Boot Integration** | Native | Requires bridge |

### Do You Need Micrometer with OTEL?

**Short Answer**: **No, you don't need Micrometer if you use OpenTelemetry.**

**Scenarios**:

#### ✅ Use OpenTelemetry Only (Recommended)
```java
// Direct OTEL usage
Meter meter = openTelemetry.getMeter("com.example");
LongCounter counter = meter.counterBuilder("requests").build();
counter.add(1);
```

**When to use**:
- New projects
- Need distributed tracing
- Want vendor neutrality
- Multi-language environment

#### ✅ Use Micrometer Only
```java
// Micrometer usage
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
Counter counter = registry.counter("requests");
counter.increment();
```

**When to use**:
- Spring Boot applications (Micrometer is default)
- Metrics-only requirements
- Already invested in Micrometer

#### ⚠️ Use Both (Bridge Pattern)
```xml
<!-- Micrometer OTEL Bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>
```

**When to use**:
- Migrating from Micrometer to OTEL
- Using Spring Boot actuator (produces Micrometer metrics)
- Need backward compatibility

### For Your Vert.x Project

**Recommendation**: **Use OpenTelemetry directly**

**Reasons**:
1. ✅ You're not using Spring Boot (no Micrometer by default)
2. ✅ You'll benefit from distributed tracing across verticles
3. ✅ OTEL is the industry standard for cloud-native apps
4. ✅ Better context propagation for async Vert.x operations
5. ✅ Single observability stack (metrics + traces + logs)

**Don't use Micrometer because**:
- ❌ Adds unnecessary dependency
- ❌ No tracing support
- ❌ Extra abstraction layer
- ❌ Less suitable for async frameworks like Vert.x

---

## JVM & System Metrics Deep Dive

### Why JVM Metrics Matter

**Critical for**:
- **Memory leak detection**: Spot growing heap usage
- **GC tuning**: Identify excessive garbage collection
- **Capacity planning**: Know when to scale
- **Performance troubleshooting**: Correlate app issues with JVM state

### 1. Memory Metrics

#### Heap Memory

```java
ObservableDoubleGauge heapUsed = meter
    .gaugeBuilder("jvm.memory.heap.used")
    .setDescription("Used heap memory")
    .setUnit("bytes")
    .buildWithCallback(measurement -> {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        measurement.record(heapUsage.getUsed());
    });

ObservableDoubleGauge heapCommitted = meter
    .gaugeBuilder("jvm.memory.heap.committed")
    .setDescription("Committed heap memory")
    .setUnit("bytes")
    .buildWithCallback(measurement -> {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        measurement.record(heapUsage.getCommitted());
    });

ObservableDoubleGauge heapMax = meter
    .gaugeBuilder("jvm.memory.heap.max")
    .setDescription("Maximum heap memory")
    .setUnit("bytes")
    .buildWithCallback(measurement -> {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        measurement.record(heapUsage.getMax());
    });
```

**Key Metrics**:
- `jvm.memory.heap.used` - Current heap usage
- `jvm.memory.heap.committed` - Memory committed by JVM
- `jvm.memory.heap.max` - Maximum heap size (-Xmx)
- `jvm.memory.heap.init` - Initial heap size (-Xms)

**Memory States**:
```
┌────────────────────────────────────────────────────────┐
│                    Max Heap (-Xmx)                     │
│  ┌──────────────────────────────────────────────┐     │
│  │         Committed (requested from OS)        │     │
│  │  ┌──────────────────────────────────┐        │     │
│  │  │      Used (actual objects)       │        │     │
│  │  │                                  │        │     │
│  │  └──────────────────────────────────┘        │     │
│  │                                               │     │
│  └──────────────────────────────────────────────┘     │
│                                                        │
└────────────────────────────────────────────────────────┘
```

**Alerting Rules**:
```promql
# Alert: Heap usage > 85% of max
(jvm_memory_heap_used / jvm_memory_heap_max) * 100 > 85

# Alert: Heap usage growing consistently
deriv(jvm_memory_heap_used[1h]) > 0
```

#### Non-Heap Memory (Metaspace)

```java
ObservableDoubleGauge metaspaceUsed = meter
    .gaugeBuilder("jvm.memory.metaspace.used")
    .setDescription("Used metaspace memory")
    .setUnit("bytes")
    .buildWithCallback(measurement -> {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace")) {
                measurement.record(pool.getUsage().getUsed());
            }
        }
    });
```

**Why Metaspace Matters**:
- Stores **class metadata**
- **Critical for classloader monitoring**
- Unbounded by default (can cause OutOfMemoryError)
- **For your app**: Each deployed verticle loads new classes!

**Metaspace Growth Pattern**:
```
Normal:
  Deploy Verticle → +5MB metaspace → Undeploy → GC reclaims

Leak:
  Deploy Verticle → +5MB → Undeploy → +5MB (not reclaimed!)
  After 20 deploys: +100MB metaspace never freed
```

#### Memory Pool Breakdown

```java
// Monitor all memory pools
for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
    String poolName = pool.getName();
    // G1: "G1 Eden Space", "G1 Old Gen", "G1 Survivor Space"
    // Metaspace: "Metaspace", "Compressed Class Space"

    meter.gaugeBuilder("jvm.memory.pool.used")
        .setUnit("bytes")
        .buildWithCallback(measurement -> {
            measurement.record(
                pool.getUsage().getUsed(),
                Attributes.of(stringKey("pool"), poolName)
            );
        });
}
```

**G1 Garbage Collector Pools**:
- **Eden Space**: New objects allocated here
- **Survivor Space**: Objects that survived one GC
- **Old Gen**: Long-lived objects
- **Metaspace**: Class metadata

### 2. Garbage Collection Metrics

```java
// GC pause time histogram
DoubleHistogram gcPauseTime = meter
    .histogramBuilder("jvm.gc.pause")
    .setDescription("Garbage collection pause time")
    .setUnit("ms")
    .build();

// GC execution counter
LongCounter gcExecutions = meter
    .counterBuilder("jvm.gc.executions.total")
    .setDescription("Total garbage collection executions")
    .build();

// Monitor GC events
for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
    String gcName = gcBean.getName(); // "G1 Young Generation", "G1 Old Generation"

    // Record GC count
    gcExecutions.add(
        gcBean.getCollectionCount(),
        Attributes.of(stringKey("gc"), gcName)
    );

    // Record GC time
    gcPauseTime.record(
        gcBean.getCollectionTime(),
        Attributes.of(stringKey("gc"), gcName)
    );
}
```

**GC Metrics**:
- `jvm.gc.pause` (histogram) - GC pause duration distribution
- `jvm.gc.executions.total` (counter) - Total GC runs
- `jvm.gc.memory.freed` (counter) - Memory reclaimed
- `jvm.gc.overhead` (gauge) - % of time spent in GC

**GC Performance Analysis**:
```promql
# GC frequency (collections per second)
rate(jvm_gc_executions_total[5m])

# GC overhead (% of time in GC)
rate(jvm_gc_pause_sum[5m]) /
rate(jvm_gc_pause_count[5m])

# Alert: GC taking > 10% of CPU time
rate(jvm_gc_pause_sum[5m]) > 0.1
```

**Ideal vs Problematic GC**:
```
✅ Healthy GC:
   - Young GC: < 50ms, frequent
   - Full GC: < 200ms, rare (< 1/hour)
   - GC overhead: < 5%

❌ Problematic GC:
   - Young GC: > 200ms
   - Full GC: > 1s, frequent (> 1/minute)
   - GC overhead: > 20%
   - Indicates: memory leak or undersized heap
```

### 3. Thread Metrics

```java
ObservableLongGauge threadCount = meter
    .gaugeBuilder("jvm.threads.count")
    .ofLongs()
    .setDescription("Current thread count")
    .setUnit("threads")
    .buildWithCallback(measurement -> {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // Total threads
        measurement.record(
            threadBean.getThreadCount(),
            Attributes.of(stringKey("state"), "all")
        );

        // Daemon threads
        measurement.record(
            threadBean.getDaemonThreadCount(),
            Attributes.of(stringKey("state"), "daemon")
        );

        // Peak threads
        measurement.record(
            threadBean.getPeakThreadCount(),
            Attributes.of(stringKey("state"), "peak")
        );
    });

// Thread states breakdown
ObservableLongGauge threadStates = meter
    .gaugeBuilder("jvm.threads.states")
    .ofLongs()
    .buildWithCallback(measurement -> {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);

        Map<Thread.State, Long> stateCounts = Arrays.stream(threadInfos)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(
                ThreadInfo::getThreadState,
                Collectors.counting()
            ));

        for (Map.Entry<Thread.State, Long> entry : stateCounts.entrySet()) {
            measurement.record(
                entry.getValue(),
                Attributes.of(stringKey("state"), entry.getKey().name())
            );
        }
    });
```

**Thread States**:
- `RUNNABLE` - Executing or ready to execute
- `BLOCKED` - Waiting for monitor lock
- `WAITING` - Waiting indefinitely for another thread
- `TIMED_WAITING` - Waiting for specified time
- `NEW` - Not yet started
- `TERMINATED` - Completed execution

**For Vert.x**:
```java
// Vert.x event loop threads
ObservableLongGauge eventLoopThreads = meter
    .gaugeBuilder("vertx.eventloop.threads")
    .ofLongs()
    .buildWithCallback(measurement -> {
        // Count threads with name pattern "vert.x-eventloop-thread-*"
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long eventLoopCount = Arrays.stream(threadBean.getAllThreadIds())
            .mapToObj(id -> threadBean.getThreadInfo(id))
            .filter(info -> info != null &&
                           info.getThreadName().contains("vert.x-eventloop-thread"))
            .count();

        measurement.record(eventLoopCount);
    });
```

**Thread Alerting**:
```promql
# Alert: Thread count growing unbounded
deriv(jvm_threads_count{state="all"}[30m]) > 0

# Alert: Too many blocked threads (> 10% of total)
(jvm_threads_states{state="BLOCKED"} /
 jvm_threads_count{state="all"}) > 0.1
```

### 4. CPU Metrics

```java
ObservableDoubleGauge processCpuUsage = meter
    .gaugeBuilder("process.cpu.usage")
    .setDescription("Process CPU usage")
    .setUnit("percent")
    .buildWithCallback(measurement -> {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean =
                (com.sun.management.OperatingSystemMXBean) osBean;

            double cpuUsage = sunOsBean.getProcessCpuLoad() * 100;
            measurement.record(cpuUsage);
        }
    });

ObservableDoubleGauge systemCpuUsage = meter
    .gaugeBuilder("system.cpu.usage")
    .setDescription("System CPU usage")
    .setUnit("percent")
    .buildWithCallback(measurement -> {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean =
                (com.sun.management.OperatingSystemMXBean) osBean;

            double cpuUsage = sunOsBean.getSystemCpuLoad() * 100;
            measurement.record(cpuUsage);
        }
    });
```

---

## Capacity & Scaling Metrics

### What is Capacity Planning?

**Definition**: Determining resource requirements to meet current and future demand.

**Questions Capacity Metrics Answer**:
1. When do we need to scale horizontally (add instances)?
2. When do we need to scale vertically (bigger machines)?
3. What are our resource utilization trends?
4. What is our headroom before saturation?

### 1. Request Throughput & Saturation

```java
// Requests per second (rate of counter)
LongCounter totalRequests = meter
    .counterBuilder("http.server.requests.total")
    .build();

// Active requests (concurrent in-flight)
LongUpDownCounter activeRequests = meter
    .upDownCounterBuilder("http.server.active_requests")
    .build();

// Request queue depth
ObservableLongGauge queueDepth = meter
    .gaugeBuilder("http.server.request_queue.depth")
    .ofLongs()
    .buildWithCallback(measurement -> {
        // Measure pending requests in event loop queue
        measurement.record(eventLoopQueueSize);
    });
```

**Saturation Analysis**:
```promql
# Current request rate (req/sec)
rate(http_server_requests_total[1m])

# Request rate trend (compare to historical)
rate(http_server_requests_total[1m]) /
rate(http_server_requests_total[1m] offset 1d)

# Active request utilization (% of max concurrency)
http_server_active_requests / on() group_left()
scalar(max_over_time(http_server_active_requests[7d]))

# Alert: Approaching max concurrency
http_server_active_requests > 800  # if max is 1000
```

**Capacity Planning Dashboard**:
```
┌─────────────────────────────────────────────────────────┐
│ Current RPS: 1,234 req/s                                │
│ Peak RPS (7d): 2,456 req/s                              │
│ Headroom: 50% (can handle 2x current load)              │
├─────────────────────────────────────────────────────────┤
│ Active Requests: 234 / 1000 (23% utilization)           │
│ Recommendation: No scaling needed                       │
└─────────────────────────────────────────────────────────┘
```

### 2. Response Time Degradation

```java
// Track latency percentiles over time
DoubleHistogram requestDuration = meter
    .histogramBuilder("http.server.duration")
    .setUnit("ms")
    .build();
```

**Latency-Based Scaling**:
```promql
# P99 latency exceeding SLO (500ms)
histogram_quantile(0.99,
  rate(http_server_duration_bucket[5m])
) > 500

# Latency degradation (compared to baseline)
histogram_quantile(0.95,
  rate(http_server_duration_bucket[5m])
) /
histogram_quantile(0.95,
  rate(http_server_duration_bucket[5m] offset 1d)
) > 1.5  # 50% slower than yesterday
```

**Latency SLIs (Service Level Indicators)**:
```
Target: P95 < 200ms, P99 < 500ms

Current: P95 = 450ms, P99 = 1200ms
Status: ❌ Degraded - Scale up needed
```

### 3. Memory Saturation

```java
// Heap utilization percentage
ObservableDoubleGauge heapUtilization = meter
    .gaugeBuilder("jvm.memory.heap.utilization")
    .setUnit("percent")
    .buildWithCallback(measurement -> {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        double utilization = (double) heapUsage.getUsed() /
                            heapUsage.getMax() * 100;
        measurement.record(utilization);
    });
```

**Memory-Based Scaling**:
```promql
# Alert: Heap usage sustained > 80%
avg_over_time(
  (jvm_memory_heap_used / jvm_memory_heap_max * 100)[10m:]
) > 80

# Alert: Heap growing > 50MB/hour
deriv(jvm_memory_heap_used[1h]) > 50 * 1024 * 1024
```

**Scaling Decision Matrix**:
```
Heap Usage | Action
-----------|---------------------------------------
< 60%      | ✅ Healthy
60-75%     | ⚠️  Monitor closely
75-85%     | 🔶 Increase heap (-Xmx) or scale horizontally
> 85%      | 🔴 Immediate action: scale or investigate leak
> 95%      | 🚨 CRITICAL: OOM imminent
```

### 4. CPU Saturation

```java
ObservableDoubleGauge cpuUtilization = meter
    .gaugeBuilder("process.cpu.utilization")
    .setUnit("percent")
    .buildWithCallback(measurement -> {
        OperatingSystemMXBean osBean =
            (com.sun.management.OperatingSystemMXBean)
            ManagementFactory.getOperatingSystemMXBean();

        double cpuUsage = osBean.getProcessCpuLoad() * 100;
        measurement.record(cpuUsage);
    });
```

**CPU-Based Scaling**:
```promql
# Alert: CPU sustained > 80%
avg_over_time(process_cpu_utilization[5m]) > 80

# CPU usage during peak hours
avg_over_time(
  process_cpu_utilization[1h]
) and hour() >= 9 and hour() <= 17
```

### 5. Verticle Deployment Capacity

```java
// Track deployed verticles vs maximum
ObservableLongGauge deployedVerticles = meter
    .gaugeBuilder("verticle.deployed.count")
    .ofLongs()
    .buildWithCallback(measurement -> {
        measurement.record(
            deployedVerticlesMap.size(),
            Attributes.of(stringKey("type"), "current")
        );

        // Track against configured maximum
        measurement.record(
            MAX_VERTICLES,
            Attributes.of(stringKey("type"), "max")
        );
    });

// Deployment rate (deploys per hour)
LongCounter deploymentCount = meter
    .counterBuilder("verticle.deployments.total")
    .build();
```

**Verticle Capacity Planning**:
```promql
# Current verticle utilization
verticle_deployed_count{type="current"} /
verticle_deployed_count{type="max"} * 100

# Deployment rate trend
rate(verticle_deployments_total[1h])

# Alert: Approaching verticle limit
verticle_deployed_count{type="current"} >
verticle_deployed_count{type="max"} * 0.9
```

### 6. Event Loop Lag (Vert.x Specific)

```java
// Measure event loop blocked time
DoubleHistogram eventLoopLag = meter
    .histogramBuilder("vertx.eventloop.lag")
    .setDescription("Event loop lag time")
    .setUnit("ms")
    .build();

// Integrate with Vert.x metrics
VertxOptions options = new VertxOptions()
    .setMetricsOptions(new MetricsOptions()
        .setEnabled(true));
```

**Event Loop Saturation**:
```promql
# Alert: Event loop lag > 100ms
histogram_quantile(0.99,
  rate(vertx_eventloop_lag_bucket[5m])
) > 100

# Indicates: Blocking operations on event loop
```

### 7. Network I/O Capacity

```java
// Track bytes sent/received
LongCounter bytesSent = meter
    .counterBuilder("http.server.bytes_sent.total")
    .setUnit("bytes")
    .build();

LongCounter bytesReceived = meter
    .counterBuilder("http.server.bytes_received.total")
    .setUnit("bytes")
    .build();
```

**Network Saturation**:
```promql
# Network throughput (MB/s)
rate(http_server_bytes_sent_total[1m]) / 1024 / 1024

# Alert: Approaching bandwidth limit (900 Mbps on 1 Gbps link)
rate(http_server_bytes_sent_total[1m]) * 8 > 900 * 1000 * 1000
```

### Capacity Planning Workflow

```
┌─────────────────────────────────────────────────────────────┐
│                   CAPACITY PLANNING CYCLE                    │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  1. MEASURE CURRENT STATE                                    │
│     ├─ Request rate: 1,500 req/s                            │
│     ├─ CPU: 45%                                              │
│     ├─ Memory: 60%                                           │
│     └─ P99 latency: 180ms                                    │
│                                                               │
│  2. IDENTIFY BOTTLENECK                                      │
│     └─ CPU is limiting factor (45% at 1,500 req/s)          │
│                                                               │
│  3. CALCULATE HEADROOM                                       │
│     └─ Can handle 3,333 req/s before CPU hits 100%          │
│                                                               │
│  4. FORECAST GROWTH                                          │
│     └─ Traffic growing 20% per month                         │
│                                                               │
│  5. SCALING DECISION                                         │
│     ├─ Current: 1,500 req/s                                  │
│     ├─ +1 month: 1,800 req/s (54% CPU) ✅                   │
│     ├─ +2 months: 2,160 req/s (65% CPU) ⚠️                  │
│     └─ Action: Add 1 instance in 2 months                    │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

---

## Classloader Leak Detection

### Why Classloaders are Critical for Your App

Your Vert.x on-demand server **dynamically loads JARs** using `URLClassLoader`. Each deployment creates a new classloader that loads verticle classes.

**Memory Leak Risk**:
```
Deploy verticle → New URLClassLoader → Load classes → Deploy to Vert.x
Undeploy verticle → Close classloader → ❌ Classes NOT GC'd if references exist
```

**Leaked References Can Come From**:
- Thread locals holding class references
- Static fields in deployed classes
- Event bus registrations not cleaned up
- Vert.x timers not cancelled
- Thread pools not shut down

### Classloader Metrics

```java
// Count active classloaders
ObservableLongGauge classloaderCount = meter
    .gaugeBuilder("jvm.classloader.count")
    .ofLongs()
    .setDescription("Number of active classloaders")
    .buildWithCallback(measurement -> {
        ClassLoadingMXBean classLoadingBean =
            ManagementFactory.getClassLoadingMXBean();

        // Total loaded classloaders
        measurement.record(
            classLoadingBean.getLoadedClassCount(),
            Attributes.of(stringKey("type"), "loaded")
        );

        // Total classes loaded over lifetime
        measurement.record(
            classLoadingBean.getTotalLoadedClassCount(),
            Attributes.of(stringKey("type"), "total")
        );

        // Classes unloaded (GC'd classloaders)
        measurement.record(
            classLoadingBean.getUnloadedClassCount(),
            Attributes.of(stringKey("type"), "unloaded")
        );
    });

// Track custom URLClassLoaders
ObservableLongGauge urlClassloaderCount = meter
    .gaugeBuilder("classloader.url.count")
    .ofLongs()
    .setDescription("Number of URLClassLoader instances")
    .buildWithCallback(measurement -> {
        // In DeploymentHandler, track classloaders
        measurement.record(classLoaderMap.size());
    });

// Classes per verticle deployment
LongCounter classesLoaded = meter
    .counterBuilder("verticle.classes_loaded.total")
    .setDescription("Total classes loaded by deployed verticles")
    .build();
```

### Detecting Classloader Leaks

#### Pattern 1: Growing Classloader Count

```promql
# Classloaders should be stable or decreasing after undeploy
# Alert: Classloaders growing unbounded
deriv(jvm_classloader_count{type="loaded"}[30m]) > 0

# Ratio of unloaded to total (should be high if cleaning up properly)
jvm_classloader_count{type="unloaded"} /
jvm_classloader_count{type="total"} < 0.5  # Less than 50% cleanup
```

**Healthy Pattern**:
```
Time: 00:00 | Classloaders: 50
Deploy verticle → +5 classloaders → 55
Undeploy verticle → GC runs → 50 (back to baseline) ✅
```

**Leak Pattern**:
```
Time: 00:00 | Classloaders: 50
Deploy verticle → +5 → 55
Undeploy verticle → 55 (NOT reclaimed!) ❌
Deploy again → +5 → 60
Undeploy → 60 (growing unbounded) 🚨
```

#### Pattern 2: Metaspace Growth Correlation

```promql
# Metaspace should decrease after undeployment
# Alert: Metaspace growing faster than heap
deriv(jvm_memory_metaspace_used[1h]) >
deriv(jvm_memory_heap_used[1h])

# Metaspace per classloader (should be constant)
jvm_memory_metaspace_used / jvm_classloader_count{type="loaded"}
```

**Expected Metaspace Pattern**:
```
No deployments: 30 MB metaspace
1 verticle deployed: 35 MB (+5 MB)
2 verticles deployed: 40 MB (+5 MB each)
Undeploy all: 30 MB (returns to baseline after GC) ✅
```

**Leak Pattern**:
```
No deployments: 30 MB
Deploy/undeploy 10 times: 80 MB (leaked 50 MB!) ❌
```

#### Pattern 3: Deployment Lifecycle Correlation

```java
// Instrument deployment lifecycle
DoubleHistogram deploymentDuration = meter
    .histogramBuilder("verticle.deployment.duration")
    .setUnit("ms")
    .build();

// Record class count at deployment time
void deployVerticle(String repo) {
    long startClasses = classLoadingBean.getLoadedClassCount();
    long startTime = System.currentTimeMillis();

    try {
        // ... deployment logic ...

        long endClasses = classLoadingBean.getLoadedClassCount();
        long classesLoaded = endClasses - startClasses;

        // Record classes loaded for this deployment
        classesLoadedPerVerticle.record(
            classesLoaded,
            Attributes.of(stringKey("repo"), repo)
        );

    } finally {
        deploymentDuration.record(
            System.currentTimeMillis() - startTime,
            Attributes.of(stringKey("repo"), repo)
        );
    }
}
```

**Dashboard: Classloader Leak Detection**

```
┌─────────────────────────────────────────────────────────────┐
│               CLASSLOADER HEALTH DASHBOARD                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Active Classloaders: 67  (⚠️ +17 in last hour)             │
│  Metaspace Usage: 95 MB   (⚠️ +30 MB in last hour)          │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Classloader Count Over Time                           │  │
│  │                                          ╱─────────    │  │
│  │                                    ╱────╱              │  │
│  │                              ╱────╱                    │  │
│  │                        ╱────╱                          │  │
│  │ 50 ─────────────────────────                          │  │
│  └───────────────────────────────────────────────────────┘  │
│       🔴 LEAK DETECTED: Classloaders not being GC'd          │
│                                                               │
│  Deployments vs Classloaders:                                │
│  ├─ Total deployments: 45                                    │
│  ├─ Total undeployments: 42                                  │
│  ├─ Expected classloaders: 53 (baseline 50 + 3 active)       │
│  └─ Actual classloaders: 67 (❌ 14 leaked!)                  │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### Preventing Classloader Leaks in Vert.x

#### Best Practice 1: Proper Cleanup in VerticleLifecycle

```java
public interface VerticleLifecycle<T> {
    Future<T> handleRequest(String body);

    // Add cleanup method
    default Future<Void> cleanup() {
        // Override in implementations to:
        // - Cancel timers
        // - Close connections
        // - Unregister event bus consumers
        // - Clear thread locals
        return Future.succeededFuture();
    }
}

// Call cleanup before undeploying
void undeployVerticle(String address) {
    VerticleLifecycle<?> verticle = deployedVerticles.get(address);

    verticle.cleanup()
        .compose(v -> vertx.undeploy(deploymentId))
        .compose(v -> {
            // Close and nullify classloader
            URLClassLoader classLoader = classLoaders.remove(address);
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException e) {
                    // log error
                }
            }
            return Future.succeededFuture();
        });
}
```

#### Best Practice 2: Monitor Thread Locals

```java
// Detect thread local leaks
ObservableLongGauge threadLocalCount = meter
    .gaugeBuilder("jvm.threadlocals.count")
    .ofLongs()
    .buildWithCallback(measurement -> {
        // Reflectively count ThreadLocal instances
        // (advanced, requires JVM internal access)
        long count = countThreadLocals();
        measurement.record(count);
    });
```

#### Best Practice 3: Weak References for Caches

```java
// If verticles use caching, use WeakHashMap
private WeakHashMap<String, Object> cache = new WeakHashMap<>();

// WeakHashMap allows GC to reclaim entries when keys are no longer referenced
```

#### Best Practice 4: Explicit Resource Tracking

```java
public class ResourceTracker {
    private final Set<AutoCloseable> resources = ConcurrentHashMap.newKeySet();

    public <T extends AutoCloseable> T register(T resource) {
        resources.add(resource);
        return resource;
    }

    public void cleanup() {
        resources.forEach(resource -> {
            try {
                resource.close();
            } catch (Exception e) {
                // log
            }
        });
        resources.clear();
    }
}

// In verticle deployment
ResourceTracker tracker = new ResourceTracker();
tracker.register(connection);
tracker.register(timer);

// In undeployment
tracker.cleanup();
```

### Classloader Leak Testing

```java
@Test
void testClassloaderCleanup() throws Exception {
    // Deploy verticle
    deployVerticle("test-verticle");
    int classloadersBefore = classLoaderMap.size();

    // Undeploy
    undeployAllVerticles();

    // Force GC
    System.gc();
    Thread.sleep(1000);

    // Verify classloaders were removed
    int classloadersAfter = classLoaderMap.size();
    assertEquals(0, classloadersAfter);

    // Verify classes were unloaded (check metaspace)
    long metaspaceAfter = getMetaspaceUsage();
    assertTrue(metaspaceAfter < metaspaceBefore + 1024 * 1024); // Allow 1MB margin
}
```

---

## Best Practices

### 1. Metric Naming Conventions

**Follow OpenTelemetry Semantic Conventions**:
```
Format: <namespace>.<component>.<metric>

Examples:
✅ http.server.duration
✅ http.server.request.size
✅ jvm.memory.heap.used
✅ verticle.deployment.total

❌ request_duration (missing namespace)
❌ serverHttpDuration (inconsistent format)
```

**Unit Suffixes**:
```
Duration: .duration (in milliseconds by default)
Size: .size (in bytes by default)
Count: .count or no suffix
Rate: .rate (per second)
```

### 2. Cardinality Management

**Low Cardinality (Good)**:
```java
// Endpoint attribute with bounded values
Attributes.of(
    stringKey("endpoint"), "/deploy"  // Limited to 3 values
)
```

**High Cardinality (Bad)**:
```java
// User ID creates unique time series per user
Attributes.of(
    stringKey("user_id"), "user12345"  // Unbounded!
)
```

**Cardinality Formula**:
```
Total time series =
  attribute1_values × attribute2_values × ... × attributeN_values

Example:
  endpoints (3) × methods (1) × status_codes (5) = 15 time series ✅

  endpoints (3) × user_ids (1M) × status_codes (5) = 15M time series ❌
```

### 3. Sampling Strategies

**Always Sample** (Development):
```java
Sampler.alwaysOn()
```

**Ratio-Based** (Production - Cost Optimization):
```java
// Sample 10% of traces
Sampler.traceIdRatioBased(0.1)
```

**Tail-Based** (Advanced):
```
Sample 100% of:
  - Errors (status >= 400)
  - Slow requests (duration > 1s)

Sample 1% of:
  - Successful fast requests
```

### 4. Attribute Best Practices

**Do**:
- Use semantic conventions when available
- Keep attribute values bounded
- Use consistent naming (snake_case)
- Include units in description

**Don't**:
- Store PII (personally identifiable information)
- Use high-cardinality values (user IDs, timestamps)
- Duplicate data already in metric name
- Use attributes for large values (use logs instead)

### 5. Metric vs Trace Decision

| Use Metric When | Use Trace When |
|-----------------|----------------|
| Aggregated data needed | Need request-level detail |
| Alerting required | Debugging performance |
| Dashboard visualization | Understanding dependencies |
| SLI/SLO tracking | Root cause analysis |
| Capacity planning | Latency breakdown |

**Example**:
- **Metric**: "HTTP requests per second" (aggregate)
- **Trace**: "Why did this specific request take 5 seconds?" (detail)

---

## Implementation Patterns

### Pattern 1: Metrics Facade

```java
public class VerticleMetrics {
    private final Meter meter;
    private final LongCounter deployments;
    private final DoubleHistogram deploymentDuration;
    private final ObservableLongGauge activeVerticles;

    public VerticleMetrics(OpenTelemetry openTelemetry) {
        this.meter = openTelemetry.getMeter("com.example.vertx");

        this.deployments = meter
            .counterBuilder("verticle.deployments.total")
            .build();

        this.deploymentDuration = meter
            .histogramBuilder("verticle.deployment.duration")
            .setUnit("ms")
            .build();

        this.activeVerticles = meter
            .gaugeBuilder("verticle.active.count")
            .ofLongs()
            .buildWithCallback(this::recordActiveVerticles);
    }

    public void recordDeployment(String repo, boolean success, long durationMs) {
        deployments.add(1, Attributes.of(
            stringKey("repo"), repo,
            stringKey("status"), success ? "success" : "failure"
        ));

        deploymentDuration.record(durationMs, Attributes.of(
            stringKey("repo"), repo
        ));
    }

    private void recordActiveVerticles(ObservableLongMeasurement measurement) {
        measurement.record(deployedVerticlesMap.size());
    }
}
```

### Pattern 2: Automatic Context Propagation

```java
// Wrap Vert.x handlers with tracing
public class TracingHandler implements Handler<RoutingContext> {
    private final Handler<RoutingContext> delegate;
    private final Tracer tracer;

    @Override
    public void handle(RoutingContext ctx) {
        Span span = tracer.spanBuilder("http.request")
            .setSpanKind(SpanKind.SERVER)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute(SemanticAttributes.HTTP_METHOD, ctx.request().method().name());
            span.setAttribute(SemanticAttributes.HTTP_ROUTE, ctx.request().path());

            delegate.handle(ctx);

            ctx.addEndHandler(ar -> {
                span.setAttribute(SemanticAttributes.HTTP_STATUS_CODE, ctx.response().getStatusCode());
                span.end();
            });
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        }
    }
}
```

### Pattern 3: Graceful Degradation

```java
// Don't fail requests if metrics fail
public void recordMetric() {
    try {
        counter.add(1);
    } catch (Exception e) {
        // Log but don't propagate
        logger.warn("Failed to record metric", e);
    }
}
```

---

## Summary

### Key Takeaways

1. **Metric Types**:
   - **Counter**: Events (requests, errors)
   - **Gauge**: Snapshots (memory, threads)
   - **Histogram**: Distributions (latency, size)

2. **JVM Metrics are Critical**:
   - Monitor heap, metaspace, GC, threads, CPU
   - Detect memory leaks early
   - Enable proactive capacity planning

3. **Classloader Leaks are Dangerous**:
   - Track classloader count and metaspace
   - Implement proper cleanup in verticle lifecycle
   - Test undeployment thoroughly

4. **OpenTelemetry > Micrometer for Vert.x**:
   - Better async support
   - Distributed tracing included
   - Industry standard

5. **Capacity Planning Requires**:
   - Throughput metrics (req/s)
   - Latency percentiles (P95, P99)
   - Resource utilization (CPU, memory)
   - Growth rate tracking

### Next Steps

1. Add OpenTelemetry dependencies
2. Initialize SDK with exporters
3. Instrument HTTP layer
4. Add JVM metrics
5. Add classloader tracking
6. Create Grafana dashboards
7. Set up alerts
