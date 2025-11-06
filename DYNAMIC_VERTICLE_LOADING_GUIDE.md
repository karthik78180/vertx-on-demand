# Dynamic Verticle Loading & JVM Utilization Guide

## Table of Contents
1. [Current Architecture Overview](#current-architecture-overview)
2. [Strategies for Maximum JVM Utilization](#strategies-for-maximum-jvm-utilization)
3. [Resource Sharing Matrix](#resource-sharing-matrix)
4. [Isolation Strategies & Trade-offs](#isolation-strategies--trade-offs)
5. [Drawbacks & Considerations](#drawbacks--considerations)
6. [Recommendations](#recommendations)

---

## Current Architecture Overview

### What You Have Now
Your `vertx-on-demand` server dynamically loads verticles from JAR files (like `micro-verticle-1`) using:
- **URLClassLoader** per JAR for class isolation
- **HTTP-based deployment** via `/deploy` endpoint
- **Configuration-driven** verticle instantiation from JSON files
- **Shared Vert.x instance** across all verticles

### Current Isolation Level
```
Application ClassLoader (Parent)
  ├── vertx-core (shared)
  ├── vertx-web (shared)
  └── protobuf-java (shared)
    │
    ├── URLClassLoader[micro-verticle-1]
    │     └── ReadHelloWorld, ProtoVerticle
    │
    ├── URLClassLoader[micro-verticle-2]
    │     └── OtherVerticle
    │
    └── URLClassLoader[micro-verticle-N]
          └── AnotherVerticle
```

---

## Strategies for Maximum JVM Utilization

### Strategy 1: **Worker Pool Verticle Deployment** (Recommended)
Deploy verticles using worker threads to utilize CPU cores without blocking the event loop.

#### Implementation
```java
// In DeploymentHandler.java
DeploymentOptions options = new DeploymentOptions()
    .setWorker(true)                    // Run on worker pool
    .setWorkerPoolSize(20)              // Dedicated worker pool
    .setMaxWorkerExecuteTime(60)        // 60 seconds timeout
    .setInstances(Runtime.getRuntime().availableProcessors()); // CPU cores

vertx.deployVerticle(verticleInstance, options);
```

#### Benefits
- **CPU Utilization**: Each instance runs on separate worker thread
- **Parallelism**: Scales with CPU cores automatically
- **Blocking Operations**: Safe for database queries, file I/O
- **Event Loop Protection**: Doesn't block main event loop

#### JVM Utilization
- **Threads**: Creates `instances * workerPoolSize` threads maximum
- **Memory**: Each instance has separate object graph
- **CPU**: Distributes load across available cores

---

### Strategy 2: **Multi-Instance Event Loop Verticles**
Deploy multiple instances of event-loop verticles for high-throughput scenarios.

#### Implementation
```java
DeploymentOptions options = new DeploymentOptions()
    .setInstances(Runtime.getRuntime().availableProcessors() * 2);

vertx.deployVerticle(verticleInstance, options);
```

#### Benefits
- **Low Overhead**: Uses lightweight event loop threads
- **High Throughput**: Ideal for non-blocking I/O operations
- **Automatic Load Balancing**: Vert.x distributes requests across instances

#### Best For
- Stateless request handlers
- Async I/O operations (HTTP calls, async DB queries)
- Message passing via Event Bus

#### Limitation
- **No Blocking Code**: Verticles must be fully async/non-blocking

---

### Strategy 3: **Hierarchical ClassLoader Isolation**
Create parent ClassLoaders for shared libraries to reduce memory overhead.

#### Current Problem
```
Each URLClassLoader loads:
  - vertx-core (duplicate)
  - protobuf-java (duplicate)
  - Custom dependencies (isolated)
```

#### Solution: Shared Platform ClassLoader
```java
// Create platform ClassLoader with common dependencies
URL[] platformJars = new URL[] {
    new File("lib/vertx-core-5.0.1.jar").toURI().toURL(),
    new File("lib/protobuf-java-4.31.1.jar").toURI().toURL()
};
ClassLoader platformLoader = new URLClassLoader(
    platformJars,
    DeploymentHandler.class.getClassLoader()
);

// Create verticle ClassLoader with platform as parent
URLClassLoader verticleLoader = new URLClassLoader(
    new URL[]{jarFile.toURI().toURL()},
    platformLoader  // Shared platform libraries
);
```

#### Benefits
- **Memory Savings**: Common libraries loaded once
- **Faster Deployment**: Less class loading overhead
- **Consistent Behavior**: Ensures library version consistency

#### Trade-off
- **Less Isolation**: All verticles share library versions
- **ClassLoader Leaks**: Harder to fully unload shared classes

---

### Strategy 4: **Clustered Vert.x with Event Bus**
Distribute verticles across multiple JVM processes for true isolation.

#### Implementation
```java
// On each JVM instance
VertxOptions options = new VertxOptions()
    .setClustered(true)
    .setClusterManager(new HazelcastClusterManager());

Vertx.clusteredVertx(options, res -> {
    if (res.succeeded()) {
        Vertx vertx = res.result();
        // Deploy verticles in this JVM
    }
});
```

#### Benefits
- **True Process Isolation**: Each verticle runs in separate JVM
- **Fault Tolerance**: One verticle crash doesn't affect others
- **Memory Isolation**: Separate heap per JVM
- **Scale Horizontally**: Deploy across multiple machines

#### Communication
- **Event Bus**: Message passing between JVMs
- **Shared State**: External cache (Redis, Hazelcast)
- **HTTP/gRPC**: Direct inter-service communication

#### Drawbacks
- **Network Overhead**: Message serialization/deserialization
- **Complexity**: Cluster management, service discovery
- **Latency**: Higher than in-process calls

---

### Strategy 5: **Separate Vert.x Instances (In-Process Multi-Tenancy)**
Create isolated Vert.x instances within same JVM for stronger isolation.

#### Implementation
```java
// Create separate Vert.x instance per tenant/verticle group
Map<String, Vertx> vertxInstances = new ConcurrentHashMap<>();

public void deployIsolatedVerticle(String tenant, String verticleClass) {
    Vertx isolatedVertx = vertxInstances.computeIfAbsent(tenant, k -> Vertx.vertx());

    // Deploy verticle to isolated instance
    isolatedVertx.deployVerticle(verticleClass, new DeploymentOptions());
}
```

#### Benefits
- **Event Loop Isolation**: Separate event loops per instance
- **Thread Pool Isolation**: Dedicated worker pools
- **Failure Isolation**: One Vert.x instance crash doesn't cascade

#### Drawbacks
- **Memory Overhead**: Each Vert.x instance has its own thread pools
- **Resource Multiplication**: `N instances * M threads` = potential thread explosion
- **No Shared Event Bus**: Cannot communicate via internal Event Bus

---

## Resource Sharing Matrix

| Resource Type | Can Share? | Should Share? | Isolation Level | Notes |
|---------------|------------|---------------|-----------------|-------|
| **Vert.x Instance** | ✅ Yes | ✅ Yes | Shared | Single instance handles thousands of verticles efficiently |
| **Event Loop Threads** | ✅ Yes | ✅ Yes | Shared | Vert.x automatically distributes work |
| **Worker Thread Pool** | ✅ Yes | ⚠️ Depends | Configurable | Separate pools avoid noisy neighbor problems |
| **Event Bus** | ✅ Yes | ✅ Yes | Shared | Core communication mechanism |
| **HTTP Server** | ✅ Yes | ✅ Yes | Shared | Single server routes to verticles |
| **URLClassLoader** | ❌ No | ⚠️ Depends | Per-JAR | Required for version isolation |
| **Application Classes** | ❌ No | ❌ No | Per-verticle | Each verticle needs its own instances |
| **Static Fields** | ⚠️ Yes* | ❌ No | Shared* | *Only if loaded by same ClassLoader |
| **File Handles** | ✅ Yes | ⚠️ Depends | OS-level | Watch for concurrent write conflicts |
| **Network Ports** | ❌ No | N/A | Process-level | One listener per port |
| **Database Connections** | ✅ Yes | ✅ Yes | Shared pool | Use connection pool for efficiency |
| **Memory (Heap)** | ✅ Yes | N/A | Shared | All verticles share JVM heap |
| **CPU Cores** | ✅ Yes | ✅ Yes | OS-scheduled | Thread scheduler distributes load |
| **Config Files** | ✅ Yes | ⚠️ Depends | Filesystem | Read-only: safe; Write: coordinate access |
| **Library Dependencies** | ⚠️ Yes* | ⚠️ Depends | Per-ClassLoader* | *Can share via parent ClassLoader |

### Sharing Decision Matrix

```
┌─────────────────────────────────────────────────┐
│ Should I share this resource?                   │
├─────────────────────────────────────────────────┤
│                                                 │
│  Is it stateless?                               │
│    ├─ YES → SHARE (e.g., Vert.x instance)      │
│    └─ NO → Continue...                          │
│                                                 │
│  Is it thread-safe?                             │
│    ├─ YES → CAN SHARE (e.g., ConcurrentHashMap)│
│    └─ NO → DON'T SHARE (e.g., JDBC Connection) │
│                                                 │
│  Does sharing save memory?                      │
│    ├─ YES → SHARE with coordination             │
│    └─ NO → ISOLATE for simplicity               │
│                                                 │
│  Does isolation improve fault tolerance?        │
│    ├─ YES → ISOLATE (e.g., worker pools)       │
│    └─ NO → SHARE                                │
│                                                 │
└─────────────────────────────────────────────────┘
```

---

## Isolation Strategies & Trade-offs

### Isolation Level 1: **Class Namespace Isolation** (Current)
**Mechanism**: URLClassLoader per JAR

```
Isolated:
  ✅ Class namespaces
  ✅ Library versions (if not inherited from parent)
  ✅ Static fields (within loaded classes)

Shared:
  🔄 Vert.x instance
  🔄 Event loop threads
  🔄 Worker threads
  🔄 Heap memory
  🔄 File system
```

**Trade-offs**:
- ✅ **Pro**: Simple implementation, low overhead
- ✅ **Pro**: Different library versions possible
- ❌ **Con**: Memory not isolated (one verticle can OOM entire JVM)
- ❌ **Con**: CPU not isolated (one CPU-heavy verticle affects others)
- ❌ **Con**: Potential ClassLoader memory leaks

---

### Isolation Level 2: **Thread Pool Isolation**
**Mechanism**: Dedicated worker pools per verticle

```java
DeploymentOptions options = new DeploymentOptions()
    .setWorker(true)
    .setWorkerPoolName("verticle-" + verticleId)  // Dedicated pool
    .setWorkerPoolSize(10);
```

```
Isolated:
  ✅ Worker threads
  ✅ Thread-local state
  ✅ Blocking operation impact

Shared:
  🔄 Event loop threads
  🔄 Heap memory
  🔄 CPU scheduling
```

**Trade-offs**:
- ✅ **Pro**: Prevents blocking verticle from starving others
- ✅ **Pro**: Better observability (per-pool metrics)
- ❌ **Con**: Higher thread count (more context switching)
- ❌ **Con**: Fixed pool size may underutilize resources

---

### Isolation Level 3: **Process Isolation** (Clustered)
**Mechanism**: Separate JVM per verticle

```
Isolated:
  ✅ Heap memory
  ✅ Thread pools
  ✅ CPU (via OS scheduler)
  ✅ File descriptors
  ✅ Crash boundaries

Shared:
  🔄 Network ports (distributed)
  🔄 Event Bus (clustered)
  🔄 External resources (DB, cache)
```

**Trade-offs**:
- ✅ **Pro**: True fault isolation
- ✅ **Pro**: Can use JVM-specific tuning per verticle
- ✅ **Pro**: Easy to scale horizontally
- ❌ **Con**: High memory overhead (JVM per verticle)
- ❌ **Con**: Inter-process communication latency
- ❌ **Con**: Complexity in deployment and orchestration

---

### Isolation Level 4: **Container Isolation**
**Mechanism**: Docker containers per verticle

```dockerfile
FROM openjdk:21-slim
COPY vertx-on-demand.jar /app/
COPY micro-verticle-1.jar /app/verticles/
CMD ["java", "-jar", "/app/vertx-on-demand.jar"]
```

```
Isolated:
  ✅ Heap memory (cgroup limits)
  ✅ CPU (cgroup limits)
  ✅ Network namespace
  ✅ Filesystem namespace
  ✅ Process tree

Shared:
  🔄 Kernel
  🔄 Host resources
```

**Trade-offs**:
- ✅ **Pro**: Strong resource limits (memory, CPU, I/O)
- ✅ **Pro**: Deployment portability
- ✅ **Pro**: Orchestration ecosystem (Kubernetes)
- ❌ **Con**: Container overhead (~50-100MB per container)
- ❌ **Con**: Network complexity (service mesh, load balancing)
- ❌ **Con**: Slower startup times

---

## Drawbacks & Considerations

### 1. ClassLoader Memory Leaks
**Problem**: URLClassLoaders may not fully release memory on `close()`

**Causes**:
- Thread-local variables holding class references
- Static fields in loaded classes
- Finalizer threads
- JDBC drivers registering with `DriverManager`

**Symptoms**:
```
java.lang.OutOfMemoryError: Metaspace
```

**Mitigation**:
```java
// Deregister JDBC drivers before closing ClassLoader
Enumeration<Driver> drivers = DriverManager.getDrivers();
while (drivers.hasMoreElements()) {
    Driver driver = drivers.nextElement();
    if (driver.getClass().getClassLoader() == classLoaderToClose) {
        DriverManager.deregisterDriver(driver);
    }
}

// Interrupt threads started by verticle
// Clear ThreadLocal variables
```

---

### 2. Shared Vert.x Instance Limitations
**Problem**: One Vert.x instance = shared event loop and worker pool

**Impacts**:
- **Blocking Code**: One blocking verticle can starve event loop
- **CPU-Heavy Tasks**: Long-running tasks block other verticles
- **Error Propagation**: Unhandled exceptions can crash event loop

**Mitigation**:
```java
// Always use worker verticles for blocking code
DeploymentOptions options = new DeploymentOptions().setWorker(true);

// Set execution time warnings
VertxOptions vertxOptions = new VertxOptions()
    .setBlockedThreadCheckInterval(1000)           // Check every 1s
    .setMaxEventLoopExecuteTime(2 * 1000000000L);  // Warn if >2s
```

---

### 3. Dependency Version Conflicts
**Problem**: Multiple verticles may need different library versions

**Current Behavior**:
```
micro-verticle-1: protobuf-java 3.x
micro-verticle-2: protobuf-java 4.x
Parent ClassLoader: protobuf-java 4.31.1
```

**Result**: Both verticles use version 4.31.1 (parent wins)

**Solution**: Child-first ClassLoader

```java
public class ChildFirstClassLoader extends URLClassLoader {
    private final ClassLoader parent;

    public ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.parent = parent;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // Try child first
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            // Fall back to parent
            return parent.loadClass(name);
        }
    }
}
```

**Drawback**: Can cause subtle bugs if different versions have API changes

---

### 4. Memory Overhead per Verticle
**Current Memory Usage**:

```
Per Verticle Instance:
  - Object allocation: ~1-10 KB
  - ClassLoader metadata: ~50-100 KB
  - Loaded classes: ~500 KB - 2 MB
  - Static fields: ~10-100 KB

Per Vert.x Instance:
  - Event loop threads: ~1 MB per thread
  - Worker threads: ~1 MB per thread
  - Internal buffers: ~10-50 MB

Shared Libraries (deduplicated):
  - vertx-core: ~10 MB
  - protobuf-java: ~2 MB
```

**Calculation Example**:
```
100 verticles with shared Vert.x instance:
  - Vert.x instance: 1 * 50 MB = 50 MB
  - Verticle instances: 100 * 2 MB = 200 MB
  - Event loop threads: 8 * 1 MB = 8 MB
  - Worker pool: 20 * 1 MB = 20 MB
  ─────────────────────────────────────
  Total: ~280 MB (heap + metaspace)

100 verticles with separate Vert.x instances:
  - Vert.x instances: 100 * 50 MB = 5 GB
  - Verticle instances: 100 * 2 MB = 200 MB
  ─────────────────────────────────────
  Total: ~5.2 GB
```

**Recommendation**: Share Vert.x instance unless fault isolation is critical

---

### 5. Thread Explosion Risk
**Problem**: Too many thread pools can degrade performance

**Current Limits**:
```
Default Worker Pool: 20 threads
Event Loop Threads: 2 * CPU cores
```

**If Creating Dedicated Pools**:
```
100 verticles * 10 threads each = 1,000 worker threads
+ 8 event loop threads
+ JVM internal threads (~20)
─────────────────────────────────────
Total: ~1,030 threads
```

**OS Limits**:
- Linux: `ulimit -u` (typically 4096-30000)
- Context switching overhead increases with thread count

**Mitigation**:
```java
// Share worker pool by default
DeploymentOptions options = new DeploymentOptions()
    .setWorker(true)
    // No workerPoolName = use shared worker pool
    .setInstances(4);

// Only create dedicated pool for high-priority or high-contention verticles
```

---

### 6. Event Bus Limitations
**Problem**: Not suitable for high-throughput data transfer

**Overhead**:
- Message serialization (if clustered)
- Queue management
- Message copying (if not clustered)

**Benchmarks** (approximate):
```
In-process Event Bus: ~100,000 msgs/sec
Clustered Event Bus: ~10,000 msgs/sec
Direct method call: ~10,000,000 calls/sec
```

**Recommendation**:
- ✅ Use Event Bus for: Control messages, low-frequency events
- ❌ Avoid for: Streaming data, high-frequency updates
- 🔄 Alternative: Direct interface calls via shared references

---

### 7. Deployment Latency
**Current Deployment Process**:
```
1. Read config JSON: ~1 ms
2. Create URLClassLoader: ~5-10 ms
3. Load verticle class: ~50-200 ms
4. Instantiate verticle: ~1 ms
5. Call start(): ~10-1000 ms (depends on initialization)
─────────────────────────────────────
Total: ~60-1200 ms per verticle
```

**For 100 Verticles**:
- Sequential: ~6-120 seconds
- Parallel (8 threads): ~1-15 seconds

**Optimization**:
```java
// Parallel deployment
ExecutorService executor = Executors.newFixedThreadPool(8);
List<CompletableFuture<Void>> futures = configs.stream()
    .map(config -> CompletableFuture.runAsync(() -> {
        deployVerticle(config);
    }, executor))
    .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

---

### 8. Hot Reload Challenges
**Problem**: Redeploying verticle requires careful cleanup

**Current Approach**:
```java
// Close old ClassLoader
if (classLoaders.containsKey(repo)) {
    classLoaders.get(repo).close();
}
```

**Risks**:
- Old classes not fully unloaded (ClassLoader leak)
- In-flight requests still using old version
- State not persisted between reloads

**Better Approach**:
```java
// Graceful shutdown
public void redeployVerticle(String verticleId) {
    VerticleLifecycle<?> old = deployed.get(verticleId);

    // 1. Stop accepting new requests
    old.stop();

    // 2. Wait for in-flight requests to complete
    Thread.sleep(5000);

    // 3. Close ClassLoader
    classLoaders.get(verticleId).close();

    // 4. Deploy new version
    deployVerticle(verticleId);
}
```

---

## Recommendations

### For Maximum Throughput (100-1000 Verticles)
```java
✅ Use single shared Vert.x instance
✅ Deploy worker verticles with instance count = CPU cores
✅ Share worker pool unless isolation needed
✅ Use hierarchical ClassLoader for shared libraries
✅ Monitor memory usage and tune -Xmx/-Xms
```

**Configuration**:
```java
VertxOptions vertxOptions = new VertxOptions()
    .setWorkerPoolSize(50)  // Shared worker pool
    .setEventLoopPoolSize(Runtime.getRuntime().availableProcessors() * 2);

DeploymentOptions deployOptions = new DeploymentOptions()
    .setWorker(true)
    .setInstances(Runtime.getRuntime().availableProcessors());
```

---

### For Maximum Isolation (10-50 Verticles)
```java
✅ Use dedicated Vert.x instance per tenant/group
✅ Deploy in separate JVMs (clustered mode)
✅ Use child-first ClassLoaders
✅ Dedicated worker pools per verticle
✅ Container-based deployment (Docker/K8s)
```

**Configuration**:
```java
// Per-tenant Vert.x instance
Map<String, Vertx> tenants = new ConcurrentHashMap<>();

String tenantId = getTenantId(request);
Vertx tenantVertx = tenants.computeIfAbsent(tenantId, id -> Vertx.vertx());

// Deploy with isolated pool
DeploymentOptions options = new DeploymentOptions()
    .setWorker(true)
    .setWorkerPoolName("tenant-" + tenantId)
    .setWorkerPoolSize(10);

tenantVertx.deployVerticle(verticle, options);
```

---

### For Development & Testing
```java
✅ Use shared Vert.x instance for simplicity
✅ URLClassLoader per JAR (current approach)
✅ Single worker pool
✅ Enable blocked thread checker
✅ Enable ClassLoader leak detection (JVM flags)
```

**JVM Flags**:
```bash
java -XX:+TraceClassLoading \
     -XX:+TraceClassUnloading \
     -XX:MaxMetaspaceSize=512m \
     -Dvertx.threadChecks=true \
     -jar vertx-on-demand-all.jar
```

---

## Summary Table

| Strategy | Verticle Capacity | Memory Overhead | Fault Isolation | Complexity | Best For |
|----------|------------------|-----------------|-----------------|------------|----------|
| **Single Vert.x + Shared Pool** | 1000+ | Low | Low | Low | High-throughput APIs |
| **Single Vert.x + Dedicated Pools** | 100-500 | Medium | Medium | Medium | Multi-tenant SaaS |
| **Multiple Vert.x Instances** | 50-100 | High | High | Medium | Tenant isolation |
| **Clustered (Multi-JVM)** | 10-50/JVM | Very High | Very High | High | Microservices |
| **Containerized** | 1-10/container | Highest | Highest | Very High | Cloud-native apps |

---

## Next Steps

1. **Benchmark Current Setup**: Deploy 10, 50, 100 verticles and measure:
   - Memory usage (heap + metaspace)
   - CPU utilization
   - Request latency (p50, p95, p99)
   - ClassLoader count

2. **Implement Monitoring**:
   ```java
   // Memory monitoring
   MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
   long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
   long metaspaceUsed = memoryBean.getNonHeapMemoryUsage().getUsed();

   // Thread monitoring
   ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
   int threadCount = threadBean.getThreadCount();
   ```

3. **Test Hot Reload**: Deploy → Redeploy → Check for ClassLoader leaks

4. **Test Under Load**: Use JMeter/Gatling to simulate concurrent requests

5. **Tune JVM**: Based on profiling results:
   ```bash
   -Xms4g -Xmx4g                    # Fixed heap
   -XX:MaxMetaspaceSize=1g          # Limit metaspace
   -XX:+UseG1GC                     # G1 for large heaps
   -XX:MaxGCPauseMillis=200         # Target GC pause time
   ```

---

**Author**: Generated for vertx-on-demand project
**Date**: 2025-11-06
**Version**: 1.0
