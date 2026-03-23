# spring-inspector

A Spring Boot MCP server that exposes Maven build/test tools and Spring Boot Actuator inspection tools to GitHub Copilot Agent Mode (or any MCP-compatible client).

## What it does

Provides two sets of MCP tools:

**Build tools** (`mvnCompile`, `mvnTest`, `mvnVerify`, `mvnRun`) — run Maven lifecycle phases against a target project directory via subprocess.

**Actuator tools** (`health`, `beans`, `mappings`, `env`, `conditions`, `metrics`, `loggers`, `setLogLevel`, `httpExchanges`, `scheduledTasks`, `threadDump`) — query a running Spring Boot Actuator endpoint.

## Requirements

- Java 21+
- Maven wrapper (`mvnw`) present in the target API directory

## Build

```bash
./mvnw package -DskipTests -B
# JAR output: target/spring-inspector.jar
```

## Install

```bash
mkdir -p ~/.local/share/spring-inspector
cp target/spring-inspector.jar ~/.local/share/spring-inspector/
```

## VS Code MCP registration

Add to `.vscode/mcp.json` in your workspace:

```json
"spring-inspector": {
  "type": "stdio",
  "command": "java",
  "args": ["-jar", "/Users/<you>/.local/share/spring-inspector/spring-inspector.jar"],
  "env": {
    "INSPECTOR_ACTUATOR_URL": "http://localhost:18081",
    "INSPECTOR_API_DIR": "${workspaceFolder}/apps/api",
    "INSPECTOR_LOG_FILE": "/Users/<you>/.local/share/spring-inspector/spring-inspector.log"
  }
}
```

## Environment variables

| Variable | Description | Default |
|---|---|---|
| `INSPECTOR_ACTUATOR_URL` | Base URL of the running Actuator endpoint | `http://localhost:8080` |
| `INSPECTOR_API_DIR` | Absolute path to the Maven project to build/test | *(required)* |
| `INSPECTOR_LOG_FILE` | Path for the log file (keeps STDIO clean) | `/tmp/spring-inspector.log` |

## Tech stack

- Spring Boot 4.0.3
- Spring AI 2.0.0-M2 (`spring-ai-starter-mcp-server`)
- Java 21, Lombok
