package no.communicationflow.inspector;

import no.communicationflow.inspector.tools.ActuatorTools;
import no.communicationflow.inspector.tools.BuildTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Manual bean definitions for infrastructure that is normally auto-configured by the
 * servlet web context.
 *
 * <p>
 * Because the inspector runs as a STDIO MCP server
 * ({@code spring.main.web-application-type=none}), Spring Boot's
 * {@code RestClientAutoConfiguration} is not activated — it requires a servlet web
 * application condition. We therefore define the {@link RestClient.Builder} bean
 * ourselves.
 * </p>
 *
 * <p>
 * Additionally, Spring AI's MCP server does not auto-discover {@code @Tool} annotations —
 * we must explicitly register the tool objects via a {@link ToolCallbackProvider} bean.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class InspectorConfig {

	@Bean
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	ToolCallbackProvider toolCallbackProvider(BuildTools buildTools, ActuatorTools actuatorTools) {
		return MethodToolCallbackProvider.builder().toolObjects(buildTools, actuatorTools).build();
	}

}
