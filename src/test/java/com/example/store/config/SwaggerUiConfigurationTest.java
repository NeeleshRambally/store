package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerUiConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SwaggerUiConfig.class);

    @Test
    void swaggerUiIsOnUnlessSwaggerUiEnabledTurnsItOff() {
        assertThat(environmentWith(Map.of()).getProperty("store.swagger-ui.enabled"))
                .isEqualTo("true");
        assertThat(environmentWith(Map.of("SWAGGER_UI_ENABLED", "false")).getProperty("store.swagger-ui.enabled"))
                .isEqualTo("false");
    }

    @Test
    void swaggerUiIsServedByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(SwaggerUiConfig.class));
    }

    @Test
    void swaggerUiIsLeftOutWhenTurnedOff() {
        runner.withPropertyValues("store.swagger-ui.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SwaggerUiConfig.class));
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
