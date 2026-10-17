package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void customerSequenceContinuesAfterSeededIds() {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO customer (name) VALUES ('Sequence Check') RETURNING id", Long.class);

        assertThat(id).isGreaterThan(100L);
    }

    @Test
    void orderSequenceContinuesAfterSeededIds() {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO \"order\" (description, customer_id) VALUES ('Sequence Check', 1) RETURNING id",
                Long.class);

        assertThat(id).isGreaterThan(10000L);
    }

    @Test
    void orderCustomerIdIsIndexed() {
        List<String> indexes =
                jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'order'", String.class);

        assertThat(indexes).contains("idx_order_customer_id");
    }

    @Test
    void seedDataChangeSetIsTaggedWithSeedContext() {
        String contexts =
                jdbcTemplate.queryForObject("SELECT contexts FROM databasechangelog WHERE id = '2-data'", String.class);

        assertThat(contexts).isEqualTo("seed");
    }
}
