package com.example.store.integration;

import com.example.store.StoreApplication;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Starts a second, non-web application against the shared container without DB_USERNAME and DB_PASSWORD.
class FailClosedStartupIT extends AbstractIntegrationTest {

    @Autowired
    private PostgreSQLContainer<?> container;

    @Test
    void startUpWithoutCredentialsFailsAtTheFirstConnection() {
        assumeTrue(System.getenv("DB_USERNAME") == null);
        SpringApplicationBuilder application =
                new SpringApplicationBuilder(StoreApplication.class).web(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run("--spring.datasource.url=" + container.getJdbcUrl()))
                .rootCause()
                .hasMessageContaining("password authentication failed for user \"${DB_USERNAME}\"");
    }

    @Test
    void startUpWithBlankLiquibaseContextsFailsBeforeTheFirstConnection() {
        SpringApplicationBuilder application =
                new SpringApplicationBuilder(StoreApplication.class).web(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run(
                        "--spring.datasource.url=" + container.getJdbcUrl(), "--spring.liquibase.contexts="))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageContaining("spring.liquibase.contexts is blank");
    }
}
