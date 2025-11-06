# OpenTelemetry Design for Vert.x On-Demand Server

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [Why OpenTelemetry?](#why-opentelemetry)
3. [Architecture Overview](#architecture-overview)
4. [Metrics Design](#metrics-design)
5. [Distributed Tracing Design](#distributed-tracing-design)
6. [JVM & Capacity Planning Focus](#jvm--capacity-planning-focus)
7. [Classloader Leak Detection Strategy](#classloader-leak-detection-strategy)
8. [Implementation Plan](#implementation-plan)
9. [Dashboards & Alerts](#dashboards--alerts)
10. [Dependencies](#dependencies)

---

## Executive Summary

This document outlines the OpenTelemetry instrumentation strategy for the Vert.x on-demand verticle deployment server.

**Key Focus Areas**:
1. 🎯 **Capacity Planning** - Data-driven scaling decisions
2. 🧠 **JVM Health** - Deep memory, GC, and thread monitoring
3. 🔍 **Classloader Leak Detection** - Critical for dynamic JAR loading
4. 📊 **Request Performance** - Latency and throughput tracking
5. 🔄 **Deployment Lifecycle** - Verticle deployment observability

**Expected Outcomes**:
- Detect memory leaks before production incidents
- Scale proactively based on capacity metrics
- Reduce MTTR (Mean Time To Recovery) by 80% with distributed tracing
- Achieve 99.9% uptime through proactive alerting

---

## Why OpenTelemetry?

### The Problem

**Without Observability**:
```
Production Issue: "The server is slow"

Questions:
❓ Which endpoint is slow?
❓ Is it a memory leak or high CPU?
❓ Is it one verticle or all of them?
❓ When did it start degrading?
❓ Do we need to scale or optimize?

Result: 2+ hours debugging, restarting server blindly
```

**With OpenTelemetry**:
```
Alert: "P95 latency for /deploy exceeds 500ms"

Dashboard shows:
✅ /deploy endpoint degraded (others normal)
✅ Heap usage at 92% (memory leak suspected)
✅ Classloader count increased from 50 to 127 (leak confirmed!)
✅ Started 2 hours ago after micro-verticle-3 deployment
✅ Metaspace growing 10MB per deployment cycle

Result: 5 minutes to identify root cause, targeted fix deployed
```

### What OpenTelemetry Solves

| Problem | OTEL Solution | Value |
|---------|---------------|-------|
| **Memory Leaks** | JVM + classloader metrics | Detect leaks in minutes, not days |
| **Performance Degradation** | Latency histograms + tracing | Identify bottlenecks with P99 precision |
| **Capacity Planning** | Resource utilization trends | Scale proactively, avoid outages |
| **Error Storms** | Error rate counters + alerts | Detect issues before users complain |
| **Deployment Failures** | Lifecycle metrics | Track deployment success rates |
| **Vendor Lock-in** | Vendor-neutral standard | Switch backends without code changes |

### Why Not Micrometer?

**Short Answer**: OpenTelemetry provides **tracing + metrics** while Micrometer only handles metrics.

For your Vert.x application with dynamic classloading and async operations:

| Feature | Micrometer | OpenTelemetry |
|---------|------------|---------------|
| Metrics | ✅ | ✅ |
| Distributed Tracing | ❌ | ✅ |
| Async Context Propagation | Limited | Excellent |
| Classloader Tracking | Manual | Built-in JVM metrics |
| Vert.x Integration | Community-driven | Official instrumentation |
| Industry Standard | Java-specific | CNCF standard (all languages) |

**Recommendation**: Use **OpenTelemetry only** for cleaner architecture and better async support.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Vert.x HTTP Server :8080                        │
│                                                                        │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │POST /deploy │  │POST /undeploy│  │ POST /:addr  │  │GET /health│ │
│  └─────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───────────┘ │
│        │                 │                  │                         │
│        └─────────────────┴──────────────────┘                         │
│                          │                                            │
│              ┌───────────▼───────────┐                                │
│              │ OpenTelemetry Tracing │                                │
│              │    HTTP Middleware    │                                │
│              │  (Auto-instrumentation)│                               │
│              └───────────┬───────────┘                                │
│                          │                                            │
│         ┌────────────────┼────────────────┐                           │
│         │                │                │                           │
│    ┌────▼─────┐   ┌──────▼──────┐   ┌────▼────┐                     │
│    │  Deploy  │   │  Undeploy   │   │  Route  │                     │
│    │ Handler  │   │  Handler    │   │   to    │                     │
│    │          │   │             │   │ Verticle│                     │
│    └────┬─────┘   └──────┬──────┘   └────┬────┘                     │
│         │                │               │                           │
│    [Metrics]        [Metrics]       [Metrics]                        │
│    • Duration        • Count         • Validation                    │
│    • Success         • Classloaders  • Handler perf                  │
│    • Class count       freed         • Size checks                   │
│    • Metaspace                                                        │
│         │                │               │                           │
└─────────┼────────────────┼───────────────┼───────────────────────────┘
          │                │               │
          └────────────────┴───────────────┘
                           │
          ┌────────────────▼────────────────┐
          │    OpenTelemetry SDK Core       │
          │                                  │
          │  ┌────────────────────────────┐ │
          │  │    Metrics Pipeline        │ │
          │  │  • Counters                │ │
          │  │  • Histograms              │ │
          │  │  • Gauges                  │ │
          │  │  • Aggregation             │ │
          │  └─────────────┬──────────────┘ │
          │                │                 │
          │  ┌─────────────▼──────────────┐ │
          │  │    Traces Pipeline         │ │
          │  │  • Span creation           │ │
          │  │  • Context propagation     │ │
          │  │  • Parent-child relations  │ │
          │  │  • Sampling (10% in prod)  │ │
          │  └─────────────┬──────────────┘ │
          │                │                 │
          │  ┌─────────────▼──────────────┐ │
          │  │    Resource Attributes     │ │
          │  │  • service.name            │ │
          │  │  • service.version         │ │
          │  │  • deployment.environment  │ │
          │  └─────────────┬──────────────┘ │
          │                │                 │
          └────────────────┼─────────────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
    ┌─────────▼────────┐    ┌───────────▼─────────┐
    │  Prometheus      │    │   OTLP Exporter     │
    │  Exporter        │    │   (gRPC/HTTP)       │
    │                  │    │                     │
    │  GET /metrics    │    │   → Jaeger          │
    │  Port: 9090      │    │   → Tempo           │
    └─────────┬────────┘    │   → Honeycomb       │
              │             └───────────┬─────────┘
              │                         │
    ┌─────────▼────────┐    ┌───────────▼─────────┐
    │   Prometheus     │    │    Jaeger UI        │
    │   Server         │    │  (Distributed       │
    │   (Scrapes       │    │   Tracing)          │
    │    every 15s)    │    │                     │
    └─────────┬────────┘    └─────────────────────┘
              │
    ┌─────────▼────────┐
    │   Grafana        │
    │   Dashboards:    │
    │   • Capacity     │
    │   • JVM Health   │
    │   • Classloaders │
    │   • Deployments  │
    │   • Requests     │
    └──────────────────┘

          ┌─────────────────────────────────────┐
          │    Prometheus Alertmanager          │
          │                                     │
          │  Alerts:                            │
          │  • Heap > 85%                       │
          │  • Classloader leak detected        │
          │  • P99 latency > 1s                 │
          │  • Deployment failures > 5%         │
          │  • GC overhead > 20%                │
          └──────────────┬──────────────────────┘
                         │
          ┌──────────────▼──────────────────────┐
          │   Notification Channels             │
          │   • Slack / PagerDuty / Email       │
          └─────────────────────────────────────┘
```

---

## Metrics Design

### Metrics Hierarchy

```
Level 1: Infrastructure (JVM, System)
  ├─ Memory (Heap, Metaspace, GC)
  ├─ CPU (Process, System)
  ├─ Threads (Count, States, Event Loop)
  └─ Classloaders (Count, Leaks)

Level 2: Application (Vert.x Server)
  ├─ HTTP Requests (Rate, Duration, Size)
  ├─ Error Rates (4xx, 5xx)
  └─ Capacity (Throughput, Saturation)

Level 3: Business Logic (Verticle Lifecycle)
  ├─ Deployments (Success, Failures, Duration)
  ├─ Active Verticles (Count, Distribution)
  └─ Handler Performance (Per-verticle latency)
```

### 1. HTTP Request Metrics

#### 1.1 Request Counter

```yaml
Metric: http.server.requests.total
Type: COUNTER
Unit: requests
Description: Total number of HTTP requests processed
Labels:
  - endpoint: /deploy | /undeploy | /:address
  - method: POST | GET
  - status_code: 200 | 400 | 404 | 413 | 500
  - content_type: application/json | application/octet-stream | none

Use Cases:
  - Request rate: rate(http_server_requests_total[5m])
  - Error rate: rate(http_server_requests_total{status_code=~"5.."}[5m])
  - Success rate: rate(http_server_requests_total{status_code="200"}[5m])
```

#### 1.2 Request Duration Histogram

```yaml
Metric: http.server.duration
Type: HISTOGRAM
Unit: milliseconds
Description: HTTP request processing duration
Labels:
  - endpoint: /deploy | /undeploy | /:address
  - method: POST | GET
  - status_code: 200 | 400 | 500
Buckets: [10, 50, 100, 250, 500, 1000, 2500, 5000, 10000]

Use Cases:
  - P95 latency: histogram_quantile(0.95, rate(http_server_duration_bucket[5m]))
  - P99 latency: histogram_quantile(0.99, rate(http_server_duration_bucket[5m]))
  - Avg latency: rate(http_server_duration_sum[5m]) / rate(http_server_duration_count[5m])
  - SLO compliance: % of requests < 500ms
```

#### 1.3 Active Requests Gauge

```yaml
Metric: http.server.active_requests
Type: UP_DOWN_COUNTER
Unit: requests
Description: Number of in-flight HTTP requests
Labels:
  - endpoint: /deploy | /undeploy | /:address

Use Cases:
  - Concurrency monitoring
  - Saturation detection
  - Load balancing decisions
```

#### 1.4 Request/Response Size

```yaml
Metric: http.server.request.size
Type: HISTOGRAM
Unit: bytes
Description: HTTP request body size
Labels:
  - endpoint: /:address
  - content_type: application/octet-stream
Buckets: [1024, 10240, 51200, 102400, 512000, 1048576]

Metric: http.server.response.size
Type: HISTOGRAM
Unit: bytes
Description: HTTP response body size
Buckets: [100, 1024, 10240, 102400, 1048576]

Use Cases:
  - Network bandwidth planning
  - Payload size optimization
  - Protobuf compression effectiveness
```

### 2. Deployment Lifecycle Metrics

#### 2.1 Deployment Counter

```yaml
Metric: verticle.deployment.total
Type: COUNTER
Unit: deployments
Description: Total verticle deployment attempts
Labels:
  - repo: micro-verticle-1 | micro-verticle-2 | ...
  - status: success | failure
  - failure_reason: classloader_error | config_not_found | verticle_init_error | deploy_timeout

Use Cases:
  - Deployment success rate: rate(verticle_deployment_total{status="success"}[1h]) / rate(verticle_deployment_total[1h])
  - Failure breakdown by reason
  - Deployment frequency trends
```

#### 2.2 Deployment Duration

```yaml
Metric: verticle.deployment.duration
Type: HISTOGRAM
Unit: milliseconds
Description: Time taken to deploy a verticle
Labels:
  - repo: micro-verticle-1 | ...
  - status: success | failure
Buckets: [100, 500, 1000, 2000, 5000, 10000]

Use Cases:
  - Deployment performance tracking
  - Identify slow-loading JARs
  - Capacity planning for deployment pipeline
```

#### 2.3 Active Verticles Gauge

```yaml
Metric: verticle.active.count
Type: GAUGE
Unit: verticles
Description: Number of currently deployed verticles
Labels:
  - repo: micro-verticle-1 | all (aggregated)

Use Cases:
  - Capacity utilization (current / max)
  - Deployment vs undeploy balance verification
  - Leak detection (should decrease to zero after full undeploy)
```

#### 2.4 Undeployment Counter

```yaml
Metric: verticle.undeployment.total
Type: COUNTER
Unit: undeployments
Description: Total verticle undeployment operations
Labels:
  - count: number of verticles undeployed
  - duration_ms: time taken to undeploy all

Use Cases:
  - Verify undeployments are completing
  - Track cleanup operations
```

### 3. Request Validation Metrics

```yaml
Metric: request.validation.failures.total
Type: COUNTER
Unit: failures
Description: Request validation failures
Labels:
  - reason: payload_too_large | missing_body | invalid_content_type
  - endpoint: /:address

Use Cases:
  - Monitor malicious or malformed requests
  - Identify clients sending oversized payloads
  - Alert on validation attack patterns
```

### 4. Verticle Handler Performance

```yaml
Metric: verticle.handler.duration
Type: HISTOGRAM
Unit: milliseconds
Description: Individual verticle handler execution time
Labels:
  - address: the verticle address/name
  - status: success | failure
Buckets: [10, 50, 100, 250, 500, 1000, 2500, 5000]

Metric: verticle.handler.errors.total
Type: COUNTER
Unit: errors
Description: Handler execution failures
Labels:
  - address: verticle name
  - error_type: exception class name

Use Cases:
  - Identify slow verticles
  - Per-verticle performance SLIs
  - Anomaly detection (sudden handler slowdown)
```

---

## JVM & Capacity Planning Focus

### Why JVM Metrics are Critical

**Your application's unique risks**:
1. **Dynamic classloading** = Memory leak risk
2. **Protobuf serialization** = GC pressure
3. **Long-lived server** = Heap growth over time
4. **Concurrent deployments** = Thread pool exhaustion

### JVM Metrics Suite

#### 1. Heap Memory (Primary Capacity Indicator)

```yaml
Metric: jvm.memory.heap.used
Type: GAUGE
Unit: bytes
Description: Currently used heap memory
Labels:
  - pool: heap

Metric: jvm.memory.heap.committed
Type: GAUGE
Unit: bytes
Description: Heap memory committed by JVM

Metric: jvm.memory.heap.max
Type: GAUGE
Unit: bytes
Description: Maximum heap memory (-Xmx)

Derived Metric: jvm.memory.heap.utilization
Formula: (used / max) * 100
Unit: percent

Capacity Planning Queries:
  # Current heap usage percentage
  (jvm_memory_heap_used / jvm_memory_heap_max) * 100

  # Heap growth rate (MB per hour)
  deriv(jvm_memory_heap_used[1h]) / 1024 / 1024

  # Time until heap exhaustion (linear projection)
  (jvm_memory_heap_max - jvm_memory_heap_used) /
  deriv(jvm_memory_heap_used[1h]) / 3600

  # Example: 2GB max, 1.5GB used, growing 100MB/hour
  # → 5 hours until OOM
```

**Capacity Planning Dashboard**:
```
┌─────────────────────────────────────────────────────────────┐
│                    HEAP CAPACITY ANALYSIS                    │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Current Usage: 1.5 GB / 2.0 GB (75%)                        │
│  Growth Rate: +120 MB/hour                                   │
│  Time to 85% threshold: 2.5 hours  ⚠️                        │
│  Time to OOM: 4.2 hours  🚨                                  │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Heap Usage Trend (Last 24h)                           │  │
│  │                                                        │  │
│  │ 2.0GB ┼─────────────────────────────────────────  Max │  │
│  │       │                                    ╱───        │  │
│  │ 1.5GB ┼──────────────────────────────────╱    85%     │  │
│  │       │                        ╱────────╱              │  │
│  │ 1.0GB ┼───────────────────────╱                       │  │
│  │       │              ╱───────╱                         │  │
│  │ 0.5GB ┼─────────────╱                                 │  │
│  │       │   ╱────────╱                                  │  │
│  │ 0.0GB ┼──╱                                            │  │
│  │       └───┬────┬────┬────┬────┬────┬────┬────┬────   │  │
│  │          00h  04h  08h  12h  16h  20h  24h            │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
│  📊 RECOMMENDATION: Scale vertically (increase -Xmx) OR      │
│                     investigate memory leak                  │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

**Alerting Rules**:
```yaml
# Critical: Heap usage > 90%
- alert: HeapMemoryCritical
  expr: (jvm_memory_heap_used / jvm_memory_heap_max) * 100 > 90
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "JVM heap memory critically high"
    description: "Heap usage at {{ $value }}%, OOM risk imminent"

# Warning: Heap usage > 80% sustained
- alert: HeapMemoryHigh
  expr: (jvm_memory_heap_used / jvm_memory_heap_max) * 100 > 80
  for: 15m
  labels:
    severity: warning
  annotations:
    summary: "JVM heap memory elevated"

# Memory leak detection: Heap growing consistently
- alert: HeapMemoryLeak
  expr: deriv(jvm_memory_heap_used[1h]) > 0
  for: 6h
  labels:
    severity: warning
  annotations:
    summary: "Potential memory leak detected"
    description: "Heap has grown continuously for 6+ hours"
```

#### 2. Metaspace (Classloader Leak Indicator)

```yaml
Metric: jvm.memory.metaspace.used
Type: GAUGE
Unit: bytes
Description: Metaspace memory used (class metadata)

Metric: jvm.memory.metaspace.committed
Type: GAUGE
Unit: bytes

Metric: jvm.memory.metaspace.max
Type: GAUGE
Unit: bytes
Description: Max metaspace (default: unbounded)

Critical Queries:
  # Metaspace utilization
  (jvm_memory_metaspace_used / jvm_memory_metaspace_committed) * 100

  # Metaspace growth rate (indicator of classloader leak)
  deriv(jvm_memory_metaspace_used[1h])

  # Metaspace per classloader (should be stable)
  jvm_memory_metaspace_used / jvm_classloader_count

  # Correlation: Metaspace growth without heap growth = classloader leak
  (deriv(jvm_memory_metaspace_used[1h]) > 0) and
  (deriv(jvm_memory_heap_used[1h]) < 0)
```

**Leak Detection Pattern**:
```
Normal Pattern:
  Deploy verticle → Metaspace +5MB → Undeploy → Metaspace -5MB (after GC)

Leak Pattern:
  Deploy → +5MB → Undeploy → +5MB (never reclaimed!)
  20 cycles → +100MB metaspace permanently leaked
```

#### 3. Garbage Collection Metrics

```yaml
Metric: jvm.gc.pause.duration
Type: HISTOGRAM
Unit: milliseconds
Description: GC pause time distribution
Labels:
  - gc: G1_Young_Generation | G1_Old_Generation
Buckets: [10, 50, 100, 200, 500, 1000, 2000, 5000]

Metric: jvm.gc.collections.total
Type: COUNTER
Unit: collections
Description: Total GC executions
Labels:
  - gc: G1_Young_Generation | G1_Old_Generation

Metric: jvm.gc.memory.reclaimed
Type: COUNTER
Unit: bytes
Description: Total memory freed by GC

Derived Metrics:
  # GC frequency (collections per second)
  rate(jvm_gc_collections_total[5m])

  # GC overhead (% of time in GC)
  rate(jvm_gc_pause_duration_sum[5m]) /
  rate(jvm_gc_pause_duration_count[5m])

  # Memory reclamation efficiency
  rate(jvm_gc_memory_reclaimed[5m]) /
  rate(jvm_gc_collections_total[5m])

  # Alert: Excessive full GC
  rate(jvm_gc_collections_total{gc="G1_Old_Generation"}[5m]) > 0.016  # >1/minute
```

**GC Health Dashboard**:
```
┌─────────────────────────────────────────────────────────────┐
│                   GC PERFORMANCE ANALYSIS                    │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Young GC:                                                   │
│    Frequency: 2.3 /sec  ✅                                   │
│    Avg Duration: 23ms   ✅                                   │
│    P99 Duration: 45ms   ✅                                   │
│                                                               │
│  Full GC:                                                    │
│    Frequency: 0.008 /sec (1 every 2 minutes)  ⚠️             │
│    Avg Duration: 456ms  ⚠️                                   │
│    P99 Duration: 1.2s   🚨                                   │
│                                                               │
│  GC Overhead: 12.3%  🚨 (Target: < 5%)                       │
│                                                               │
│  Memory Reclaimed per GC:                                    │
│    Young: 180 MB  ✅                                         │
│    Full: 45 MB    ❌ (Low reclamation = retention issue)     │
│                                                               │
│  📊 DIAGNOSIS: Excessive full GCs with low reclamation       │
│                indicates memory leak or undersized heap      │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

#### 4. Thread Metrics

```yaml
Metric: jvm.threads.count
Type: GAUGE
Unit: threads
Description: Current thread count
Labels:
  - state: all | daemon | peak

Metric: jvm.threads.states
Type: GAUGE
Unit: threads
Description: Thread count by state
Labels:
  - state: RUNNABLE | BLOCKED | WAITING | TIMED_WAITING

Metric: vertx.eventloop.threads
Type: GAUGE
Unit: threads
Description: Vert.x event loop threads

Metric: vertx.eventloop.lag
Type: HISTOGRAM
Unit: milliseconds
Description: Event loop lag (blocked time)
Buckets: [1, 5, 10, 50, 100, 200, 500, 1000]

Capacity Queries:
  # Thread pool utilization
  jvm_threads_count{state="all"} /
  scalar(max_over_time(jvm_threads_count{state="all"}[7d]))

  # Blocked thread percentage
  (jvm_threads_states{state="BLOCKED"} /
   jvm_threads_count{state="all"}) * 100

  # Event loop responsiveness
  histogram_quantile(0.99, rate(vertx_eventloop_lag_bucket[5m]))
```

#### 5. CPU Metrics

```yaml
Metric: process.cpu.usage
Type: GAUGE
Unit: percent
Description: Process CPU usage (0-100%)

Metric: system.cpu.usage
Type: GAUGE
Unit: percent
Description: System-wide CPU usage

Capacity Planning:
  # CPU headroom
  100 - process_cpu_usage

  # CPU saturation
  process_cpu_usage > 80

  # Request capacity based on CPU
  # If at 1000 req/s with 50% CPU:
  # Max capacity ≈ 2000 req/s
  (100 / process_cpu_usage) * rate(http_server_requests_total[5m])
```

### Capacity Planning Methodology

#### Step 1: Establish Baseline

```promql
# Record during normal load
Baseline RPS: 1000 req/s
Baseline CPU: 40%
Baseline Heap: 1.2 GB / 4 GB (30%)
Baseline Threads: 50
```

#### Step 2: Calculate Headroom

```
Metric        | Current | Max   | Headroom | Limiting Factor?
--------------|---------|-------|----------|------------------
CPU           | 40%     | 100%  | 2.5x     | No
Heap          | 30%     | 100%  | 3.3x     | No
Threads       | 50      | 1000  | 20x      | No
Active Conns  | 200     | 1000  | 5x       | No
Event Loop    | 5ms lag | 100ms | 20x      | No

Bottleneck: CPU (can handle 2.5x current load)
Max RPS: 2,500 req/s before needing to scale
```

#### Step 3: Monitor Trends

```promql
# Traffic growth rate (% change week-over-week)
(rate(http_server_requests_total[7d]) -
 rate(http_server_requests_total[7d] offset 7d)) /
rate(http_server_requests_total[7d] offset 7d) * 100

# Example: +15% growth per week
# Week 0: 1000 req/s
# Week 4: 1,749 req/s
# Week 8: 3,059 req/s (exceeds capacity!)
# → Need to scale by Week 7
```

#### Step 4: Predictive Scaling

```
Current State (Week 0):
  RPS: 1,000 req/s
  CPU: 40%
  Max Capacity: 2,500 req/s

Growth Rate: +15% per week

Projection:
  Week 4: 1,749 req/s (70% CPU) ⚠️
  Week 6: 2,313 req/s (93% CPU) 🚨
  Week 8: 3,059 req/s (OVER CAPACITY) ❌

Action: Scale horizontally (add instance) by Week 5
```

---

## Classloader Leak Detection Strategy

### Why Classloader Leaks are Critical

**Your application's risk profile**:
- Deploys JARs dynamically via `URLClassLoader`
- Each deployment loads ~500-2000 classes
- Each class occupies ~5-10 KB in metaspace
- **Leak impact**: 100 deploy/undeploy cycles = +500MB metaspace never freed

**Consequences**:
1. **OutOfMemoryError: Metaspace** (server crash)
2. **Heap bloat** (classloaders hold references)
3. **GC thrashing** (trying to reclaim unreclaimable memory)
4. **Performance degradation** (more memory scans)

### Leak Detection Metrics

#### 1. Classloader Count Tracking

```yaml
Metric: jvm.classloader.count
Type: GAUGE
Unit: classloaders
Description: Total loaded classloaders in JVM
Labels:
  - type: loaded | total | unloaded

Metric: classloader.url.count
Type: GAUGE
Unit: classloaders
Description: Active URLClassLoader instances
Labels:
  - repo: micro-verticle-1 | all

Metric: classloader.classes.loaded
Type: GAUGE
Unit: classes
Description: Total classes loaded across all classloaders

Leak Detection Queries:
  # Classloaders should decrease after undeploy
  # Alert: Continuous growth
  deriv(jvm_classloader_count{type="loaded"}[30m]) > 0

  # Unload ratio (should be > 80% for healthy cleanup)
  jvm_classloader_count{type="unloaded"} /
  jvm_classloader_count{type="total"} < 0.5

  # URLClassLoader leak detection
  # After full undeploy, should return to baseline
  classloader_url_count{repo="all"} > 0
```

#### 2. Metaspace Correlation

```yaml
Metric: classloader.metaspace.per_loader
Type: GAUGE
Unit: bytes
Description: Average metaspace per classloader
Formula: jvm_memory_metaspace_used / jvm_classloader_count{type="loaded"}

Leak Indicators:
  # Metaspace growing while classloader count stable = classes not unloading
  (deriv(jvm_memory_metaspace_used[1h]) > 0) and
  (deriv(jvm_classloader_count{type="loaded"}[1h]) == 0)

  # Metaspace per classloader growing = leak within classloader
  deriv(classloader_metaspace_per_loader[1h]) > 0
```

#### 3. Deployment Lifecycle Correlation

```yaml
Metric: verticle.deployment.classes_loaded
Type: HISTOGRAM
Unit: classes
Description: Number of classes loaded per deployment
Labels:
  - repo: micro-verticle-1
Buckets: [100, 500, 1000, 2000, 5000]

Metric: verticle.undeployment.classes_unloaded
Type: HISTOGRAM
Unit: classes
Description: Number of classes unloaded after undeploy
Labels:
  - repo: micro-verticle-1

Leak Detection:
  # Classes loaded should equal classes unloaded
  sum(rate(verticle_deployment_classes_loaded_sum[1h])) ==
  sum(rate(verticle_undeployment_classes_unloaded_sum[1h]))

  # If loaded > unloaded → leak!
  sum(rate(verticle_deployment_classes_loaded_sum[1h])) -
  sum(rate(verticle_undeployment_classes_unloaded_sum[1h])) > 0
```

### Classloader Leak Dashboard

```
┌─────────────────────────────────────────────────────────────────────┐
│                  CLASSLOADER LEAK DETECTION DASHBOARD               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  📊 CURRENT STATE                                                    │
│  ├─ Active Classloaders: 127  (+77 from baseline of 50)  🚨         │
│  ├─ Total Loaded (lifetime): 4,532                                  │
│  ├─ Total Unloaded: 1,234                                           │
│  └─ Unload Ratio: 27%  🚨 (Target: > 80%)                           │
│                                                                       │
│  📈 METASPACE ANALYSIS                                               │
│  ├─ Metaspace Usage: 487 MB  (+437 MB from baseline)  🚨            │
│  ├─ Growth Rate: +12 MB/hour                                        │
│  ├─ Metaspace per Classloader: 3.8 MB  (Growing)  ⚠️                │
│  └─ Time to Metaspace OOM: 21 hours  🚨                              │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Classloader Count vs Deployments (Last 6h)                  │    │
│  │                                                              │    │
│  │ 150┼                                         ●───●───●  CL  │    │
│  │    │                                   ●───●╱               │    │
│  │ 100┼                             ●───●╱                     │    │
│  │    │                       ●───●╱                           │    │
│  │  50┼─────────────────────────────  (Baseline)               │    │
│  │    │   ╱●╲   ╱●╲   ╱●╲   ╱●╲   ╱●╲            Deploys      │    │
│  │   0┼──●───●─●───●─●───●─●───●─●───●                        │    │
│  │    └───┬────┬────┬────┬────┬────┬────                      │    │
│  │       00h  01h  02h  03h  04h  05h                          │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  🔍 LEAK DIAGNOSIS                                                   │
│  ├─ Deployments: 45                                                  │
│  ├─ Undeployments: 42                                                │
│  ├─ Expected CL count: 53 (50 baseline + 3 active)                  │
│  ├─ Actual CL count: 127                                             │
│  └─ LEAKED CLASSLOADERS: 74  🚨                                      │
│                                                                       │
│  📋 TOP SUSPECTS (Repos with most leaked classes)                   │
│  1. micro-verticle-3: 12 leaked classloaders (+60 MB metaspace)     │
│  2. micro-verticle-1: 8 leaked classloaders  (+40 MB metaspace)     │
│  3. micro-verticle-2: 4 leaked classloaders  (+20 MB metaspace)     │
│                                                                       │
│  ⚡ RECOMMENDATIONS                                                  │
│  1. Investigate micro-verticle-3 for leaked references:             │
│     - Thread locals                                                  │
│     - Static fields                                                  │
│     - Event bus consumers not unregistered                           │
│     - Timers not cancelled                                           │
│  2. Run heap dump analysis (jmap -dump:live,format=b,file=heap.bin) │
│  3. Restart server if OOM imminent                                   │
│  4. Add explicit cleanup() method to VerticleLifecycle               │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Leak Detection Alerts

```yaml
# Critical: Classloader leak detected
- alert: ClassloaderLeak
  expr: deriv(jvm_classloader_count{type="loaded"}[30m]) > 0
  for: 2h
  labels:
    severity: critical
  annotations:
    summary: "Classloader leak detected"
    description: "Classloaders growing for 2+ hours, likely leak"

# Critical: Metaspace leak
- alert: MetaspaceLeak
  expr: |
    (deriv(jvm_memory_metaspace_used[1h]) > 1024 * 1024 * 10) and
    (deriv(jvm_classloader_count{type="loaded"}[1h]) >= 0)
  for: 3h
  labels:
    severity: critical
  annotations:
    summary: "Metaspace growing > 10MB/hour"
    description: "Likely classloader leak"

# Warning: Low classloader unload ratio
- alert: LowClassloaderUnloadRatio
  expr: |
    (jvm_classloader_count{type="unloaded"} /
     jvm_classloader_count{type="total"}) < 0.5
  for: 1h
  labels:
    severity: warning
  annotations:
    summary: "Less than 50% of classloaders are being GC'd"

# Warning: Metaspace approaching limit
- alert: MetaspaceNearLimit
  expr: |
    (jvm_memory_metaspace_used /
     jvm_memory_metaspace_committed) > 0.9
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Metaspace usage > 90%"
```

### Programmatic Leak Detection

```java
// Add to DeploymentHandler
public class ClassloaderHealthCheck {
    private static final int BASELINE_CLASSLOADER_COUNT = 50;
    private static final long MAX_METASPACE_PER_VERTICLE = 10 * 1024 * 1024; // 10MB

    public boolean isLeakDetected() {
        int currentClassloaders = ManagementFactory.getClassLoadingMXBean()
            .getLoadedClassCount();

        int expectedClassloaders = BASELINE_CLASSLOADER_COUNT +
                                   deployedVerticles.size();

        int leaked = currentClassloaders - expectedClassloaders;

        if (leaked > 10) {
            logger.warn("Classloader leak detected: {} leaked classloaders", leaked);
            // Emit custom metric
            classloaderLeakGauge.set(leaked);
            return true;
        }

        return false;
    }

    public void performLeakCheck() {
        if (isLeakDetected()) {
            // Log details for investigation
            logger.error("CLASSLOADER LEAK DETAILS:");
            logger.error("  Expected: {}", expectedClassloaders);
            logger.error("  Actual: {}", currentClassloaders);
            logger.error("  Leaked: {}", leaked);

            // Optionally trigger heap dump
            if (leaked > 50) {
                triggerHeapDump();
            }
        }
    }
}
```

---

## Distributed Tracing Design

### Trace Structure

```
Trace ID: abc123def456 (unique per request)
│
└─ Span: http.request (234ms) [ROOT]
   ├─ Attributes:
   │  ├─ http.method: POST
   │  ├─ http.route: /deploy
   │  ├─ http.status_code: 200
   │  └─ http.request.body.size: 1024
   │
   ├─ Span: validate_request (5ms)
   │  ├─ Attributes:
   │  │  ├─ validation.result: success
   │  │  └─ content.type: application/json
   │
   ├─ Span: verticle.deploy (200ms)
   │  ├─ Attributes:
   │  │  ├─ verticle.repo: micro-verticle-1
   │  │  └─ deployment.id: dep_xyz
   │  │
   │  ├─ Span: load_jar (50ms)
   │  │  ├─ Attributes:
   │  │  │  ├─ jar.path: ../micro-verticle-1/build/libs/...
   │  │  │  └─ jar.size.bytes: 5242880
   │  │
   │  ├─ Span: read_config (10ms)
   │  │  ├─ Attributes:
   │  │  │  ├─ config.path: config/micro-verticle-1.json
   │  │  │  └─ config.found: true
   │  │
   │  ├─ Span: instantiate_verticle (20ms)
   │  │  ├─ Attributes:
   │  │  │  ├─ verticle.class: com.example.MyVerticle
   │  │  │  └─ classloader.id: cl_123
   │  │
   │  └─ Span: deploy_to_vertx (120ms)
   │     ├─ Attributes:
   │     │  ├─ vertx.deployment.options: {}
   │     │  └─ classes.loaded: 1,234
   │
   └─ Span: send_response (2ms)
      └─ Attributes:
         └─ response.size.bytes: 256
```

### Trace Sampling Strategy

```java
// Production: Sample 10% of traces, 100% of errors/slow requests
Sampler sampler = Sampler.parentBased(
    Sampler.traceIdRatioBased(0.1)  // 10% base sampling
);

// But always sample if:
// 1. Status code >= 400
// 2. Duration > 1 second
// 3. Specific endpoints (e.g., /deploy)

// Implemented via custom sampler or span processors
```

---

## Implementation Plan

### Phase 1: Dependencies & Setup (Week 1)

**Tasks**:
1. Add OpenTelemetry dependencies to `build.gradle`
2. Create `com.example.observability` package
3. Implement `OpenTelemetryConfig` for SDK initialization
4. Add resource attributes (service name, version, environment)

**Deliverables**:
- OpenTelemetry SDK initialized on server startup
- `/metrics` endpoint exposed on port 9090
- Metrics exported to Prometheus format

### Phase 2: HTTP Layer Instrumentation (Week 1-2)

**Tasks**:
1. Add HTTP request counter
2. Add HTTP duration histogram
3. Add active requests gauge
4. Add request/response size histograms
5. Wrap handlers with tracing spans

**Deliverables**:
- All HTTP requests are counted and timed
- P95/P99 latencies tracked
- Basic Grafana dashboard for HTTP metrics

### Phase 3: JVM Metrics (Week 2)

**Tasks**:
1. Implement heap memory gauges
2. Implement metaspace gauges
3. Implement GC metrics (pause time, frequency)
4. Implement thread metrics (count, states)
5. Implement CPU usage gauges

**Deliverables**:
- Comprehensive JVM health dashboard
- Capacity planning queries available
- Heap usage alerts configured

### Phase 4: Classloader Leak Detection (Week 2-3)

**Tasks**:
1. Implement classloader count gauge
2. Track classes loaded per deployment
3. Correlate metaspace with classloader count
4. Add leak detection alerts
5. Create classloader health dashboard

**Deliverables**:
- Real-time classloader leak detection
- Automated alerts on leak patterns
- Dashboard showing leak diagnosis

### Phase 5: Deployment Lifecycle Instrumentation (Week 3)

**Tasks**:
1. Add deployment counter (success/failure)
2. Add deployment duration histogram
3. Add active verticles gauge
4. Track deployment failure reasons
5. Add undeployment metrics

**Deliverables**:
- Deployment success rate tracking
- Deployment performance monitoring
- Alerts on deployment failures

### Phase 6: Verticle Handler Instrumentation (Week 3-4)

**Tasks**:
1. Add handler duration histogram per verticle
2. Add handler error counter per verticle
3. Implement request validation metrics
4. Add protobuf size tracking

**Deliverables**:
- Per-verticle performance visibility
- Identify slow handlers
- Validation failure tracking

### Phase 7: Distributed Tracing (Week 4)

**Tasks**:
1. Configure OTLP exporter for traces
2. Add HTTP request root span
3. Add deployment lifecycle child spans
4. Configure sampling strategy
5. Set up Jaeger/Tempo backend

**Deliverables**:
- End-to-end request tracing
- Deployment flow visualization
- Jaeger UI integration

### Phase 8: Dashboards & Alerts (Week 4-5)

**Tasks**:
1. Create Grafana dashboards:
   - Capacity Planning
   - JVM Health
   - Classloader Leaks
   - Deployment Health
   - Request Performance
2. Configure Alertmanager rules
3. Set up notification channels (Slack, email)
4. Document runbooks for alerts

**Deliverables**:
- 5 production-ready Grafana dashboards
- 10+ alert rules
- Incident response runbooks

---

## Dependencies

### Gradle Dependencies

```gradle
dependencies {
    // OpenTelemetry Core
    implementation 'io.opentelemetry:opentelemetry-api:1.41.0'
    implementation 'io.opentelemetry:opentelemetry-sdk:1.41.0'
    implementation 'io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.41.0'

    // OpenTelemetry Instrumentation
    implementation 'io.opentelemetry.instrumentation:opentelemetry-vertx-http-server-5.0:2.8.0-alpha'
    implementation 'io.opentelemetry.instrumentation:opentelemetry-runtime-telemetry-java8:2.8.0-alpha'

    // Exporters
    implementation 'io.opentelemetry:opentelemetry-exporter-prometheus:1.41.0-alpha'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp:1.41.0'
    implementation 'io.opentelemetry:opentelemetry-exporter-logging:1.41.0'

    // Semantic Conventions
    implementation 'io.opentelemetry.semconv:opentelemetry-semconv:1.27.0-alpha'

    // Optional: Micrometer bridge (if needed for Spring Boot Actuator compatibility)
    // implementation 'io.micrometer:micrometer-registry-otlp:1.12.0'
}
```

### Infrastructure Dependencies

```yaml
# Prometheus (metrics storage & alerting)
prometheus:
  version: 2.48.0
  scrape_interval: 15s
  evaluation_interval: 15s
  retention: 30d

# Grafana (dashboards)
grafana:
  version: 10.2.0
  plugins:
    - grafana-piechart-panel
    - grafana-clock-panel

# Jaeger (distributed tracing)
jaeger:
  version: 1.52.0
  components:
    - jaeger-all-in-one  # For dev/staging
    - jaeger-agent       # For production
    - jaeger-collector
    - jaeger-query

# Alertmanager (alert routing)
alertmanager:
  version: 0.26.0
  integrations:
    - slack
    - pagerduty
    - email
```

---

## Dashboards & Alerts

### Dashboard 1: Capacity Planning

**Panels**:
1. Request Rate (req/s) - Current vs Historical
2. Headroom Gauge (% capacity remaining)
3. Time to Saturation (hours until scaling needed)
4. Resource Utilization Heatmap (CPU, Memory, Threads)
5. Growth Rate Trend (% change week-over-week)
6. Max Capacity Projection (based on current bottleneck)

**Purpose**: Proactive scaling decisions

### Dashboard 2: JVM Health

**Panels**:
1. Heap Usage (used/max) with trend
2. Heap Growth Rate (MB/hour)
3. Metaspace Usage with trend
4. GC Frequency & Duration
5. GC Overhead Percentage
6. Thread Count by State
7. CPU Usage (process vs system)

**Purpose**: Detect memory leaks and performance issues

### Dashboard 3: Classloader Leak Detection

**Panels**:
1. Classloader Count Timeline
2. Metaspace vs Classloader Count Correlation
3. Classes Loaded vs Unloaded
4. Unload Ratio Gauge
5. Metaspace per Classloader
6. Deployment vs Classloader Count
7. Top Leaking Repos (ranked by leaked classloaders)

**Purpose**: Early classloader leak detection

### Dashboard 4: Deployment Health

**Panels**:
1. Deployment Success Rate (%)
2. Deployment Duration Distribution
3. Active Verticles Count
4. Deployment Failures by Reason
5. Deployment Frequency Trend
6. Classes Loaded per Deployment

**Purpose**: Monitor deployment reliability

### Dashboard 5: Request Performance

**Panels**:
1. Request Rate by Endpoint
2. P50/P95/P99 Latency by Endpoint
3. Error Rate (%) by Status Code
4. Request Size Distribution
5. Validation Failure Rate
6. Handler Duration by Verticle
7. Slowest Verticles (Top 10)

**Purpose**: Application performance monitoring

### Alert Rules Summary

| Alert | Threshold | Severity | Action |
|-------|-----------|----------|--------|
| Heap > 90% | 5min | Critical | Investigate leak or scale |
| Heap > 80% | 15min | Warning | Monitor closely |
| Classloader Leak | 2h growth | Critical | Heap dump + investigation |
| Metaspace Leak | 3h growth | Critical | Review deployments |
| GC Overhead > 20% | 10min | Warning | Tune GC or increase heap |
| Full GC > 1/min | 5min | Critical | Memory issue |
| P99 Latency > 1s | 5min | Warning | Performance investigation |
| Error Rate > 5% | 5min | Critical | Check logs + traces |
| Deployment Fail > 5% | 1h | Warning | Check configs |
| CPU > 90% | 10min | Warning | Scale horizontally |

---

## Next Steps

**To proceed with implementation**:

1. **Review this design** - Confirm it meets your needs
2. **Approve dependencies** - Ensure no conflicts with existing stack
3. **Set priorities** - Which phase to start with?
4. **Define SLOs** - What are your latency/availability targets?
5. **Infrastructure setup** - Do you have Prometheus/Grafana/Jaeger ready?

Would you like me to:
- Start implementing Phase 1 (dependencies & setup)?
- Create a specific dashboard configuration?
- Write a proof-of-concept for classloader leak detection?
- Set up a local Prometheus + Grafana stack for testing?

Let me know and I'll proceed with the implementation!
