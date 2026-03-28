package no.communicationflow.inspector.tools;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityToolsTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    @Test
    void renderPrometheusTargets_includesHealthAndErrors() {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "activeTargets": [
                      {
                        "health": "up",
                        "scrapeUrl": "http://api:8080/actuator/prometheus",
                        "labels": {"job": "api"},
                        "lastError": ""
                      },
                      {
                        "health": "down",
                        "scrapeUrl": "http://host.docker.internal:9000/actuator/prometheus",
                        "labels": {"job": "auth-service"},
                        "lastError": "connection refused"
                      }
                    ]
                  }
                }
                """;

        String rendered = ObservabilityTools.renderPrometheusTargets(MAPPER.readTree(json));

        assertThat(rendered)
                .contains("✅ api → http://api:8080/actuator/prometheus [health=up]")
                .contains("❌ auth-service → http://host.docker.internal:9000/actuator/prometheus [health=down] lastError=connection refused");
    }

    @Test
    void renderPrometheusQuery_listsReturnedSamples() {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": [
                      {
                        "metric": {"job": "api", "instance": "api:8080"},
                        "value": [1711576800.0, "1"]
                      }
                    ]
                  }
                }
                """;

        String rendered = ObservabilityTools.renderPrometheusQuery("up{job=\"api\"}", MAPPER.readTree(json));

        assertThat(rendered)
                .contains("query: up{job=\"api\"}")
                .contains("resultType: vector")
                .contains("instance=api:8080, job=api => 1");
    }

    @Test
    void renderPhase26ObservabilityStatus_reportsMissingAndPresentChecks() {
        String metricsJson = """
                {
                  "names": [
                    "grpc.server.requests",
                    "grpc.server.duration",
                    "api.rate_limit.rejected",
                    "cache.gets",
                    "resilience4j.retry.calls"
                  ]
                }
                """;
        String targetsJson = """
                {
                  "status": "success",
                  "data": {
                    "activeTargets": [
                      {
                        "health": "up",
                        "scrapeUrl": "http://api:8080/actuator/prometheus",
                        "labels": {"job": "api"},
                        "lastError": ""
                      },
                      {
                        "health": "up",
                        "scrapeUrl": "http://auth-service:9000/actuator/prometheus",
                        "labels": {"job": "auth-service"},
                        "lastError": ""
                      },
                      {
                        "health": "up",
                        "scrapeUrl": "http://postgres-exporter:9187/metrics",
                        "labels": {"job": "postgres"},
                        "lastError": ""
                      }
                    ]
                  }
                }
                """;

        String rendered = ObservabilityTools.renderPhase26ObservabilityStatus(
                MAPPER.readTree(metricsJson),
                MAPPER.readTree(targetsJson),
                "http://localhost:8080",
                "http://localhost:9090");

        assertThat(rendered)
                .contains("✅ grpc.server.requests present")
                .contains("✅ resilience4j.* metrics present")
                .contains("✅ api up")
                .contains("✅ auth-service up")
                .contains("✅ postgres up")
                .contains("Remaining manual items");
    }
}


