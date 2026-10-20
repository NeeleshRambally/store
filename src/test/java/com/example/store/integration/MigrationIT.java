package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIT extends AbstractIntegrationTest {

    private static final String PRODUCT_ID_ORDER_ID_SCAN = "using idx_order_product_product_id_order_id";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void customerSequenceContinuesAfterSeededIds() {
        Long id = insertAndRollBack("INSERT INTO customer (name) VALUES ('Sequence Check') RETURNING id");

        assertThat(id).isGreaterThan(100L);
        assertThat(rowCount("customer", id)).isZero();
    }

    @Test
    void orderSequenceContinuesAfterSeededIds() {
        Long id = insertAndRollBack(
                "INSERT INTO \"order\" (description, customer_id) VALUES ('Sequence Check', 1) RETURNING id");

        assertThat(id).isGreaterThan(10000L);
        assertThat(rowCount("\"order\"", id)).isZero();
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

    @Test
    void customerFilteredPageIsReadInIdOrderFromTheCustomerIdIdIndex() {
        List<String> plan = planOfStatementSentFor("/order?customerId=1&size=20", 0, 1, 20);

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("Index Scan using idx_order_customer_id_id"));
        assertThat(plan).noneSatisfy(line -> assertThat(line).contains("Sort"));
    }

    @Test
    void customerFilteredCursorSeeksTheCustomerIdIdIndex() {
        List<String> plan = planOfStatementSentFor("/order?customerId=1&after=5000&size=20", 0, 1, 5000, 21);

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("idx_order_customer_id_id"));
        assertThat(plan)
                .anySatisfy(line -> assertThat(line).contains("Index Cond: ((customer_id = 1) AND (id > 5000))"));
    }

    @Test
    void customerFilteredCountUsesTheCustomerIdIdIndex() {
        List<String> plan = planOfStatementSentFor("/order?customerId=1&size=20", 1, 1);

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("idx_order_customer_id_id"));
    }

    @Test
    void productFilteredPageTakesItsOrderIdsFromALimitedScanOfTheProductIdOrderIdIndex() {
        List<String> plan = planOfStatementSentFor("/order?productId=2&page=1&size=20", 0, 2, 20, 20);

        assertThat(lineAbove(plan, PRODUCT_ID_ORDER_ID_SCAN)).contains("Limit");
    }

    @Test
    void productFilteredCursorSeeksTheProductIdOrderIdIndex() {
        List<String> plan = planOfStatementSentFor("/order?productId=2&after=5000&size=20", 0, 2, 5000, 21);

        assertThat(lineAbove(plan, PRODUCT_ID_ORDER_ID_SCAN)).contains("Limit");
        assertThat(plan)
                .anySatisfy(line -> assertThat(line).contains("Index Cond: ((product_id = 2) AND (order_id > 5000))"));
    }

    @Test
    void productFilteredCountReadsTheProductIdOrderIdIndexWithoutTheOrderTable() {
        List<String> plan = planOfStatementSentFor("/order?productId=2&page=1&size=20", 1, 2);

        assertThat(plan).anySatisfy(line -> assertThat(line).contains("idx_order_product_product_id_order_id"));
        assertThat(plan).noneSatisfy(line -> assertThat(line).contains("on \"order\""));
    }

    private static String lineAbove(List<String> plan, String node) {
        assertThat(plan).anySatisfy(line -> assertThat(line).contains(node));
        int index = IntStream.range(0, plan.size())
                .filter(i -> plan.get(i).contains(node))
                .findFirst()
                .getAsInt();
        return plan.get(index - 1);
    }

    private List<String> planOfStatementSentFor(String uri, int statement, Object... values) {
        String sql = statementsSentFor(uri).get(statement);
        for (Object value : values) {
            sql = sql.replaceFirst("\\?", String.valueOf(value));
        }
        return explainWithoutSeqScan("EXPLAIN " + sql);
    }

    private List<String> statementsSentFor(String uri) {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level level = sql.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sql.addAppender(appender);
        sql.setLevel(Level.DEBUG);
        try {
            assertThat(restTemplate.getForEntity(uri, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            sql.setLevel(level);
            sql.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> explainWithoutSeqScan(String explain) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(explain, String.class);
        });
    }

    @Test
    void productSequenceContinuesAfterSeededIds() {
        Long id = insertAndRollBack("INSERT INTO product (description) VALUES ('Sequence Check') RETURNING id");

        assertThat(id).isGreaterThan(100L);
        assertThat(rowCount("product", id)).isZero();
    }

    private Long insertAndRollBack(String insert) {
        return transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            return jdbcTemplate.queryForObject(insert, Long.class);
        });
    }

    private Long rowCount(String table, Long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
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
