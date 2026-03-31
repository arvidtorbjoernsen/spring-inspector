package no.communicationflow.inspector.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import no.communicationflow.inspector.InspectorProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * MCP tools that query the Spring Boot Actuator endpoints of the running API.
 *
 * <p>
 * Requires the API to be running with management endpoints exposed. A typical
 * {@code application.yml} for the api module includes:
 * </p>
 * <pre>
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: "*"
 *   endpoint:
 *     health:
 *       show-details: always
 * </pre>
 *
 * <p>
 * The base URL is configured via {@code inspector.actuator-url} (env var
 * {@code INSPECTOR_ACTUATOR_URL}), defaulting to {@code http://localhost:8080}.
 * </p>
 */
@Component
@Slf4j
public class ActuatorTools {

	private static final Set<String> VALID_LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

	private final RestClient restClient;

	private final JsonMapper mapper;

	private final List<Pattern> secretPatterns;

	private final String actuatorUrl;

	public ActuatorTools(InspectorProperties props, RestClient.Builder restClientBuilder, JsonMapper mapper) {
		this.actuatorUrl = props.getActuatorUrl();
		this.restClient = restClientBuilder.baseUrl(actuatorUrl + "/actuator").build();
		this.mapper = mapper;
		this.secretPatterns = Arrays.stream(props.getSecretPatterns())
			.map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
			.toList();
	}

	// ─── Health & Status ─────────────────────────────────────────────────────────

	@Tool(description = """
			Check the health of the running Spring Boot API.
			Returns overall status (UP/DOWN/OUT_OF_SERVICE) and per-component detail
			(database, redis, diskSpace, etc.) including any relevant details.
			""")
	public String health() {
		return get("/health", json -> {
			String status = field(json, "status");
			StringBuilder sb = new StringBuilder("Health: **").append(status).append("**\n\n");
			JsonNode components = json.path("components");
			if (!components.isMissingNode() && components.isObject()) {
				components.properties().forEach(e -> {
					sb.append("  ").append(e.getKey()).append(": ").append(field(e.getValue(), "status")).append("\n");
					JsonNode details = e.getValue().path("details");
					if (!details.isMissingNode()) {
						details.properties()
							.forEach(d -> sb.append("    ")
								.append(d.getKey())
								.append(": ")
								.append(nodeText(d.getValue()))
								.append("\n"));
					}
				});
			}
			return sb.toString();
		});
	}

	// ─── Auto-configuration ───────────────────────────────────────────────────────

	@Tool(description = """
			Show the Spring Boot auto-configuration conditions evaluation report.
			Returns the top N NEGATIVE matches — beans that were NOT created and why.
			Use to diagnose missing beans, wrong conditions, or misconfigured properties.
			""")
	public String conditions(
			@ToolParam(description = "Max number of negative matches to show (default 20).") Integer topN) {
		int limit = (topN != null && topN > 0) ? topN : 20;
		return get("/conditions", json -> {
			StringBuilder sb = new StringBuilder();
			json.path("contexts").properties().forEach(ctx -> {
				sb.append("Context: ").append(ctx.getKey()).append("\n");
				JsonNode negatives = ctx.getValue().path("negativeMatches");
				List<java.util.Map.Entry<String, JsonNode>> list = new ArrayList<>(negatives.properties());
				sb.append("Negative matches: showing ")
					.append(Math.min(limit, list.size()))
					.append(" of ")
					.append(list.size())
					.append("\n\n");
				list.stream().limit(limit).forEach(e -> {
					sb.append("  ✗ ").append(e.getKey()).append("\n");
					e.getValue()
						.path("notMatched")
						.forEach(cond -> sb.append("      → ")
							.append(field(cond, "condition"))
							.append(": ")
							.append(field(cond, "message"))
							.append("\n"));
				});
			});
			return sb.toString();
		});
	}

	// ─── Beans ────────────────────────────────────────────────────────────────────

	@Tool(description = """
			List Spring beans registered in the application context.
			With filter: shows beans whose name or type contains the filter string.
			Without filter: returns a count summary per context (full list is too large).
			""")
	public String beans(@ToolParam(description = """
			Filter string (optional). Case-insensitive match against bean name or fully-qualified type.
			Examples: 'UserService', 'no.communicationflow', 'RestClient'.""") String filter) {
		return get("/beans", json -> {
			StringBuilder sb = new StringBuilder();
			json.path("contexts").properties().forEach(ctx -> {
				JsonNode beans = ctx.getValue().path("beans");
				if (filter == null || filter.isBlank()) {
					sb.append("Context: ")
						.append(ctx.getKey())
						.append(" — ")
						.append(beans.size())
						.append(" total beans\n")
						.append("Provide a filter to list specific beans.\n\n");
					return;
				}
				String f = filter.toLowerCase();
				List<java.util.Map.Entry<String, JsonNode>> matching = new ArrayList<>();
				beans.properties().forEach(e -> {
					if (e.getKey().toLowerCase().contains(f) || field(e.getValue(), "type").toLowerCase().contains(f)) {
						matching.add(e);
					}
				});
				sb.append("Context: ")
					.append(ctx.getKey())
					.append(" — ")
					.append(matching.size())
					.append(" matching '")
					.append(filter)
					.append("'\n\n");
				matching.forEach(e -> {
					sb.append("  ").append(e.getKey()).append("\n");
					sb.append("    type:  ").append(field(e.getValue(), "type")).append("\n");
					sb.append("    scope: ").append(field(e.getValue(), "scope")).append("\n");
					JsonNode deps = e.getValue().path("dependencies");
					if (deps.isArray() && !deps.isEmpty()) {
						List<String> depList = StreamSupport.stream(deps.spliterator(), false)
							.map(JsonNode::asText)
							.limit(5)
							.collect(Collectors.toList());
						if (deps.size() > 5)
							depList.add("...+" + (deps.size() - 5) + " more");
						sb.append("    deps:  ").append(String.join(", ", depList)).append("\n");
					}
					sb.append("\n");
				});
			});
			return sb.toString();
		});
	}

	// ─── Mappings ────────────────────────────────────────────────────────────────

	@Tool(description = """
			List HTTP request mappings registered with Spring MVC / WebFlux.
			Use to verify which endpoints exist, their paths, methods, and handler classes.
			""")
	public String mappings(@ToolParam(description = """
			Filter string (optional). Case-insensitive match against path pattern or handler class/method.
			Examples: '/api/v1/users', 'UserController', 'POST'. Leave empty to see all.""") String filter) {
		return get("/mappings", json -> {
			StringBuilder sb = new StringBuilder();
			json.path("contexts").properties().forEach(ctx -> {
				ctx.getValue().path("mappings").properties().forEach(srv -> {
					srv.getValue().forEach(mapping -> {
						String handler = mapping.path("handler").asText("");
						String predicate = mapping.path("predicate").asText("?");
						if (filter == null || filter.isBlank() || handler.toLowerCase().contains(filter.toLowerCase())
								|| predicate.toLowerCase().contains(filter.toLowerCase())) {
							sb.append("  ").append(predicate).append("\n");
							sb.append("    → ").append(handler).append("\n");
						}
					});
				});
			});
			return sb.isEmpty() ? "No mappings match filter: " + filter : sb.toString();
		});
	}

	// ─── Environment ─────────────────────────────────────────────────────────────

	@Tool(description = """
			Show application environment properties (application.yml, env vars, system props, etc.).
			Secrets (passwords, tokens, keys, credentials) are automatically redacted.
			Use keysFilter to narrow results to matching property keys.
			""")
	public String env(@ToolParam(description = """
			Key filter (optional). Case-insensitive substring match against property key.
			Examples: 'datasource', 'spring.ai', 'server.port', 'INSPECTOR'.""") String keysFilter) {
		return get("/env", json -> {
			StringBuilder sb = new StringBuilder();
			sb.append("Active profiles: ")
				.append(StreamSupport.stream(json.path("activeProfiles").spliterator(), false)
					.map(JsonNode::asText)
					.collect(Collectors.joining(", ", "[", "]")))
				.append("\n\n");
			json.path("propertySources").forEach(source -> {
				String name = field(source, "name");
				JsonNode props = source.path("properties");
				List<String> lines = new ArrayList<>();
				props.properties().forEach(e -> {
					String key = e.getKey();
					if (keysFilter != null && !keysFilter.isBlank()
							&& !key.toLowerCase().contains(keysFilter.toLowerCase()))
						return;
					String value = isSecret(key) ? "***REDACTED***" : field(e.getValue(), "value");
					lines.add("  " + key + " = " + value);
				});
				if (!lines.isEmpty()) {
					sb.append("── ").append(name).append(" ──\n");
					lines.forEach(l -> sb.append(l).append("\n"));
					sb.append("\n");
				}
			});
			return sb.toString();
		});
	}

	// ─── Loggers ─────────────────────────────────────────────────────────────────

	@Tool(description = """
			Show logger configuration for the running API.
			With loggerName: returns current effective + configured level for that specific logger.
			Without loggerName: lists all loggers that have an explicit (non-default) configured level.
			""")
	public String loggers(@ToolParam(description = """
			Logger name (optional). Examples: 'no.communicationflow', 'org.springframework.security'.
			Leave empty to list all explicitly-configured loggers.""") String loggerName) {
		if (loggerName != null && !loggerName.isBlank()) {
			return get("/loggers/" + loggerName.trim(), json -> {
				String configured = json.path("configuredLevel").asText("null");
				String effective = json.path("effectiveLevel").asText("?");
				return "Logger: %s\n  configured: %s\n  effective:  %s".formatted(loggerName.trim(), configured,
						effective);
			});
		}
		return get("/loggers", json -> {
			StringBuilder sb = new StringBuilder("Explicitly-configured loggers:\n\n");
			json.path("loggers").properties().forEach(e -> {
				String level = field(e.getValue(), "configuredLevel");
				if (!"?".equals(level) && !"null".equalsIgnoreCase(level) && !level.isBlank()) {
					sb.append("  ")
						.append(e.getKey())
						.append("  →  configured=")
						.append(level)
						.append("  effective=")
						.append(field(e.getValue(), "effectiveLevel"))
						.append("\n");
				}
			});
			return sb.toString();
		});
	}

	@Tool(description = """
			Change a logger's level at runtime — no restart required.
			Useful for temporarily enabling DEBUG/TRACE on a specific package to diagnose issues.
			Valid levels: TRACE, DEBUG, INFO, WARN, ERROR, OFF.
			""")
	public String setLogLevel(@ToolParam(
			description = "Fully-qualified logger name. Examples: 'no.communicationflow.api', 'org.springframework.security'.") String loggerName,
			@ToolParam(description = "Target log level: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.") String level) {
		if (loggerName == null || loggerName.isBlank())
			return "❌ loggerName is required.";
		String upper = level != null ? level.trim().toUpperCase() : "";
		if (!VALID_LOG_LEVELS.contains(upper)) {
			return "❌ Invalid level '%s'. Valid values: %s".formatted(level, VALID_LOG_LEVELS);
		}
		try {
			String body = "{\"configuredLevel\":\"%s\"}".formatted(upper);
			restClient.post()
				.uri("/loggers/" + loggerName.trim())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toBodilessEntity();
			return "✅ %s → %s".formatted(loggerName.trim(), upper);
		}
		catch (ResourceAccessException e) {
			return apiDown();
		}
		catch (Exception e) {
			return "❌ Error: " + e.getMessage();
		}
	}

	// ─── Metrics ─────────────────────────────────────────────────────────────────

	@Tool(description = """
			Query application metrics.
			Without name: lists all available metric names (JVM, HTTP, Spring AI, custom, etc.).
			With name: returns the current measurement value(s) and description.
			Common names: 'jvm.memory.used', 'http.server.requests', 'spring.ai.chat.observations'.
			""")
	public String metrics(@ToolParam(
			description = "Metric name (optional). Leave empty to list all metric names.") String metricName) {
		if (metricName != null && !metricName.isBlank()) {
			return get("/metrics/" + metricName.trim(), json -> {
				StringBuilder sb = new StringBuilder();
				sb.append("Metric: ").append(field(json, "name")).append("\n");
				sb.append("  description: ").append(field(json, "description")).append("\n");
				sb.append("  baseUnit:    ").append(field(json, "baseUnit")).append("\n");
				json.path("measurements")
					.forEach(m -> sb.append("  ")
						.append(field(m, "statistic"))
						.append(" = ")
						.append(m.path("value").asDouble())
						.append("\n"));
				return sb.toString();
			});
		}
		return get("/metrics", json -> {
			List<String> names = StreamSupport.stream(json.path("names").spliterator(), false)
				.map(JsonNode::asText)
				.sorted()
				.toList();
			return "Available metrics (%d):\n  %s\n\nCall metrics(name) for details.".formatted(names.size(),
					String.join("\n  ", names));
		});
	}

	// ─── HTTP Exchanges ───────────────────────────────────────────────────────────

	@Tool(description = """
			Show recent HTTP request/response exchanges recorded by the API.
			Requires a org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository
			bean (e.g. InMemoryHttpExchangeRepository) in the api application context.
			Useful for tracing which endpoints were called and with what response codes.
			""")
	public String httpExchanges(
			@ToolParam(description = "Max number of recent exchanges to show (default 20).") Integer limit) {
		int n = (limit != null && limit > 0) ? limit : 20;
		return get("/httpexchanges", json -> {
			List<JsonNode> exchanges = new ArrayList<>();
			json.path("exchanges").forEach(exchanges::add);
			int count = Math.min(n, exchanges.size());
			StringBuilder sb = new StringBuilder(
					"Last %d of %d HTTP exchanges:\n\n".formatted(count, exchanges.size()));
			exchanges.stream().skip(Math.max(0, exchanges.size() - n)).forEach(e -> {
				JsonNode req = e.path("request");
				JsonNode resp = e.path("response");
				sb.append("  ")
					.append(field(req, "method"))
					.append(" ")
					.append(field(req, "uri"))
					.append(" → ")
					.append(resp.path("status").asInt())
					.append("  (")
					.append(field(e, "timeTaken"))
					.append(")\n");
			});
			return sb.toString();
		});
	}

	// ─── Scheduled Tasks ─────────────────────────────────────────────────────────

	@Tool(description = """
			List all @Scheduled methods registered in the API.
			Shows fixed-rate, fixed-delay, and cron tasks together with target method names.
			""")
	public String scheduledTasks() {
		return get("/scheduledtasks", json -> {
			StringBuilder sb = new StringBuilder("Scheduled tasks:\n\n");
			appendTasks(sb, json.path("fixedRate"), "Fixed-rate", "interval");
			appendTasks(sb, json.path("fixedDelay"), "Fixed-delay", "interval");
			appendTasks(sb, json.path("cron"), "Cron", "expression");
			appendTasks(sb, json.path("custom"), "Custom", "trigger");
			return sb.toString();
		});
	}

	// ─── Thread Dump ─────────────────────────────────────────────────────────────

	@Tool(description = """
			Take a thread dump of the running API.
			By default filters out WAITING and TIMED_WAITING threads (the idle pool).
			Use nameFilter to target specific thread groups (e.g. 'http-nio', 'reactor').
			Useful for diagnosing deadlocks, thread pool exhaustion, or blocked threads.
			""")
	public String threadDump(@ToolParam(description = """
			Thread name filter (optional). Case-insensitive substring match.
			Examples: 'http-nio', 'reactor', 'task-scheduler'.
			Leave empty to see all RUNNABLE and BLOCKED threads.""") String nameFilter) {
		return get("/threaddump", json -> {
			StringBuilder sb = new StringBuilder();
			json.path("threads").forEach(t -> {
				String name = field(t, "threadName");
				String state = field(t, "threadState");
				boolean nameMatch = nameFilter == null || nameFilter.isBlank()
						|| name.toLowerCase().contains(nameFilter.toLowerCase());
				boolean stateVisible = nameFilter != null && !nameFilter.isBlank()
						|| (!state.equals("WAITING") && !state.equals("TIMED_WAITING"));
				if (!nameMatch || !stateVisible)
					return;
				sb.append("[").append(state).append("] ").append(name).append("\n");
				t.path("stackTrace")
					.forEach(f -> sb.append("  at ")
						.append(field(f, "className"))
						.append(".")
						.append(field(f, "methodName"))
						.append(" (")
						.append(field(f, "fileName"))
						.append(":")
						.append(f.path("lineNumber").asInt())
						.append(")\n"));
				sb.append("\n");
			});
			return sb.isEmpty() ? "No matching threads." : sb.toString();
		});
	}

	// ─── Helpers ─────────────────────────────────────────────────────────────────

	@FunctionalInterface
	private interface JsonHandler {

		String handle(JsonNode node) throws Exception;

	}

	private String get(String path, JsonHandler handler) {
		try {
			String body = restClient.get()
				.uri(path)
				.retrieve()
				// Don't throw on 4xx/5xx — e.g. actuator returns 503 when a health
				// component is DOWN, but the JSON body is still valid and useful.
				.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
				})
				.body(String.class);
			if (body == null)
				return "Empty response from actuator at " + path;
			return handler.handle(mapper.readTree(body));
		}
		catch (ResourceAccessException e) {
			return apiDown();
		}
		catch (Exception e) {
			log.warn("Actuator call failed [{}]: {}", path, e.getMessage());
			return "❌ Actuator error at %s: %s".formatted(path, e.getMessage());
		}
	}

	private String apiDown() {
		return """
				❌ Cannot reach the API actuator at %s.
				   Is the Spring Boot API running?
				   • Start it:  pnpm nx run api:serve-dev
				   • Test it:   curl %s/actuator/health
				   • Override:  set env var INSPECTOR_ACTUATOR_URL=http://host:port
				""".formatted(actuatorUrl, actuatorUrl);
	}

	private boolean isSecret(String key) {
		return secretPatterns.stream().anyMatch(p -> p.matcher(key).matches());
	}

	private static String field(JsonNode node, String name) {
		return nodeText(node.path(name));
	}

	/**
	 * Jackson 3 throws {@code JsonNodeException} when {@code asText()} is called on a
	 * non-scalar node (array, object). This helper safely converts any node to a string.
	 */
	private static String nodeText(JsonNode node) {
		if (node == null || node.isMissingNode())
			return "?";
		return node.isValueNode() ? node.asText() : node.toString();
	}

	private static void appendTasks(StringBuilder sb, JsonNode tasks, String label, String intervalField) {
		if (!tasks.isMissingNode() && tasks.isArray() && !tasks.isEmpty()) {
			sb.append(label).append(" tasks:\n");
			tasks.forEach(t -> {
				String target = t.path("runnable").path("target").asText(t.path("target").asText("?"));
				String interval = t.path(intervalField).asText(t.path("initialDelay").asText("?"));
				sb.append("  • ").append(target).append("  [").append(interval).append("]\n");
			});
			sb.append("\n");
		}
	}

}
