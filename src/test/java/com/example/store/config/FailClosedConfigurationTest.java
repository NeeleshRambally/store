package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailClosedConfigurationTest {

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
}
