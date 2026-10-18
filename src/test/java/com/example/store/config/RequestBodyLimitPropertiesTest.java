package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties(RequestBodyLimitProperties.class)
    static class Binding {}

    @Test
    void bindsTheConfiguredBound() {
        runner.withPropertyValues("store.request.max-body-bytes=4096").run(context -> assertThat(
                        context.getBean(RequestBodyLimitProperties.class).maxBodyBytes())
                .isEqualTo(4096L));
    }

    @Test
    void rejectsABoundBelowOneKibibyte() {
        runner.withPropertyValues("store.request.max-body-bytes=1023").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void defaultsToSixtyFourKibibytesInTheBaseConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("store.request.max-body-bytes", Long.class))
                .isEqualTo(65536L);
    }

    @Test
    void readsTheBoundFromRequestMaxBodyBytes() {
        System.setProperty("REQUEST_MAX_BODY_BYTES", "2048");
        try {
            StandardEnvironment environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment);

            assertThat(environment.getProperty("store.request.max-body-bytes", Long.class))
                    .isEqualTo(2048L);
        } finally {
            System.clearProperty("REQUEST_MAX_BODY_BYTES");
        }
    }
}
