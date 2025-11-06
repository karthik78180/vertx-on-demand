# Thread Monitoring Guide for Vert.x On-Demand Server

## Overview

Comprehensive thread monitoring with focus on:
- **Vert.x Event Loop Threads** - Must never be blocked
- **Vert.x Worker Threads** - Can be blocked (designed for blocking operations)
- **Blocked Thread Detection** - Automatic detection with stack traces
- **Virtual Threads** - Java 21+ Project Loom support

---

## Why Thread Monitoring is Critical

### The Event Loop Problem

Vert.x uses a **reactor pattern** with event loop threads:

```
❌ BAD: Blocking the Event Loop
┌──────────────────────────────────────┐
│  Event Loop Thread (BLOCKED!)        │
│  ├─ HTTP Request 1: waiting...       │
│  ├─ HTTP Request 2: waiting...       │
│  ├─ HTTP Request 3: waiting...       │
│  └─ 💀 BLOCKING DATABASE CALL        │  ← Kills performance!
└──────────────────────────────────────┘

Result: ALL requests stuck, server appears frozen
```

```
✅ GOOD: Using Worker Threads
┌──────────────────────────────────────┐
│  Event Loop Thread                   │
│  ├─ HTTP Request 1: ✅ responding    │
│  ├─ HTTP Request 2: ✅ responding    │
│  └─ HTTP Request 3: ✅ responding    │
└──────────────────────────────────────┘
         │
         └─→ Worker Thread
             └─ Blocking DB call
```

### Key Principle

**Event loop threads must NEVER block. Ever.**

Blocking operations include:
- Database queries
- File I/O
- HTTP client calls (synchronous)
- `Thread.sleep()`
- Synchronous lock acquisition
- Any operation that waits

---

## Thread Metrics Exposed

### 1. Vert.x Event Loop Threads

```prometheus
# Number of event loop threads
vertx_threads_eventloop{state="all"} 8

# Event loop threads by state
vertx_threads_eventloop{state="RUNNABLE"} 8
vertx_threads_eventloop{state="WAITING"} 0
vertx_threads_eventloop{state="BLOCKED"} 0  ← Should ALWAYS be 0!
```

**What to Monitor**:
- `state="BLOCKED"` should **always be 0**
- If > 0, you have a **critical** performance issue

### 2. Vert.x Worker Threads

```prometheus
# Number of worker threads
vertx_threads_worker{state="all"} 20

# Worker threads by state
vertx_threads_worker{state="RUNNABLE"} 5
vertx_threads_worker{state="WAITING"} 10
vertx_threads_worker{state="BLOCKED"} 5  ← OK for workers!
```

**What to Monitor**:
- High `BLOCKED` count = potential thread pool exhaustion
- If all workers blocked = need to increase worker pool size

### 3. Blocked Thread Detection

```prometheus
# Number of currently blocked threads
jvm_threads_blocked_count{thread_type="all"} 12
jvm_threads_blocked_count{thread_type="eventloop"} 0  ← CRITICAL if > 0!
jvm_threads_blocked_count{thread_type="worker"} 12   ← OK

# Maximum blocked thread duration (ms)
jvm_threads_blocked_duration 5432

# Blocked event loop duration (CRITICAL metric)
jvm_threads_blocked_duration{thread_type="eventloop"} 0
```

**Alert Rules**:
```promql
# CRITICAL: Event loop blocked
jvm_threads_blocked_count{thread_type="eventloop"} > 0

# WARNING: Too many blocked threads
jvm_threads_blocked_count{thread_type="all"} > 50

# CRITICAL: Thread blocked for > 10 seconds
jvm_threads_blocked_duration > 10000
```

### 4. Event Loop Blocked Incidents

```prometheus
# Total times event loop was blocked (CRITICAL counter)
vertx_eventloop_blocked_total 0  ← Should stay at 0!
```

**Alert**:
```promql
# Alert if event loop ever gets blocked
increase(vertx_eventloop_blocked_total[5m]) > 0
```

### 5. Critical Blocked Threads

```prometheus
# Threads blocked for > 5 seconds
jvm_threads_blocked_critical_total 3
```

### 6. Virtual Threads (Java 21+)

```prometheus
# Number of virtual threads
jvm_threads_virtual_count 142
```

**What are Virtual Threads?**
- Java 21+ feature (Project Loom)
- Lightweight threads managed by JVM
- Can have millions of them
- Change the blocking paradigm

### 7. Thread State Breakdown

```prometheus
# Threads by state and type
jvm_threads_by_state{state="RUNNABLE",thread_type="eventloop",virtual="false"} 8
jvm_threads_by_state{state="WAITING",thread_type="worker",virtual="false"} 12
jvm_threads_by_state{state="BLOCKED",thread_type="other",virtual="false"} 5
jvm_threads_by_state{state="RUNNABLE",thread_type="eventloop",virtual="true"} 0
```

---

## Blocked Thread Detection

### How It Works

The `ThreadMetrics` class:
1. Polls all threads every metric collection cycle
2. Identifies threads in `BLOCKED` state
3. Tracks blocked duration
4. Captures stack traces
5. Logs critical incidents

### Detection Thresholds

```java
// Consider blocked if > 100ms
BLOCKED_THRESHOLD_MS = 100

// Critical if blocked > 5 seconds
CRITICAL_BLOCKED_THRESHOLD_MS = 5000
```

### Automatic Logging

#### Event Loop Blocked (CRITICAL)

When an event loop thread is detected as blocked:

```
============================================
   🚨 CRITICAL: EVENT LOOP BLOCKED! 🚨
============================================
Event loop threads should NEVER block!
This will severely degrade application performance.

Thread: vert.x-eventloop-thread-0
Blocked Time: 234ms
State: BLOCKED
Lock: java.util.concurrent.locks.ReentrantLock$NonfairSync@1a2b3c4d

Stack Trace:
  at java.util.concurrent.locks.LockSupport.park(LockSupport.java:194)
  at java.util.concurrent.locks.AbstractQueuedSynchronizer.parkAndCheckInterrupt(...)
  at com.example.MyVerticle.handleRequest(MyVerticle.java:45)
  ...

⚠️  ACTION REQUIRED:
1. Move blocking operation to worker thread
2. Use executeBlocking() for blocking calls
3. Review code at: com.example.MyVerticle.handleRequest(MyVerticle.java:45)
============================================
```

#### Critical Blocked Thread (>5s)

```
============================================
   CRITICAL: THREAD BLOCKED > 5 SECONDS
============================================
Thread: vert.x-worker-thread-5
ID: 42
State: BLOCKED
Blocked Time: 5432ms
Blocked Count: 15
Lock: java.util.concurrent.locks.ReentrantLock$NonfairSync@5e6f7a8b
Lock Owner: vert.x-worker-thread-3 (ID: 39)

Stack Trace:
  at sun.misc.Unsafe.park(Native Method)
  at java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)
  at com.example.Database.query(Database.java:123)
  ...
============================================
```

### Health Check Integration

The `/health` endpoint includes thread monitoring:

```json
{
  "status": "unhealthy",
  "heap_utilization": 45.2,
  "active_verticles": 3,
  "blocked_threads": 12,
  "blocked_event_loops": 1,    ← CRITICAL!
  "using_virtual_threads": false
}
```

**Status Code**:
- `200 OK` - All healthy
- `503 Service Unavailable` - Event loop blocked OR heap > 90% OR classloader leak

---

## Vert.x Blocked Thread Checker

### Configuration

`MainServer.java` enables Vert.x's built-in blocked thread checker:

```java
VertxOptions options = new VertxOptions()
    // Check every 1 second
    .setBlockedThreadCheckInterval(1000)
    .setBlockedThreadCheckIntervalUnit(TimeUnit.MILLISECONDS)

    // Warn if event loop blocked > 2 seconds
    .setMaxEventLoopExecuteTime(2)
    .setMaxEventLoopExecuteTimeUnit(TimeUnit.SECONDS)

    // Warn if worker thread blocked > 10 seconds
    .setMaxWorkerExecuteTime(10)
    .setMaxWorkerExecuteTimeUnit(TimeUnit.SECONDS)

    // Warn on long exception handling
    .setWarningExceptionTime(5)
    .setWarningExceptionTimeUnit(TimeUnit.SECONDS);

Vertx vertx = Vertx.vertx(options);
```

### Vert.x Log Output

When event loop is blocked, Vert.x logs:

```
WARNING: Thread Thread[vert.x-eventloop-thread-0,5,main] has been blocked for 2045 ms, time limit is 2000 ms
io.vertx.core.VertxException: Thread blocked
	at com.example.MyVerticle.handleRequest(MyVerticle.java:45)
	...
```

---

## Virtual Threads (Java 21+)

### What are Virtual Threads?

**Traditional Threads**:
- OS-managed (platform threads)
- Heavy (~1MB stack)
- Limited by OS (thousands max)
- Context switching expensive

**Virtual Threads**:
- JVM-managed
- Lightweight (~1KB)
- Millions possible
- Cheap context switching
- Can block freely!

### Detection

`ThreadMetrics` detects virtual threads by:
1. Thread name patterns (`VirtualThread-*`)
2. Thread type checking (Java 21+ API)

```prometheus
jvm_threads_virtual_count 1423
```

### Virtual Threads in Vert.x

**NOTE**: As of Vert.x 5.0, virtual threads are **experimental**.

Future Vert.x versions may allow:
```java
// Hypothetical future API
VertxOptions options = new VertxOptions()
    .setUseVirtualThreads(true);
```

This would eliminate event loop blocking concerns!

### Checking Virtual Thread Usage

```java
// Via metrics
boolean usingVirtual = metricsRegistry.threads().isUsingVirtualThreads();

// Via health endpoint
curl http://localhost:8080/health
// "using_virtual_threads": true
```

---

## Common Blocking Scenarios & Solutions

### ❌ Scenario 1: Blocking Database Call

```java
// BAD: Blocks event loop!
public void handleRequest(RoutingContext ctx) {
    String result = database.query("SELECT * FROM users");  // BLOCKS!
    ctx.response().end(result);
}
```

```java
// GOOD: Use executeBlocking
public void handleRequest(RoutingContext ctx) {
    vertx.executeBlocking(promise -> {
        // Runs on worker thread
        String result = database.query("SELECT * FROM users");
        promise.complete(result);
    }).onComplete(ar -> {
        // Back on event loop
        if (ar.succeeded()) {
            ctx.response().end(ar.result());
        } else {
            ctx.fail(ar.cause());
        }
    });
}
```

### ❌ Scenario 2: File I/O

```java
// BAD: Blocks event loop!
String content = Files.readString(Path.of("file.txt"));
```

```java
// GOOD: Use Vert.x async file system
vertx.fileSystem().readFile("file.txt", ar -> {
    if (ar.succeeded()) {
        Buffer content = ar.result();
        // Process content
    }
});
```

### ❌ Scenario 3: Synchronous HTTP Client

```java
// BAD: Blocks event loop!
HttpResponse<String> response = HttpClient.newHttpClient()
    .send(request, HttpResponse.BodyHandlers.ofString());
```

```java
// GOOD: Use Vert.x WebClient
WebClient client = WebClient.create(vertx);
client.get(80, "example.com", "/api")
    .send()
    .onSuccess(response -> {
        // Async response handling
    });
```

### ❌ Scenario 4: Thread.sleep()

```java
// BAD: Blocks event loop!
Thread.sleep(1000);
```

```java
// GOOD: Use Vert.x timers
vertx.setTimer(1000, id -> {
    // Executes after 1 second without blocking
});
```

### ❌ Scenario 5: Synchronized Blocks

```java
// BAD: Can block event loop!
public synchronized void criticalSection() {
    // If another thread holds lock, event loop blocks!
}
```

```java
// GOOD: Use Lock with tryLock
private final Lock lock = new ReentrantLock();

public void criticalSection(Handler<Boolean> handler) {
    if (lock.tryLock()) {
        try {
            // Critical section
            handler.handle(true);
        } finally {
            lock.unlock();
        }
    } else {
        // Couldn't acquire lock, retry later
        vertx.setTimer(10, id -> criticalSection(handler));
    }
}
```

---

## Monitoring Dashboard Panels

### Panel 1: Event Loop Health

```promql
# Event loop blocked count (should be 0)
jvm_threads_blocked_count{thread_type="eventloop"}

# Event loop blocked incidents (should not increase)
increase(vertx_eventloop_blocked_total[5m])
```

**Visualization**: Stat panel with red alert if > 0

### Panel 2: Thread State Breakdown

```promql
# Event loop states
vertx_threads_eventloop

# Worker states
vertx_threads_worker
```

**Visualization**: Stacked bar chart showing RUNNABLE/WAITING/BLOCKED

### Panel 3: Blocked Threads Over Time

```promql
# Total blocked threads
jvm_threads_blocked_count{thread_type="all"}

# Max blocked duration
jvm_threads_blocked_duration
```

**Visualization**: Time series line chart

### Panel 4: Virtual Thread Adoption

```promql
# Virtual thread count
jvm_threads_virtual_count

# Virtual vs platform threads
jvm_threads_count{state="all"} - jvm_threads_virtual_count
```

**Visualization**: Time series showing virtual thread growth

---

## Alert Rules

```yaml
groups:
  - name: thread_monitoring
    rules:
      # CRITICAL: Event loop blocked
      - alert: EventLoopBlocked
        expr: jvm_threads_blocked_count{thread_type="eventloop"} > 0
        for: 10s
        labels:
          severity: critical
        annotations:
          summary: "Event loop thread blocked!"
          description: "Event loop blocked for {{ $value }} threads. This is CRITICAL for Vert.x performance."

      # CRITICAL: Event loop blocking incidents
      - alert: EventLoopBlockingIncidents
        expr: increase(vertx_eventloop_blocked_total[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "Event loop has been blocked {{ $value }} times in last 5min"

      # WARNING: Too many blocked threads
      - alert: HighBlockedThreadCount
        expr: jvm_threads_blocked_count{thread_type="all"} > 20
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $value }} threads currently blocked"

      # CRITICAL: Thread blocked for very long time
      - alert: ThreadBlockedTooLong
        expr: jvm_threads_blocked_duration > 30000
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Thread blocked for {{ $value }}ms (> 30s)"
          description: "Possible deadlock or severe contention"

      # INFO: Virtual threads in use
      - alert: VirtualThreadsDetected
        expr: jvm_threads_virtual_count > 0
        for: 1m
        labels:
          severity: info
        annotations:
          summary: "Application using {{ $value }} virtual threads (Java 21+)"
```

---

## Testing Blocked Thread Detection

### Test 1: Simulate Blocked Event Loop

Create a test verticle that blocks:

```java
public class BlockingTestVerticle extends AbstractVerticle {
    @Override
    public void start() {
        vertx.createHttpServer()
            .requestHandler(req -> {
                try {
                    // DELIBERATELY BLOCK EVENT LOOP (for testing only!)
                    Thread.sleep(5000);
                    req.response().end("This is bad!");
                } catch (InterruptedException e) {
                    req.fail(e);
                }
            })
            .listen(8081);
    }
}
```

**Expected Behavior**:
1. Vert.x logs blocked thread warning after 2s
2. `ThreadMetrics` detects blocked event loop
3. `vertx_eventloop_blocked_total` counter increments
4. `/health` endpoint returns 503
5. Critical log message printed

### Test 2: Monitor Health Endpoint

```bash
# Before blocking
curl http://localhost:8080/health
# {"status":"healthy",...,"blocked_event_loops":0}

# Trigger blocking operation
curl http://localhost:8081/test

# During blocking (check in another terminal)
curl http://localhost:8080/health
# {"status":"unhealthy",...,"blocked_event_loops":1}
```

### Test 3: Check Metrics

```bash
# Before
curl http://localhost:9090/metrics | grep eventloop_blocked
# vertx_eventloop_blocked_total 0

# After triggering block
curl http://localhost:9090/metrics | grep eventloop_blocked
# vertx_eventloop_blocked_total 1
```

---

## Best Practices

### ✅ DO

1. **Use worker threads for blocking operations**
   ```java
   vertx.executeBlocking(promise -> { /* blocking code */ });
   ```

2. **Use Vert.x async APIs**
   - `vertx.fileSystem()` for file I/O
   - `WebClient` for HTTP
   - `vertx.setTimer()` instead of `Thread.sleep()`

3. **Monitor event loop blocked count**
   - Alert if > 0
   - Investigate immediately

4. **Use dedicated worker pool for heavy operations**
   ```java
   WorkerExecutor executor = vertx.createSharedWorkerExecutor("heavy-pool", 10);
   executor.executeBlocking(promise -> { /* heavy work */ });
   ```

5. **Profile blocked threads**
   - Use `metricsRegistry.threads().getBlockedThreadReport()`
   - Check stack traces to find blocking code

### ❌ DON'T

1. **Never block the event loop**
   - No `Thread.sleep()`
   - No synchronous I/O
   - No long computations

2. **Don't ignore blocked thread warnings**
   - Fix immediately
   - They indicate serious performance issues

3. **Don't use synchronized on hot paths**
   - Use concurrent collections
   - Use lock-free algorithms
   - Use `vertx.runOnContext()` for serialization

4. **Don't create platform threads unnecessarily**
   - Use Vert.x's thread pools
   - Consider virtual threads (Java 21+)

---

## Summary

**Thread monitoring provides**:
- ✅ Real-time event loop health
- ✅ Blocked thread detection with stack traces
- ✅ Worker thread utilization tracking
- ✅ Virtual thread adoption metrics (Java 21+)
- ✅ Automatic alerting on critical issues
- ✅ Detailed blocked thread reports

**Critical metrics to watch**:
- `jvm_threads_blocked_count{thread_type="eventloop"}` → Must be 0
- `vertx_eventloop_blocked_total` → Should not increase
- `jvm_threads_blocked_duration` → Should be low
- `vertx_threads_eventloop{state="BLOCKED"}` → Must be 0

**For Vert.x applications, an event loop block is a CRITICAL issue that must be fixed immediately!**
