package no.communicationflow.inspector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the Spring Inspector MCP server.
 *
 * <p>All properties can be overridden via environment variables (Spring Boot's
 * relaxed binding converts {@code INSPECTOR_ACTUATOR_URL} →
 * {@code inspector.actuator-url}, etc.).</p>
 */
@Data
@ConfigurationProperties(prefix = "inspector")
public class InspectorProperties {

    /**
     * Base URL of the running Spring Boot API actuator.
     * Override with env var {@code INSPECTOR_ACTUATOR_URL}.
     */
    private String actuatorUrl = "http://localhost:18082";

    /**
     * Base URL of the running Prometheus server.
     * Override with env var {@code INSPECTOR_PROMETHEUS_URL}.
     */
    private String prometheusUrl = "http://localhost:9090";

    /**
     * Absolute (or workspace-relative) path to the {@code apps/api} directory.
     * Used as the working directory for Maven sub-process invocations.
     * Override with env var {@code INSPECTOR_API_DIR}.
     */
    private String apiDir = "apps/api";

    /**
     * Regex patterns for property keys whose values must be redacted in the
     * {@code env} tool output.  All patterns are evaluated case-insensitively.
     */
    private String[] secretPatterns = {
            ".*password.*",
            ".*passwd.*",
            ".*secret.*",
            ".*[._-]key[._-].*",
            "\\.key$",
            ".*token.*",
            ".*credential.*",
            ".*private.*",
            ".*\\.cert.*",
            ".*auth.*header.*",
            ".*api[._-]key.*"
    };
}
