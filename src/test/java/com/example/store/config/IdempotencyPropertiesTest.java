package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties(IdempotencyProperties.class)
    static class Binding {}

    @Test
    void bindsTheConfiguredDurations() {
        runner.withPropertyValues(
                        "store.idempotency.ttl=PT1H",
                        "store.idempotency.stale-after=PT20S",
                        "store.idempotency.purge-interval=PT5M")
                .run(context -> {
                    IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
                    assertThat(properties.ttl()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.staleAfter()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(properties.purgeInterval()).isEqualTo(Duration.ofMinutes(5));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void rejectsATtlThatIsNotPositive(String ttl) {
        runner.withPropertyValues(
                        "store.idempotency.ttl=" + ttl,
                        "store.idempotency.stale-after=PT30S",
                        "store.idempotency.purge-interval=PT10M")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void rejectsAStaleWindowShorterThanTheTransactionTimeout() {
        runner.withPropertyValues(
                        "store.idempotency.ttl=PT24H",
                        "store.idempotency.stale-after=PT14S",
                        "store.idempotency.purge-interval=PT10M")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void acceptsAStaleWindowOfExactlyTheTransactionTimeout() {
        runner.withPropertyValues(
                        "store.idempotency.ttl=PT24H",
                        "store.idempotency.stale-after=PT15S",
                        "store.idempotency.purge-interval=PT10M")
                .run(context -> assertThat(
                                context.getBean(IdempotencyProperties.class).staleAfter())
                        .isEqualTo(Duration.ofSeconds(15)));
    }

    @Test
    void rejectsAPurgeIntervalThatIsNotPositive() {
        runner.withPropertyValues(
                        "store.idempotency.ttl=PT24H",
                        "store.idempotency.stale-after=PT30S",
                        "store.idempotency.purge-interval=PT0S")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void defaultsToADayAThirtySecondWindowAndATenMinutePurgeInTheBaseConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("store.idempotency.ttl")).isEqualTo("PT24H");
        assertThat(environment.getProperty("store.idempotency.stale-after")).isEqualTo("PT30S");
        assertThat(environment.getProperty("store.idempotency.purge-interval")).isEqualTo("PT10M");
    }

    @Test
    void readsEachDurationFromItsVariable() {
        System.setProperty("IDEMPOTENCY_TTL", "PT2H");
        System.setProperty("IDEMPOTENCY_STALE_AFTER", "PT45S");
        System.setProperty("IDEMPOTENCY_PURGE_INTERVAL", "PT1M");
        try {
            StandardEnvironment environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment);

            assertThat(environment.getProperty("store.idempotency.ttl")).isEqualTo("PT2H");
            assertThat(environment.getProperty("store.idempotency.stale-after")).isEqualTo("PT45S");
            assertThat(environment.getProperty("store.idempotency.purge-interval"))
                    .isEqualTo("PT1M");
        } finally {
            System.clearProperty("IDEMPOTENCY_TTL");
            System.clearProperty("IDEMPOTENCY_STALE_AFTER");
            System.clearProperty("IDEMPOTENCY_PURGE_INTERVAL");
        }
    }
}
