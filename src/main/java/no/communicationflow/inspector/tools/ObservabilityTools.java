package no.communicationflow.inspector.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import no.communicationflow.inspector.InspectorProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * MCP tools for Phase 26 observability verification.
 *
 * <p>
 * These tools query the self-hosted Prometheus instance and combine it with the API
 * actuator metrics endpoint so Phase 26 checks can be repeated without manually clicking
 * through Grafana for the basic scrape/export validation.
 * </p>
 */
@Component
@Slf4j
public class ObservabilityTools {

	private static final List<String> REQUIRED_ACTUATOR_METRICS = List.of("grpc.server.requests",
			"grpc.server.duration", "api.rate_limit.rejected", "cache.gets");

	private static final List<String> REQUIRED_PROMETHEUS_JOBS = List.of("api", "auth-service", "postgres");

	private static final List<String> RESILIENCE4J_PREFIXES = List.of("resilience4j.", "resilience4j_");

	private final RestClient actuatorClient;

	private final RestClient prometheusClient;

	private final JsonMapper mapper;

	private final String actuatorUrl;

	private final String prometheusUrl;

	public ObservabilityTools(InspectorProperties props, RestClient.Builder restClientBuilder, JsonMapper mapper) {
		this.actuatorUrl = props.getActuatorUrl();
		this.prometheusUrl = props.getPrometheusUrl();
		this.actuatorClient = restClientBuilder.baseUrl(actuatorUrl + "/actuator").build();
		this.prometheusClient = restClientBuilder.baseUrl(prometheusUrl).build();
		this.mapper = mapper;
	}

	@Tool(description = """
			Show Prometheus scrape target health for the local observability stack.
			Useful for checking whether api, auth-service, and postgres-exporter are UP
			before drilling into Grafana dashboards.
			""")
	public String prometheusTargets() {
		return getPrometheus("/api/v1/targets", ObservabilityTools::renderPrometheusTargets);
	}

	@Tool(description = """
			Run an instant PromQL query against Prometheus and render a concise summary.
			Example queries: 'up', 'up{job="api"}', 'sum(rate(http_server_requests_seconds_count[5m]))'.
			""")
	public String prometheusQuery(
			@ToolParam(description = "PromQL expression to execute against /api/v1/query.") String promQl) {
		if (promQl == null || promQl.isBlank()) {
			return "❌ promQl is required.";
		}
		return getPrometheus(uriBuilder -> uriBuilder.path("/api/v1/query").queryParam("query", promQl).build(),
				json -> renderPrometheusQuery(promQl, json));
	}

	@Tool(description = """
			Run the core Phase 26 observability verification checklist.
			Combines actuator metric discovery with Prometheus target health so you can
			quickly confirm that the API exports the expected Micrometer metrics and that
			Prometheus is scraping the expected jobs.
			""")
	public String phase26ObservabilityStatus() {
		try {
			JsonNode metricsJson = readActuatorMetricsJson();
			JsonNode targetsJson = readPrometheusJson(uriBuilder -> uriBuilder.path("/api/v1/targets").build());
			return renderPhase26ObservabilityStatus(metricsJson, targetsJson, actuatorUrl, prometheusUrl);
		}
		catch (ResourceAccessException e) {
			return connectionFailure(e);
		}
		catch (Exception e) {
			log.warn("Phase 26 observability verification failed: {}", e.getMessage());
			return "❌ Phase 26 observability verification failed: " + e.getMessage();
		}
	}

	static String renderPhase26ObservabilityStatus(JsonNode actuatorMetricsJson, JsonNode prometheusTargetsJson,
			String actuatorUrl, String prometheusUrl) {
		Set<String> metricNames = actuatorMetricNames(actuatorMetricsJson);
		List<TargetSummary> targets = activeTargets(prometheusTargetsJson);

		StringBuilder sb = new StringBuilder("Phase 26 observability status\n\n");
		sb.append("Actuator:   ").append(actuatorUrl).append("/actuator\n");
		sb.append("Prometheus: ").append(prometheusUrl).append("\n\n");

		sb.append("Actuator metric export\n");
		for (String requiredMetric : REQUIRED_ACTUATOR_METRICS) {
			boolean present = metricNames.contains(requiredMetric);
			sb.append(present ? "  ✅ " : "  ❌ ")
				.append(requiredMetric)
				.append(present ? " present" : " missing")
				.append("\n");
		}

		boolean resilience4jPresent = metricNames.stream().anyMatch(ObservabilityTools::isResilience4jMetric);
		sb.append(resilience4jPresent ? "  ✅ " : "  ❌ ")
			.append("resilience4j.* metrics ")
			.append(resilience4jPresent ? "present" : "missing")
			.append("\n\n");

		sb.append("Prometheus scrape jobs\n");
		Map<String, List<TargetSummary>> targetsByJob = targets.stream()
			.collect(Collectors.groupingBy(TargetSummary::job));
		for (String job : REQUIRED_PROMETHEUS_JOBS) {
			List<TargetSummary> jobTargets = targetsByJob.getOrDefault(job, List.of());
			boolean up = !jobTargets.isEmpty() && jobTargets.stream().allMatch(t -> "up".equalsIgnoreCase(t.health()));
			sb.append(up ? "  ✅ " : "  ❌ ")
				.append(job)
				.append(jobTargets.isEmpty() ? " missing" : up ? " up" : " not fully up")
				.append("\n");
			for (TargetSummary target : jobTargets) {
				sb.append("     - ")
					.append(target.health().toUpperCase(Locale.ROOT))
					.append(" ")
					.append(target.scrapeUrl());
				if (!target.lastError().isBlank()) {
					sb.append(" (error: ").append(target.lastError()).append(")");
				}
				sb.append("\n");
			}
		}

		sb.append("\nRemaining manual items\n");
		sb.append("  • Sentry event capture still requires live project/DSN validation.\n");
		sb.append("  • Tempo/Loki trace drilldown still needs a traced request and Grafana-side confirmation.\n");
		return sb.toString();
	}

	static String renderPrometheusTargets(JsonNode json) {
		List<TargetSummary> targets = activeTargets(json);
		if (targets.isEmpty()) {
			return "Prometheus targets: none returned.";
		}

		StringBuilder sb = new StringBuilder("Prometheus active targets\n\n");
		targets.stream()
			.sorted(Comparator.comparing(TargetSummary::job).thenComparing(TargetSummary::scrapeUrl))
			.forEach(target -> {
				sb.append("  ")
					.append("up".equalsIgnoreCase(target.health()) ? "✅" : "❌")
					.append(" ")
					.append(target.job())
					.append(" → ")
					.append(target.scrapeUrl())
					.append(" [health=")
					.append(target.health())
					.append("]");
				if (!target.lastError().isBlank()) {
					sb.append(" lastError=").append(target.lastError());
				}
				sb.append("\n");
			});
		return sb.toString();
	}

	static String renderPrometheusQuery(String promQl, JsonNode json) {
		JsonNode data = json.path("data");
		String resultType = text(data.path("resultType"), "?");
		JsonNode result = data.path("result");

		StringBuilder sb = new StringBuilder("Prometheus query\n\n");
		sb.append("query: ").append(promQl).append("\n");
		sb.append("resultType: ").append(resultType).append("\n\n");

		if (!result.isArray() || result.isEmpty()) {
			sb.append("No samples returned.");
			return sb.toString();
		}

		int count = 0;
		for (JsonNode sample : result) {
			if (count++ == 20) {
				sb.append("… ").append(result.size() - 20).append(" more samples\n");
				break;
			}
			String labels = StreamSupport.stream(sample.path("metric").properties().spliterator(), false)
				.map(e -> e.getKey() + "=" + text(e.getValue(), ""))
				.sorted()
				.collect(Collectors.joining(", "));
			JsonNode value = sample.path("value");
			String renderedValue = value.isArray() && value.size() >= 2 ? text(value.get(1), "?") : value.toString();
			sb.append("  • ")
				.append(labels.isBlank() ? "<no labels>" : labels)
				.append(" => ")
				.append(renderedValue)
				.append("\n");
		}
		return sb.toString();
	}

	static Set<String> actuatorMetricNames(JsonNode json) {
		return StreamSupport.stream(json.path("names").spliterator(), false)
			.map(node -> text(node, ""))
			.collect(Collectors.toCollection(TreeSet::new));
	}

	static List<TargetSummary> activeTargets(JsonNode json) {
		List<TargetSummary> targets = new ArrayList<>();
		JsonNode activeTargets = json.path("data").path("activeTargets");
		if (!activeTargets.isArray()) {
			return targets;
		}
		activeTargets.forEach(target -> {
			String job = text(target.path("labels").path("job"), "?");
			String health = text(target.path("health"), "unknown");
			String scrapeUrl = text(target.path("scrapeUrl"), "?");
			String lastError = text(target.path("lastError"), "");
			targets.add(new TargetSummary(job, health, scrapeUrl, lastError));
		});
		return targets;
	}

	static boolean isResilience4jMetric(String name) {
		return RESILIENCE4J_PREFIXES.stream().anyMatch(name::startsWith);
	}

	private JsonNode readActuatorMetricsJson() {
		String body = actuatorClient.get()
			.uri("/metrics")
			.retrieve()
			.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
			})
			.body(String.class);
		if (body == null) {
			throw new IllegalStateException("Empty response from actuator at /metrics");
		}
		return mapper.readTree(body);
	}

	private JsonNode readPrometheusJson(
			java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn) {
		String body = prometheusClient.get()
			.uri(uriFn)
			.retrieve()
			.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
			})
			.body(String.class);
		if (body == null) {
			throw new IllegalStateException("Empty response from Prometheus");
		}
		JsonNode json = mapper.readTree(body);
		if (!"success".equalsIgnoreCase(text(json.path("status"), "success"))) {
			throw new IllegalStateException("Prometheus returned status=" + text(json.path("status"), "?"));
		}
		return json;
	}

	private String getPrometheus(String path, JsonHandler handler) {
		return getPrometheus(uriBuilder -> uriBuilder.path(path).build(), handler);
	}

	private String getPrometheus(
			java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn,
			JsonHandler handler) {
		try {
			return handler.handle(readPrometheusJson(uriFn));
		}
		catch (ResourceAccessException e) {
			return connectionFailure(e);
		}
		catch (Exception e) {
			log.warn("Prometheus call failed: {}", e.getMessage());
			return "❌ Prometheus error at %s: %s".formatted(prometheusUrl, e.getMessage());
		}
	}

	private String connectionFailure(ResourceAccessException e) {
		String message = e.getMessage() == null ? "connection refused" : e.getMessage();
		return "❌ Cannot reach actuator or Prometheus (%s, %s): %s".formatted(actuatorUrl, prometheusUrl, message);
	}

	@FunctionalInterface
	private interface JsonHandler {

		String handle(JsonNode json);

	}

	record TargetSummary(String job, String health, String scrapeUrl, String lastError) {
	}

	private static String text(JsonNode node, String fallback) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return fallback;
		}
		String value = node.toString();
		if (node.isValueNode() && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		return value == null || value.isBlank() ? fallback : value;
	}

}
