package com.example.store.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

// Step definitions are scenario scoped, so the captured response never leaks between scenarios.
public class StoreApiSteps {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ResponseEntity<String> response;
    private JsonNode body;
    private long createdProductId;
    private long createdOrderId;

    @When("I create a customer named {string}")
    public void createCustomerNamed(String name) throws Exception {
        post("/customer", objectMapper.writeValueAsString(Map.of("name", name)));
    }

    @When("I create a customer with payload:")
    public void createCustomerWithPayload(String payload) throws Exception {
        post("/customer", payload);
    }

    @When("I create an order {string} for customer {int} with products {int} and {int}")
    public void createOrderForCustomer(String description, int customerId, int firstProduct, int secondProduct)
            throws Exception {
        post(
                "/order",
                objectMapper.writeValueAsString(Map.of(
                        "description",
                        description,
                        "customerId",
                        customerId,
                        "productIds",
                        List.of(firstProduct, secondProduct))));
    }

    @When("I create an order {string} for customer {int} with {int} products")
    public void createOrderWithManyProducts(String description, int customerId, int productCount) throws Exception {
        post(
                "/order",
                objectMapper.writeValueAsString(Map.of(
                        "description",
                        description,
                        "customerId",
                        customerId,
                        "productIds",
                        LongStream.rangeClosed(1, productCount).boxed().toList())));
    }

    @When("I create a product described as {string}")
    public void createProduct(String description) throws Exception {
        post("/products", objectMapper.writeValueAsString(Map.of("description", description)));
        createdProductId = body.path("id").asLong();
    }

    @When("I request product {int}")
    public void requestProduct(int id) throws Exception {
        get("/products/" + id);
    }

    @When("I request the product created above")
    public void requestCreatedProduct() throws Exception {
        get("/products/" + createdProductId);
    }

    @When("I create an order {string} for customer {int} with the product created above")
    public void createOrderWithCreatedProduct(String description, int customerId) throws Exception {
        post(
                "/order",
                objectMapper.writeValueAsString(Map.of(
                        "description",
                        description,
                        "customerId",
                        customerId,
                        "productIds",
                        List.of(createdProductId))));
        createdOrderId = body.path("id").asLong();
    }

    @When("I request products with page {int} and size {int}")
    public void requestProductsPage(int page, int size) throws Exception {
        get("/products?page=" + page + "&size=" + size);
    }

    @When("I create an order with payload:")
    public void createOrderWithPayload(String payload) throws Exception {
        post("/order", payload);
    }

    @When("I create an order with a body of {int} bytes")
    public void createOrderWithBodyOf(int bytes) throws Exception {
        String prefix = "{\"description\":\"";
        String suffix = "\",\"customerId\":1,\"productIds\":[1]}";
        post("/order", prefix + "a".repeat(bytes - prefix.length() - suffix.length()) + suffix);
    }

    @When("I request customers with page {int} and size {int}")
    public void requestCustomersPage(int page, int size) throws Exception {
        get("/customer?page=" + page + "&size=" + size);
    }

    @When("I request customers sorted by {string}")
    public void requestCustomersSortedBy(String sort) throws Exception {
        get("/customer?sort=" + sort);
    }

    @When("I request orders sorted by {string}")
    public void requestOrdersSortedBy(String sort) throws Exception {
        get("/order?sort=" + sort);
    }

    @When("I request customer {int}")
    public void requestCustomer(int id) throws Exception {
        get("/customer/" + id);
    }

    @When("I request order {int}")
    public void requestOrder(int id) throws Exception {
        get("/order/" + id);
    }

    @When("I request order {int} again with that ETag")
    public void requestOrderWithETag(int id) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(response.getHeaders().getETag());
        capture(restTemplate.exchange("/order/" + id, HttpMethod.GET, new HttpEntity<>(headers), String.class));
    }

    @When("I search customers by name {string}")
    public void searchCustomersByName(String name) throws Exception {
        get(UriComponentsBuilder.fromPath("/customer")
                .queryParam("name", name)
                .build()
                .toUriString());
    }

    @When("I request orders after {int} with size {int}")
    public void requestOrdersAfter(int after, int size) throws Exception {
        get("/order?after=" + after + "&size=" + size);
    }

    @When("I request orders after {int} on page {int}")
    public void requestOrdersAfterOnPage(int after, int page) throws Exception {
        get("/order?after=" + after + "&page=" + page);
    }

    @When("I request products after {int} with size {int}")
    public void requestProductsAfter(int after, int size) throws Exception {
        get("/products?after=" + after + "&size=" + size);
    }

    @When("I request orders of customer {int}")
    public void requestOrdersOfCustomer(int customerId) throws Exception {
        get("/order?customerId=" + customerId);
    }

    @When("I request orders containing product {int}")
    public void requestOrdersContainingProduct(int productId) throws Exception {
        get("/order?productId=" + productId);
    }

    @When("I request orders of customer {int} containing product {int}")
    public void requestOrdersOfCustomerContainingProduct(int customerId, int productId) throws Exception {
        get("/order?customerId=" + customerId + "&productId=" + productId);
    }

    @When("I request orders sorted by {string} with page {int} and size {int}")
    public void requestOrdersSortedByPage(String sort, int page, int size) throws Exception {
        get("/order?sort=" + sort + "&page=" + page + "&size=" + size);
    }

    @When("I request orders with page {int} and size {int}")
    public void requestOrdersPage(int page, int size) throws Exception {
        get("/order?page=" + page + "&size=" + size);
    }

    @Then("the response status is {int}")
    public void responseStatusIs(int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
    }

    @Then("the response customer is named {string} with a generated id and no orders")
    public void responseCustomerIs(String name) {
        assertThat(body.get("name").asText()).isEqualTo(name);
        assertThat(body.get("id").asLong()).isGreaterThan(100L);
        assertThat(body.get("orders")).isEmpty();
        assertThat(body.get("orderCount").asLong()).isZero();
    }

    @Then("the response order is {string} for customer {string} with a generated id")
    public void responseOrderIs(String description, String customerName) {
        assertThat(body.get("description").asText()).isEqualTo(description);
        assertThat(body.get("customer").get("name").asText()).isEqualTo(customerName);
        assertThat(body.get("id").asLong()).isGreaterThan(10000L);
    }

    @Then("the response order is {string} for customer {string} with id {int}")
    public void responseOrderWithId(String description, String customerName, int id) {
        assertThat(body.get("description").asText()).isEqualTo(description);
        assertThat(body.get("customer").get("name").asText()).isEqualTo(customerName);
        assertThat(body.get("id").asInt()).isEqualTo(id);
    }

    @Then("every customer on the page has a name containing {string}")
    public void everyCustomerNameContains(String fragment) {
        assertThat(body.get("content")).isNotEmpty();
        for (JsonNode customer : body.get("content")) {
            assertThat(customer.get("name").asText().toLowerCase()).contains(fragment.toLowerCase());
        }
    }

    @Then("the page contains customer {string}")
    public void pageContainsCustomer(String name) {
        assertThat(body.get("content").findValuesAsText("name")).contains(name);
    }

    @Then("the page is empty")
    public void pageIsEmpty() {
        assertThat(body.get("content")).isEmpty();
    }

    @Then("the response order contains products {int} and {int}")
    public void responseOrderContainsProducts(int first, int second) {
        assertThat(body.get("products").findValues("id").stream()
                        .map(JsonNode::asInt)
                        .toList())
                .containsExactly(first, second);
    }

    @Then("the response product is described as {string} with a generated id and no orders")
    public void responseProductIs(String description) {
        assertThat(body.get("description").asText()).isEqualTo(description);
        assertThat(body.get("id").asLong()).isGreaterThan(100L);
        assertThat(body.get("orders")).isEmpty();
        assertThat(body.get("orderCount").asLong()).isZero();
    }

    @Then("the response product has no orders")
    public void responseProductHasNoOrders() {
        assertThat(body.get("id").asLong()).isEqualTo(createdProductId);
        assertThat(body.get("orders")).isEmpty();
        assertThat(body.get("orderCount").asLong()).isZero();
    }

    @Then("the response product lists the order created above")
    public void responseProductListsCreatedOrder() {
        assertThat(body.get("id").asLong()).isEqualTo(createdProductId);
        List<Long> orders = new java.util.ArrayList<>();
        body.get("orders").forEach(node -> orders.add(node.asLong()));
        assertThat(orders).containsExactly(createdOrderId);
        assertThat(body.get("orderCount").asLong()).isEqualTo(1L);
    }

    @Then("the response carries a weak ETag")
    public void responseCarriesWeakETag() {
        assertThat(response.getHeaders().getETag()).startsWith("W/\"");
    }

    @Then("the response product has id {int} with its {int} newest orders and the total count")
    public void responseProductHasNewestOrders(int id, int limit) {
        Long total =
                jdbcTemplate.queryForObject("select count(*) from order_product where product_id = ?", Long.class, id);
        List<Long> newest = jdbcTemplate
                .queryForList(
                        "select order_id from order_product where product_id = ? order by order_id desc limit ?",
                        Long.class,
                        id,
                        limit)
                .stream()
                .sorted()
                .toList();
        assertThat(body.get("id").asInt()).isEqualTo(id);
        assertThat(body.get("orderCount").asLong()).isEqualTo(total);
        List<Long> orders = new java.util.ArrayList<>();
        body.get("orders").forEach(node -> orders.add(node.asLong()));
        assertThat(orders).hasSize(limit).isEqualTo(newest);
    }

    @Then("the response customer has id {int} named {string} with its {int} newest orders and the total count")
    public void responseCustomerHasNewestOrders(int id, String name, int limit) {
        Long total =
                jdbcTemplate.queryForObject("select count(*) from \"order\" where customer_id = ?", Long.class, id);
        List<Long> newest = jdbcTemplate
                .queryForList(
                        "select id from \"order\" where customer_id = ? order by id desc limit ?",
                        Long.class,
                        id,
                        limit)
                .stream()
                .sorted()
                .toList();
        assertThat(body.get("id").asInt()).isEqualTo(id);
        assertThat(body.get("name").asText()).isEqualTo(name);
        assertThat(body.get("orderCount").asLong()).isEqualTo(total);
        List<Long> orders = new java.util.ArrayList<>();
        body.get("orders").forEach(node -> orders.add(node.get("id").asLong()));
        assertThat(orders).hasSize(limit).isEqualTo(newest);
    }

    @Then("the page has {int} products starting at id {int}")
    public void pageHasProductsStartingAt(int count, int firstId) {
        pageHasElementsStartingAt(count, firstId);
    }

    @Then("every product on the page lists its newest order ids and the total count")
    public void everyProductListsNewestOrders() {
        for (JsonNode product : body.get("content")) {
            assertThat(product.get("orders")).isNotEmpty().hasSizeLessThanOrEqualTo(20);
            assertThat(product.get("orderCount").asLong())
                    .isGreaterThanOrEqualTo(product.get("orders").size());
        }
    }

    @Then("every order on the page lists at least one product")
    public void everyOrderListsProducts() {
        for (JsonNode order : body.get("content")) {
            assertThat(order.get("products")).isNotEmpty();
        }
    }

    @Then("every order on the page belongs to customer {int}")
    public void everyOrderBelongsToCustomer(int customerId) {
        assertThat(body.get("content")).isNotEmpty();
        for (JsonNode order : body.get("content")) {
            assertThat(order.get("customer").get("id").asInt()).isEqualTo(customerId);
        }
    }

    @Then("every order on the page contains product {int}")
    public void everyOrderContainsProduct(int productId) {
        assertThat(body.get("content")).isNotEmpty();
        for (JsonNode order : body.get("content")) {
            assertThat(order.get("products").findValues("id").stream()
                            .map(JsonNode::asInt)
                            .toList())
                    .contains(productId);
        }
    }

    @Then("the response problem lists a validation error for {string}")
    public void responseProblemListsValidationErrorFor(String field) {
        assertThat(body.get("errors").findValuesAsText("field")).contains(field);
    }

    @Then("the response problem detail is {string}")
    public void responseProblemDetailIs(String detail) {
        assertThat(body.get("detail").asText()).isEqualTo(detail);
    }

    @Then("customer {int} is still named {string}")
    public void customerIsStillNamed(int id, String name) throws Exception {
        get("/customer?size=100");
        JsonNode customer = null;
        for (JsonNode node : body.get("content")) {
            if (node.get("id").asInt() == id) {
                customer = node;
            }
        }
        assertThat(customer).isNotNull();
        assertThat(customer.get("name").asText()).isEqualTo(name);
    }

    @Then("the page has {int} customers starting at id {int}")
    public void pageHasCustomersStartingAt(int count, int firstId) {
        pageHasElementsStartingAt(count, firstId);
    }

    @Then("the page has {int} orders starting at id {int}")
    public void pageHasOrdersStartingAt(int count, int firstId) {
        pageHasElementsStartingAt(count, firstId);
    }

    @Then("the page reports page {int}, size {int} and at least {int} total elements")
    public void pageReports(int page, int size, int totalElements) {
        assertThat(body.get("page").asInt()).isEqualTo(page);
        assertThat(body.get("size").asInt()).isEqualTo(size);
        assertThat(body.get("totalElements").asLong()).isGreaterThanOrEqualTo(totalElements);
    }

    @Then("the next cursor is {int}")
    public void nextCursorIs(int cursor) {
        assertThat(body.get("nextCursor").asLong()).isEqualTo(cursor);
    }

    @Then("the page reports size {int}")
    public void pageReportsSize(int size) {
        assertThat(body.get("size").asInt()).isEqualTo(size);
    }

    @Then("every customer on the page includes its newest orders and the total count")
    public void everyCustomerIncludesNewestOrders() {
        for (JsonNode customer : body.get("content")) {
            assertThat(customer.get("orders")).isNotEmpty().hasSizeLessThanOrEqualTo(20);
            assertThat(customer.get("orderCount").asLong())
                    .isGreaterThanOrEqualTo(customer.get("orders").size());
        }
    }

    @Then("the page lists the orders in description descending order with ties broken by id descending")
    public void pageListsOrdersByDescriptionDescThenIdDesc() {
        List<Long> expected = jdbcTemplate.queryForList(
                "select id from \"order\" order by description desc, id desc limit ?",
                Long.class,
                body.get("content").size());
        List<Long> ids = new java.util.ArrayList<>();
        body.get("content").forEach(order -> ids.add(order.get("id").asLong()));

        assertThat(ids).hasSize(50).containsExactlyElementsOf(expected);
    }

    @Then("the first order on the page belongs to customer {string}")
    public void firstOrderBelongsTo(String customerName) {
        assertThat(body.get("content").get(0).get("customer").get("name").asText())
                .isEqualTo(customerName);
    }

    private void pageHasElementsStartingAt(int count, int firstId) {
        assertThat(body.get("content")).hasSize(count);
        assertThat(body.get("content").get(0).get("id").asInt()).isEqualTo(firstId);
    }

    private void post(String path, String json) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        capture(restTemplate.postForEntity(path, new HttpEntity<>(json, headers), String.class));
    }

    private void get(String path) throws Exception {
        capture(restTemplate.getForEntity(path, String.class));
    }

    private void capture(ResponseEntity<String> entity) throws Exception {
        response = entity;
        body = entity.getBody() == null ? objectMapper.nullNode() : objectMapper.readTree(entity.getBody());
    }
}
