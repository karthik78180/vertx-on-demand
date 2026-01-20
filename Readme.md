# Vert.x On-Demand Server

This is the main Vert.x 5 server that dynamically deploys and undeploys verticles from external JARs. It provides centralized dependency injection using Guice, allowing all verticles to share common initialization and resources.

## Features

- **Dynamic Verticle Deployment**: Load and deploy verticles from external JARs at runtime
- **Guice Dependency Injection**: Built-in DI support for verticles
- **Centralized Initialization**: Single shared `InitializationService` for all verticles
- **Resource Caching**: Shared cache for common data across verticles
- **Metrics Tracking**: Built-in request and error counting
- **Comprehensive Logging**: SLF4J/Logback integration

## Requirements

- Java 21
- Gradle 9.3.0
- Vert.x 5.0.7
- Guice 5.1.0

## Building and Running

### Build the Project
```bash
./gradlew clean build
```

### Run the Server
```bash
./gradlew run
```

The server will start on `localhost:8080`.

## API Endpoints

### Deploy a Verticle
```bash
curl --location 'http://localhost:8080/deploy' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer {{bearerToken}}' \
--data '{ "repo": "micro-verticle-1" }'
```

### Undeploy a Verticle
```bash
curl --location 'http://localhost:8080/undeploy' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer {{bearerToken}}' \
--data '{ "repo": "micro-verticle-1" }'
```

### Example Request Endpoints
Once a verticle is deployed, you can access its endpoints. Examples:

#### JSON Request
```bash
curl --location 'http://localhost:8080/ReadHelloWorld.v1' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer {{bearerToken}}' \
--data '{
    "name": "Sri"
}'
```

#### Protobuf Request
```bash
curl --location 'http://localhost:8080/GreetProto.v1' \
--header 'Content-Type: application/octet-stream' \
--header 'Authorization: Bearer {{bearerToken}}' \
--data-binary '@/C:/Users/KAVUR/one-data/micro-verticle-1/src/test/resources/greet-request.bin'
```

## Project Structure

### Key Components

- `src/main/java/com/example/api/VerticleLifecycle.java`: Core interface that all deployable verticles must implement
- `src/main/java/com/example/MainVerticle.java`: Main server verticle that sets up HTTP routing
- `src/main/java/com/example/DeploymentHandler.java`: Handles verticle deployment/undeployment logic

### Verticle Configuration

Each deployable verticle must have a configuration JSON file in its `config` directory:

```json
{
  "artifactId": "verticle-name",
  "version": "1.0.0",
  "description": "Description of the verticle",
  "verticleClass": "com.example.verticles.YourVerticle"
}
```

## Guice Dependency Injection Model

The server provides automatic dependency injection for verticles using Guice. Each verticle can declare dependencies via constructor injection, and the server will automatically instantiate them.

### Verticle Lifecycle with DI

```
1. Verticle Class is Loaded
       ↓
2. Guice Instantiates Verticle (constructor injection)
       ↓
3. start(Vertx, JsonObject) is called
       ↓
4. init() is called (if verticle implements VerticleInitializer)
       ↓
5. handle(RoutingContext) processes requests
       ↓
6. shutdown() is called during undeployment (if verticle implements VerticleInitializer)
       ↓
7. stop() is called
```

### Architecture

```
DeploymentHandler (creates Guice Injector)
    ↓
VerticleModule (configures DI bindings)
    ↓
Verticle instantiation with automatic injection
    ↓
Each verticle gets:
  - InitializationService (shared)
  - Other injected dependencies
```

### Key Components

- **`InitializationService`**: Core DI provider
  - Provides Vertx instance
  - Provides global configuration
  - Use this in your verticles' constructors for DI

- **`VerticleInitializer`**: Optional interface for lifecycle hooks
  - `init()`: Called once per verticle before first request
  - `shutdown()`: Called during undeployment for cleanup

- **`VerticleModule`**: Guice module that configures DI
  - Binds InitializationService as a singleton
  - Provides Vertx and configuration to verticles

- **`DeploymentHandler`**: Manages verticle deployment with DI
  - Creates Guice Injector and uses it for instantiation
  - Calls init() and shutdown() hooks automatically

## Development

To create a deployable verticle with Guice DI support:

### Option 1: Simple Verticle (No DI)

If your verticle doesn't need any dependencies:

```java
public class SimpleVerticle implements VerticleLifecycle<JsonObject> {
    // No constructor needed - no dependencies

    @Override
    public void start(Vertx vertx, JsonObject config) {
        System.out.println("Started: " + config);
    }

    @Override
    public void handle(RoutingContext context) {
        // Handle request
    }

    @Override
    public void stop() {
        // Cleanup
    }
}
```

### Option 2: Verticle with DI and Resource Initialization (Recommended)

To use Guice DI with initialization and shutdown hooks:

```java
public class SmartVerticle implements VerticleLifecycle<JsonObject>, VerticleInitializer {
    private static final Logger logger = LoggerFactory.getLogger(SmartVerticle.class);

    // Dependencies injected by Guice
    private final InitializationService initService;

    // Verticle-specific resources initialized in init()
    private DatabaseConnection dbConnection;

    // Constructor with dependency injection - Guice will call this
    public SmartVerticle(InitializationService initService) {
        this.initService = initService;
    }

    @Override
    public void start(Vertx vertx, JsonObject config) {
        logger.info("Verticle starting with config: {}", config);
        // Validate configuration if needed
    }

    @Override
    public void init() throws Exception {
        logger.info("Initializing resources");
        // Initialize resources once, before first request
        dbConnection = new DatabaseConnection(
            initService.getGlobalConfig().getString("db_url")
        );
        dbConnection.connect();
    }

    @Override
    public void handle(RoutingContext context) {
        try {
            // Use initialized resource
            ResultSet results = dbConnection.query("SELECT * FROM users");
            context.response().end(results.toJson().encodePrettily());
        } catch (Exception e) {
            context.response().setStatusCode(500).end("Error: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        logger.info("Verticle stopping");
    }

    @Override
    public void shutdown() throws Exception {
        logger.info("Shutting down - cleaning up resources");
        // Cleanup resources initialized in init()
        if (dbConnection != null) {
            dbConnection.disconnect();
        }
    }
}
```

### Steps to Create a Verticle

1. Create a new Gradle project
2. Add the vertx-on-demand dependency:
   ```gradle
   implementation 'com.example:vertx-on-demand:1.0.0'
   ```
3. Implement `VerticleLifecycle<T>` interface
4. (Optional) Implement `VerticleInitializer` for init/shutdown hooks
5. (Optional) Add constructor accepting dependencies for Guice injection
6. Add configuration JSON in `config` directory:
   ```json
   {
     "verticleClass": "com.example.verticles.YourVerticle",
     "initClass": "com.example.init.CommonInit",
     "address": "/your-endpoint.v1"
   }
   ```
   - `verticleClass`: Your verticle implementation (required)
   - `initClass`: Shared initialization class, loaded once per repo (optional)
   - `address`: HTTP endpoint path (required)
7. Build with `shadowJar` to create a fat JAR

### Centralized Initialization with initClass

For multiple verticles in the same repository, use `initClass` to initialize shared resources once:

```java
// CommonInit.java - Loaded ONCE when repository is deployed
public class CommonInit implements VerticleInitializable {
    private static DatabasePool dbPool;

    @Override
    public void initialize(Vertx vertx, JsonObject config) throws Exception {
        // Initialize shared resources ONCE
        String dbUrl = config.getString("db_url");
        dbPool = new DatabasePool(dbUrl);
        dbPool.connect();
    }

    @Override
    public void shutdown() throws Exception {
        if (dbPool != null) {
            dbPool.disconnect();
        }
    }

    // Provide static access for all verticles
    public static DatabasePool getDbPool() {
        return dbPool;
    }
}
```

Then in all your verticles, access the shared resource:

```java
// MyVerticle.java - Uses shared resources from CommonInit
public class MyVerticle implements VerticleLifecycle<JsonObject> {
    @Override
    public void handle(RoutingContext context) {
        // Use shared resource - no duplicate initialization
        CommonInit.getDbPool().query("SELECT * FROM users")
            .onSuccess(result -> context.response().end(result.toJson()))
            .onFailure(err -> context.response().setStatusCode(500).end(err.getMessage()));
    }
}
```

**Deployment Flow with initClass:**
1. Load JAR file
2. Load and instantiate initClass → call initialize() once
3. Load and instantiate all verticles → call init() on each (if VerticleInitializer)
4. Start handling requests
5. On undeploy: call shutdown() on verticles, then on initClass

### Using Guice Injection

Any class available to Guice can be injected into your verticle constructor:

```java
public class MyVerticle implements VerticleLifecycle<JsonObject>, VerticleInitializer {
    private final InitializationService initService;
    private final MyCustomService customService;

    // Guice will inject both dependencies
    public MyVerticle(InitializationService initService, MyCustomService customService) {
        this.initService = initService;
        this.customService = customService;
    }

    @Override
    public void init() throws Exception {
        // Initialize resources before first request
    }

    @Override
    public void handle(RoutingContext context) {
        // Use injected services
    }

    @Override
    public void shutdown() throws Exception {
        // Cleanup resources
    }
}
```

To make `MyCustomService` injectable, add it to your verticle's Guice module or bind it in a custom module.

### Benefits of Guice DI

- ✅ **Automatic Injection**: Dependencies are injected automatically by Guice
- ✅ **Control Over Initialization**: Each verticle controls when to initialize its resources
- ✅ **Proper Cleanup**: shutdown() ensures resources are cleaned up during undeployment
- ✅ **Per-Verticle Resources**: Each verticle can have its own initialized resources
- ✅ **Testability**: Easy to mock dependencies for unit tests
- ✅ **Flexibility**: Verticles can declare any dependencies they need

## How to Run

### 1. Publish Server Interface to Local Maven
```cmd
cd vertx-on-demand
gradlew clean publishToMavenLocal
```
### 2. Start the Server
```cmd
gradlew clean shadowJar
java -jar build\\libs\\vertx-on-demand-all.jar
```
Server starts at: http://localhost:8080

## API Endpoints
### Deploy a Verticle
```cmd
curl.exe -X POST http://localhost:8080/deploy ^
 -H "Content-Type: application/json" ^
 -d "{ \"repo\": \"micro-verticle-1\" }"
```

### Undeploy a Verticle
```cmd
curl -X POST http://localhost:8080/undeploy ^
 -H "Content-Type: application/json" ^
 -d "{ \"repo\": \"micro-verticle-1\" }"
```

### Call the Verticle's Handler
```cmd
curl.exe -X POST http://localhost:8080/ReadHelloWorld.v1
```

