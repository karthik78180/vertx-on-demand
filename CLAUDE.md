# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Vert.x On-Demand Server** is a Vert.x 5 framework that enables dynamic deployment and undeployment of verticles from external JAR files. It provides HTTP endpoints for managing verticle lifecycle and routing requests to deployed verticles. The project also defines the `VerticleLifecycle` interface that all deployable micro-verticles must implement.

### Requirements
- Java 25
- Gradle 8.13 or later
- Vert.x 5.0.11

## Common Commands

### Build
```bash
./gradlew clean build
```

### Build with Fat JAR (shadowJar)
```bash
./gradlew shadowJar
```

### Run Server
```bash
./gradlew run
```
Server starts on `http://localhost:8080`

### Run All Tests
```bash
./gradlew test
```

### Run a Single Test Class
```bash
./gradlew test --tests ClassName
```

### Run a Single Test Method
```bash
./gradlew test --tests ClassName.methodName
```

### Publish Interface to Local Maven
```bash
./gradlew clean publishToMavenLocal
```
Required for micro-verticles to depend on `VerticleLifecycle` interface.

## Architecture

### Core Components

**MainServer** (`src/main/java/com/example/MainServer.java`)
- Entry point for the application
- Creates HTTP server on port 8080
- Sets up routing for `/deploy`, `/undeploy`, and dynamic request handlers
- Initializes `DeploymentHandler`

**DeploymentHandler** (`src/main/java/com/example/DeploymentHandler.java`)
- Core orchestration logic for the dynamic deployment system
- Manages `deployed` map (address → VerticleLifecycle instance)
- Manages `classLoaders` map (repo → URLClassLoader) for memory management
- Three main methods:
  - `deploy()`: Loads verticles from external JARs using custom URLClassLoader, reads config JSON, instantiates verticles
  - `undeploy()`: Stops all deployed verticles and closes classloaders
  - `handle()`: Routes incoming requests to deployed verticles with request validation

**VerticleLifecycle<T>** (`src/main/java/com/example/api/VerticleLifecycle.java`)
- Generic interface that all deployable verticles must implement
- Three lifecycle methods:
  - `start(Vertx vertx, JsonObject config)`: Called on deployment
  - `handle(RoutingContext context)`: Handles incoming requests
  - `stop()`: Called on undeployment

**RequestValidator** (`src/main/java/com/example/validator/RequestValidator.java`)
- Validates incoming requests, particularly protobuf payloads
- Enforces 1MB size limit for protobuf requests (`MAX_PROTOBUF_SIZE`)
- Uses defensive null checks for compatibility with unit tests
- Returns HTTP 413 if payload exceeds limit

### Key Architectural Patterns

1. **Dynamic Class Loading**: Verticles are loaded from external JARs using `URLClassLoader` with parent classloader isolation
2. **Configuration-Driven Deployment**: Each verticle has a JSON config file specifying:
   - `verticleClass`: Fully qualified class name implementing `VerticleLifecycle`
   - `address`: URL path to access the verticle (e.g., "ReadHelloWorld.v1")
   - Other custom configuration passed to `start()`
3. **Concurrent Tracking**: Uses `ConcurrentHashMap` for thread-safe management of deployed verticles and classloaders
4. **Request Routing**: Requests to `/:address` are routed to the corresponding deployed verticle

## Testing

### Test Structure
- **DeploymentHandlerTest**: Unit tests for deployment/undeployment and request handling
- **RequestValidatorTest**: Validation tests for request size limits
- Uses Mockito with lenient stubbing mode (`@MockitoSettings(strictness = Strictness.LENIENT)`)
- Vert.x JUnit5 extension for testing (`io.vertx:vertx-junit5`)

### Testing Notes
- Tests use mocks for Vertx and RoutingContext
- RequestValidator has defensive null checks to handle mock objects gracefully
- The LENIENT strictness setting allows for flexible mocking in complex integration scenarios

## Gradle Configuration

**Important Gradle Details**:
- Main class: `com.example.MainServer`
- Shadow JAR classifier: "all" (creates fat JAR as `vertx-on-demand-all.jar`)
- Java toolchain enforced at Java 21
- Test platform: JUnit Platform (JUnit 5)
- Test logging shows all passed, skipped, and failed tests

**Dependencies**:
- Vert.x Core and Web (5.0.11)
- Protocol Buffers for message serialization (4.34.1)
- JUnit 6, Mockito 5.23.0, Vert.x JUnit5 for testing

## Development Workflow

### Creating a New Verticle
When creating a new micro-verticle project that will be deployed by this server:

1. Create a new Gradle project
2. Add dependency on `vertx-on-demand` (publish this project to local Maven first)
3. Implement `VerticleLifecycle<T>` interface
4. Create config JSON file in `config/` directory specifying the class and address
5. Build with shadowJar to create fat JAR

### Deploying a Verticle
```bash
curl -X POST http://localhost:8080/deploy \
  -H "Content-Type: application/json" \
  -d '{"repo": "micro-verticle-1"}'
```

Deployment expects:
- JAR file at: `../micro-verticle-1/build/libs/micro-verticle-1-1.0.0-all.jar`
- Config files at: `../micro-verticle-1/config/` (*.json)

### Calling a Deployed Verticle
Once deployed with address "ReadHelloWorld.v1":
```bash
curl -X POST http://localhost:8080/ReadHelloWorld.v1 \
  -H "Content-Type: application/json" \
  -d '{"name": "Sri"}'
```

## Key Implementation Details

### Memory Management
- When deploying the same repo twice, the old URLClassLoader is closed first to prevent memory leaks
- On deployment failure, the classloader is cleaned up immediately
- Undeployment closes all classloaders and clears both maps

### Error Handling
- Deployment returns HTTP 400 if config or JAR files are missing
- Deployment returns HTTP 500 with error message on other failures
- Request handling returns HTTP 404 if verticle address not found
- RequestValidator returns HTTP 413 for oversized protobuf payloads

### Null Safety
- RequestValidator includes defensive null checks for RoutingContext, request, headers, and body
- This is necessary because mocks in tests may not fully implement Vert.x interfaces

## Useful Git References
Recent commits show focus on:
- Gradle version updates and build optimization
- Protobuf validator improvements with defensive null checks
- Test resilience improvements with lenient Mockito stubbing
