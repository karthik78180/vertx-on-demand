# Vert.x On-Demand Server

This is the main Vert.x 5 server that dynamically deploys and undeploys verticles from external JARs. It also defines the interface `VerticleLifecycle` used by all micro-verticles.

## Requirements

- Java 21
- Gradle 8.13
- Windows 11 (use `\\` for file paths)
- Vert.x 5.0.1

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

## Development

To create a new deployable verticle:

1. Create a new project using Gradle
2. Add the vertx-on-demand dependency
3. Implement the `VerticleLifecycle` interface
4. Add configuration JSON in the `config` directory
5. Build with `shadowJar` to create a fat JAR

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

