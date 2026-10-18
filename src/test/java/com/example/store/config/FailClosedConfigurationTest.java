package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailClosedConfigurationTest {

    private static final String BLANK_CONTEXTS_MESSAGE = "spring.liquibase.contexts is blank: set LIQUIBASE_CONTEXTS"
            + " to a context such as prod or seed, or leave it unset";

    @Test
    void baseConfigurationHasNoCredentialDefaultsAndNoSeed() {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThatThrownBy(() -> environment.getProperty("spring.datasource.username"))
                .hasMessageContaining("DB_USERNAME");
        assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                .hasMessageContaining("DB_PASSWORD");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5433/store");
        assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("prod");
    }

    @Test
    void localProfileIsTheReadmePath() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("admin");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("admin");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5433/store");
        assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("seed");
    }

    @Test
    void aBlankLiquibaseContextsVariableStopsStartUp() {
        StandardEnvironment environment = environmentWith(Map.of("LIQUIBASE_CONTEXTS", ""));

        assertThat(environment.getProperty("spring.liquibase.contexts")).isEmpty();
        assertThatThrownBy(() -> new LiquibaseContextsGuard().postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(BLANK_CONTEXTS_MESSAGE);
    }

    @Test
    void anAbsentLiquibaseContextsPropertyStopsStartUp() {
        StandardEnvironment environment = new StandardEnvironment();

        assertThat(environment.getProperty("spring.liquibase.contexts")).isNull();
        assertThatThrownBy(() -> new LiquibaseContextsGuard().postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(BLANK_CONTEXTS_MESSAGE);
    }

    @Test
    void aBlankLiquibaseContextsVariableIsIgnoredWhenLiquibaseIsDisabled() {
        StandardEnvironment environment =
                environmentWith(Map.of("LIQUIBASE_CONTEXTS", "", "spring.liquibase.enabled", "false"));

        assertThatCode(() -> new LiquibaseContextsGuard().postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    @Test
    void theDefaultLiquibaseContextsPassTheGuard() {
        StandardEnvironment environment = environmentWith(Map.of());

        assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("prod");
        assertThatCode(() -> new LiquibaseContextsGuard().postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
