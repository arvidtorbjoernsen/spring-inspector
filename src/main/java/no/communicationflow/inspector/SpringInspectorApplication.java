package no.communicationflow.inspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InspectorProperties.class)
public class SpringInspectorApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringInspectorApplication.class, args);
	}

}
