package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    void sequencesAllocateBlocksOfFifty() {
        List<Long> increments = jdbcTemplate.queryForList(
                "SELECT increment_by FROM pg_sequences WHERE sequencename IN ('customer_id_seq', 'order_id_seq', 'product_id_seq')",
                Long.class);

        assertThat(increments).hasSize(3).containsOnly(50L);
    }

    @Test
    void orderCustomerIdIsIndexed() {
        List<String> indexes =
                jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'order'", String.class);

        assertThat(indexes).contains("idx_order_customer_id");
    }

    @Test
    void customerNameHasTrigramIndexForSubstringSearch() {
        List<String> indexes =
                jdbcTemplate.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'customer'", String.class);

        assertThat(indexes).anySatisfy(definition -> assertThat(definition)
                .contains("idx_customer_name_trgm")
                .contains("gin")
                .contains("gin_trgm_ops"));
    }

    @Test
    void customerNameSearchPredicateIsServedByTheTrigramIndex() {
        List<String> plan = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("DROP INDEX idx_customer_name_id");
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(
                    "EXPLAIN SELECT id, name FROM customer WHERE lower(name) LIKE lower('%onne%') ESCAPE '!'",
                    String.class);
        });

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("Bitmap Index Scan on idx_customer_name_trgm"));
    }

    @Test
    void sortedColumnsAreIndexedWithTheIdTieBreak() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('order', 'product', 'customer')", String.class);

        assertThat(indexes).contains("idx_order_description_id", "idx_product_description_id", "idx_customer_name_id");
    }

    @Test
    void sortedOrderPageIsServedByTheDescriptionIndex() {
        List<String> plan = explainWithoutSeqScan("EXPLAIN SELECT o.id, o.description, c.id, c.name FROM \"order\" o"
                + " JOIN customer c ON c.id = o.customer_id ORDER BY o.description DESC, o.id DESC OFFSET 20 LIMIT 20");

        assertThat(plan)
                .anySatisfy(line ->
                        assertThat(line).contains("Index Scan Backward").contains("using idx_order_description_id"));
    }

    @Test
    void sortedProductPageIsServedByTheDescriptionIndex() {
        List<String> plan = explainWithoutSeqScan(
                "EXPLAIN SELECT id, description FROM product ORDER BY description DESC, id DESC LIMIT 20");

        assertThat(plan)
                .anySatisfy(
                        line -> assertThat(line).contains("Index Only Scan").contains("idx_product_description_id"));
    }

    @Test
    void sortedCustomerPageIsServedByTheNameIndex() {
        List<String> plan = explainWithoutSeqScan("EXPLAIN SELECT id, name FROM customer ORDER BY name, id LIMIT 20");

        assertThat(plan)
                .anySatisfy(line -> assertThat(line).contains("Index Only Scan").contains("idx_customer_name_id"));
    }

    @Test
    void customerOrderWindowIsServedByTheCustomerIdIndex() {
        List<String> plan = explainWithoutSeqScan("EXPLAIN SELECT customer_id, id, description, total FROM"
                + " (SELECT o.customer_id, o.id, o.description,"
                + " row_number() OVER (PARTITION BY o.customer_id ORDER BY o.id DESC) AS rn,"
                + " count(*) OVER (PARTITION BY o.customer_id) AS total"
                + " FROM \"order\" o WHERE o.customer_id IN (1, 2, 3)) t WHERE rn <= 20 ORDER BY customer_id, id");

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("Bitmap Index Scan on idx_order_customer_id"));
    }

    private List<String> explainWithoutSeqScan(String explain) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(explain, String.class);
        });
    }

    @Test
    void productSequenceContinuesAfterSeededIds() {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO product (description) VALUES ('Sequence Check') RETURNING id", Long.class);

        assertThat(id).isGreaterThan(100L);
    }

    @Test
    void orderProductJoinTableIsIndexedOnBothSides() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'order_product'", String.class);

        assertThat(indexes).anySatisfy(definition -> assertThat(definition).contains("(order_id, product_id)"));
        assertThat(indexes).anySatisfy(definition -> assertThat(definition).contains("(product_id)"));
    }

    @Test
    void seedDataChangeSetIsTaggedWithSeedContext() {
        String contexts =
                jdbcTemplate.queryForObject("SELECT contexts FROM databasechangelog WHERE id = '2-data'", String.class);

        assertThat(contexts).isEqualTo("seed");
    }
}
