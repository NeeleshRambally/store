package com.example.store.integration;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import lombok.SneakyThrows;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

// Proves that the checked-in OpenAPI.yaml and the running application agree, for requests and responses.
class OpenApiContractIT extends AbstractIntegrationTest {

    private static final Path SPEC = Path.of("OpenAPI.yaml").toAbsolutePath();
    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator.createForSpecificationUrl(
                    SPEC.toUri().toString())
            .withResolveCombinators(true)
            .build();
    private static final OpenApiInteractionValidator SCHEMAS_AS_DECLARED =
            OpenApiInteractionValidator.createForSpecificationUrl(SPEC.toUri().toString())
                    .withResolveCombinators(true)
                    .withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                    .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PostgreSQLContainer<?> container;

    @Test
    void orderPageMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void oversizedPageRequestIsWithinTheContractAndCapped() {
        Interaction interaction = get("/order", Map.of("size", "500"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("size").asInt()).isEqualTo(100);
        assertConforms(interaction);
    }

    @Test
    void undocumentedResponseFieldsAreRejected() {
        Interaction interaction = get("/order/1", Map.of());
        Response tampered = SimpleResponse.Builder.ok()
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .withBody(interaction.entity().getBody().replaceFirst("\\{", "{\"undocumented\":true,"))
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/order/1", Request.Method.GET, tampered);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getKey)
                .contains("validation.response.body.schema.additionalProperties");
    }

    @Test
    void successfulGetWithoutItsCacheHeadersIsRejectedByTheContract() {
        Interaction interaction = get("/order/1", Map.of());
        Response stripped = SimpleResponse.Builder.ok()
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(interaction.entity().getBody())
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/order/1", Request.Method.GET, stripped);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getMessage)
                .containsExactlyInAnyOrder(
                        "Header 'ETag' is expected on path '/order/{id}' but not found in response.",
                        "Header 'Cache-Control' is expected on path '/order/{id}' but not found in response.");
    }

    @Test
    void notModifiedWithoutItsCacheHeadersIsRejectedByTheContract() {
        Response stripped = SimpleResponse.Builder.status(HttpStatus.NOT_MODIFIED.value())
                .withHeader("X-Request-Id", "synthetic")
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/customer", Request.Method.GET, stripped);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getMessage)
                .containsExactlyInAnyOrder(
                        "Header 'ETag' is expected on path '/customer' but not found in response.",
                        "Header 'Cache-Control' is expected on path '/customer' but not found in response.");
    }

    @Test
    void orderCursorPageMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("after", "0", "size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void lastOrderCursorPageMatchesTheContract() {
        long maxId = get("/order", Map.of("size", "1", "sort", "id,desc"))
                .json()
                .get("content")
                .get(0)
                .get("id")
                .asLong();

        Interaction interaction = get("/order", Map.of("after", String.valueOf(maxId - 1), "size", "5"));

        assertThat(interaction.json().get("nextCursor").isNull()).isTrue();
        assertConforms(interaction);
    }

    @Test
    void orderByIdMatchesTheContract() {
        Interaction interaction = get("/order/1", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void unknownOrderMatchesTheContract() {
        Interaction interaction = get("/order/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertConforms(interaction);
    }

    @Test
    void unknownOrderResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/order/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(interaction.json()).isEqualTo(example("NotFound", "unknownOrder"));
    }

    @Test
    void nonNumericOrderIdMatchesTheContract() {
        Interaction interaction = get("/order/abc", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void negativeCursorMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("after", "-1"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("after");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void negativePageMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("page", "-1"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("page");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void pageAboveTheBoundMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("page", "21474837", "size", "100"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("page");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void pageAtTheBoundMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("page", "21474836", "size", "100"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("content")).isEmpty();
        assertConforms(interaction);
    }

    @Test
    void emptyOrBlankNumericQueryValuesAreServedAsAbsent() {
        Interaction interaction = get("/order", Map.of("customerId", "", "after", " ", "page", "", "size", "\t"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("page").asInt()).isZero();
        assertThat(interaction.json().get("size").asInt()).isEqualTo(20);
        assertThat(VALIDATOR.validateRequest(interaction.request()).getMessages())
                .extracting(ValidationReport.Message::getKey)
                .hasSize(4)
                .containsOnly("validation.request.parameter.missing");
        assertResponseConforms(interaction);
    }

    @Test
    void pageSizeBeyondThirtyTwoBitsMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("size", "3000000000"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText())
                .isEqualTo("Failed to convert 'size' with value: '3000000000'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void hexadecimalPageMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("page", "0x2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to convert 'page' with value: '0x2'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void hexadecimalOrderIdMatchesTheContract() {
        Interaction interaction = get("/order/0x1", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to convert 'id' with value: '0x1'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void cursorWhoseDigitsAreSplitByASpaceMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("after", "1 0"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to convert 'after' with value: '1 0'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void zeroPaddedOrderIdMatchesTheContract() {
        Interaction interaction = get("/order/01", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to convert 'id' with value: '01'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void customerFilterInFullwidthDigitsMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("customerId", "\uFF11"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText())
                .isEqualTo("Failed to convert 'customerId' with value: '\uFF11'");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @ParameterizedTest
    @CsvSource({"/order, customerId=1%", "/customer, name=50%", "/products, page=%ZZ"})
    void queryStringTomcatCannotParseMatchesTheContract(String path, String query) throws Exception {
        Interaction interaction = getAsWritten(path, query);

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("code").asText()).isEqualTo("invalid_parameter");
        assertResponseConforms(interaction);
    }

    @Test
    void orderPageFilteredByCustomerMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("customerId", "1", "size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction
                        .json()
                        .get("content")
                        .get(0)
                        .get("customer")
                        .get("id")
                        .asLong())
                .isEqualTo(1L);
        assertConforms(interaction);
    }

    @Test
    void orderCursorPageFilteredByProductMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("productId", "2", "after", "0", "size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("content")).hasSize(2).allSatisfy(order -> assertThat(
                        order.get("products").findValues("id").stream()
                                .map(JsonNode::asLong)
                                .toList())
                .contains(2L));
        assertConforms(interaction);
    }

    @Test
    void combinedOrderFiltersMatchTheContract() {
        Interaction interaction = get("/order", Map.of("customerId", "1", "productId", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertConforms(interaction);
    }

    @Test
    void combinedOrderFiltersResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/order", Map.of("customerId", "1", "productId", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json()).isEqualTo(example("BadRequest", "combinedFilters"));
    }

    @Test
    void zeroCustomerIdMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("customerId", "0"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("customerId");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void conflictingOrderMatchesTheContract() {
        Response conflict = SimpleResponse.Builder.status(HttpStatus.CONFLICT.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Conflict",
                        "status", 409,
                        "detail", "The request conflicts with existing data",
                        "instance", "/order",
                        "code", "conflict")))
                .build();

        assertNoErrors(VALIDATOR.validateResponse("/order", Request.Method.POST, conflict));
    }

    @Test
    void serverErrorMatchesTheContract() {
        Response failure = SimpleResponse.Builder.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Internal Server Error",
                        "status", 500,
                        "instance", "/order",
                        "code", "internal_error")))
                .build();

        assertNoErrors(VALIDATOR.validateResponse("/order", Request.Method.GET, failure));
    }

    @Test
    void serviceUnavailableMatchesTheContract() {
        Response unavailable = SimpleResponse.Builder.status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withHeader("Retry-After", "5")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Service Unavailable",
                        "status", 503,
                        "detail", "The service cannot obtain a database connection",
                        "instance", "/order",
                        "code", "service_unavailable")))
                .build();

        assertNoErrors(VALIDATOR.validateResponse("/order", Request.Method.GET, unavailable));
        assertNoErrors(VALIDATOR.validateResponse("/order", Request.Method.POST, unavailable));
    }

    @Test
    void cancelledStatementResponseEqualsTheDocumentedExample() throws Exception {
        Interaction interaction;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            interaction = lock.cancelWaiting(() -> get("/order", Map.of("size", "5")));
        }

        assertThat(interaction.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(interaction.json()).isEqualTo(example("ServiceUnavailable", "databaseTimeout"));
        assertConforms(interaction);
    }

    @Test
    void lostConnectionResponseEqualsTheDocumentedExample() throws Exception {
        Interaction interaction;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            interaction = lock.terminateWaiting(() -> get("/order", Map.of("size", "5")));
        }

        assertThat(interaction.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(interaction.json()).isEqualTo(example("ServiceUnavailable", "serviceUnavailable"));
        assertConforms(interaction);
    }

    @Test
    void serviceUnavailableWithoutRetryAfterIsRejectedByTheContract() {
        Response unavailable = SimpleResponse.Builder.status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Service Unavailable",
                        "status", 503,
                        "code", "service_unavailable")))
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/products/2", Request.Method.GET, unavailable);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getMessage)
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("Retry-After");
    }

    @Test
    void problemWithoutACodeIsRejectedByTheContract() {
        Response notFound = SimpleResponse.Builder.status(HttpStatus.NOT_FOUND.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Not Found",
                        "status", 404,
                        "detail", "Order with id 999999 was not found",
                        "instance", "/order/999999")))
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/order/999999", Request.Method.GET, notFound);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getKey)
                .containsExactly("validation.response.body.schema.required");
    }

    @Test
    void undocumentedProblemCodeIsRejectedByTheContract() {
        Response notFound = SimpleResponse.Builder.status(HttpStatus.NOT_FOUND.value())
                .withContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withHeader("X-Request-Id", "synthetic")
                .withBody(toJson(Map.of(
                        "type", "about:blank",
                        "title", "Not Found",
                        "status", 404,
                        "detail", "Order with id 999999 was not found",
                        "instance", "/order/999999",
                        "code", "order_not_found")))
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/order/999999", Request.Method.GET, notFound);

        assertThat(report.getMessages())
                .extracting(ValidationReport.Message::getKey)
                .containsExactly("validation.response.body.schema.enum");
    }

    @Test
    void orderForUnknownCustomerMatchesTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "Contract order", "customerId", 999999, "productIds", List.of(1)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertConforms(interaction);
    }

    @Test
    void createOrderMatchesTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "Contract order", "customerId", 1, "productIds", List.of(1, 2)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.CREATED);
        assertConforms(interaction);
    }

    @Test
    void rejectedOrderMatchesTheContract() {
        Interaction interaction = post("/order", Map.of("description", "", "customerId", 1, "productIds", List.of()));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors")).isNotEmpty();
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void repeatedProductIdsMatchTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "Contract order", "customerId", 1, "productIds", List.of(2, 2)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("productIds");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void controlCharacterInAnOrderDescriptionMatchesTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "Contract\u0000order", "customerId", 5, "productIds", List.of(1)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("description");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void fractionalCustomerIdMatchesTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "Contract fraction", "customerId", 5.5, "productIds", List.of(1)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("customerId");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void contentAfterAnOrderBodyMatchesTheContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Interaction interaction = exchange(
                HttpMethod.POST,
                "/order",
                Map.of(),
                headers,
                "{\"description\":\"Contract trailing\",\"customerId\":5,\"productIds\":[1]} x");

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to read request");
        assertResponseConforms(interaction);
    }

    @Test
    void repeatedPropertyInAnOrderBodyMatchesTheContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Interaction interaction = exchange(
                HttpMethod.POST,
                "/order",
                Map.of(),
                headers,
                "{\"description\":\"Contract repeated\",\"customerId\":5,\"customerId\":6,\"productIds\":[1]}");

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Failed to read request");
        assertResponseConforms(interaction);
    }

    @ParameterizedTest(name = "POST {0}")
    @MethodSource("bodiesCarryingAnId")
    void aClientSuppliedIdIsRejectedByTheRequestSchemaItself(String path, Map<String, Object> body) {
        Interaction interaction = post(path, body);

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("code").asText()).isEqualTo("unknown_property");
        assertThat(SCHEMAS_AS_DECLARED.validateRequest(interaction.request()).getMessages())
                .extracting(ValidationReport.Message::getKey)
                .containsExactly("validation.request.body.schema.additionalProperties");
        assertResponseConforms(interaction);
    }

    static Stream<Arguments> bodiesCarryingAnId() {
        return Stream.of(
                arguments(
                        "/order",
                        Map.of("id", 5, "description", "Contract id order", "customerId", 5, "productIds", List.of(1))),
                arguments("/customer", Map.of("id", 5, "name", "Contract Id Customer")),
                arguments("/products", Map.of("id", 5, "description", "Contract id product")));
    }

    @ParameterizedTest(name = "POST {0}")
    @MethodSource("keyedCreations")
    void aRepeatWithTheSameIdempotencyKeyMatchesTheContract(String path, Map<String, Object> body) {
        String key = UUID.randomUUID().toString();

        Interaction first = post(path, body, key);
        Interaction repeat = post(path, body, key);

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.headers().getFirst("Idempotent-Replayed")).isNull();
        assertConforms(first);
        assertThat(repeat.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(repeat.headers().getLocation()).isEqualTo(first.headers().getLocation());
        assertThat(repeat.json()).isEqualTo(first.json());
        assertConforms(repeat);
    }

    static Stream<Arguments> keyedCreations() {
        return Stream.of(
                arguments(
                        "/order",
                        Map.of("description", "Contract keyed order", "customerId", 1, "productIds", List.of(1))),
                arguments("/customer", Map.of("name", "Contract keyed customer")),
                arguments("/products", Map.of("description", "Contract keyed product")));
    }

    @Test
    void aReplayedResponseWithAnotherReplayedValueIsRejectedByTheContract() {
        Interaction created = post(
                "/customer",
                Map.of("name", "Contract replay value"),
                UUID.randomUUID().toString());
        Response tampered = SimpleResponse.Builder.status(HttpStatus.CREATED.value())
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .withHeader("Location", created.headers().getLocation().toString())
                .withHeader("X-Request-Id", "synthetic")
                .withHeader("Idempotent-Replayed", "yes")
                .withBody(created.entity().getBody())
                .build();

        ValidationReport report = VALIDATOR.validateResponse("/customer", Request.Method.POST, tampered);

        assertThat(report.getMessages()).singleElement().satisfies(message -> {
            assertThat(message.getKey()).startsWith("validation.response.header");
            assertThat(message.getMessage()).contains("(\"yes\") not found in enum (possible values: [\"true\"])");
        });
    }

    @Test
    void invalidIdempotencyKeyResponseEqualsTheDocumentedExample() {
        Interaction interaction = post("/customer", Map.of("name", "Contract spaced key"), "has a space");

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json()).isEqualTo(example("BadRequest", "invalidIdempotencyKey"));
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void reusedIdempotencyKeyResponseEqualsTheDocumentedExample() {
        String key = UUID.randomUUID().toString();
        assertThat(post("/customer", Map.of("name", "Contract first body"), key).status())
                .isEqualTo(HttpStatus.CREATED);

        Interaction interaction = post("/customer", Map.of("name", "Contract second body"), key);

        assertThat(interaction.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(interaction.json()).isEqualTo(example("UnprocessableEntity", "idempotencyKeyReused"));
        assertConforms(interaction);
    }

    @Test
    void requestInProgressResponseEqualsTheDocumentedExample() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("name", "Contract held customer");
        CompletableFuture<Interaction> held;
        Interaction repeat;
        try (TableLock lock = TableLock.on(container, "customer")) {
            held = lock.holdWaiting(() -> post("/customer", body, key));
            repeat = post("/customer", body, key);
        }

        assertThat(repeat.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeat.headers().getFirst("Retry-After")).isEqualTo("1");
        assertThat(repeat.json()).isEqualTo(example("Conflict", "requestInProgress"));
        assertConforms(repeat);
        assertThat(held).succeedsWithin(Duration.ofSeconds(30)).satisfies(first -> {
            assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
            assertConforms(first);
        });
    }

    @Test
    void oversizedOrderMatchesTheContract() {
        Interaction interaction =
                post("/order", Map.of("description", "a".repeat(70_000), "customerId", 1, "productIds", List.of(1)));

        assertThat(interaction.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Request body exceeds 65536 bytes");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void unsupportedMediaTypeMatchesTheContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        Interaction interaction = exchange(HttpMethod.POST, "/order", Map.of(), headers, "not json");

        assertThat(interaction.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(interaction.json().get("status").asInt()).isEqualTo(415);
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void unknownSortPropertyMatchesTheContract() {
        Interaction interaction = get("/order", Map.of("sort", "nope"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertConforms(interaction);
    }

    @Test
    void unknownSortPropertyResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/order", Map.of("sort", "customer.name"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json()).isEqualTo(example("BadRequest", "unknownSortProperty"));
    }

    @Test
    void cursorWithPageResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/order", Map.of("after", "0", "page", "1"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json()).isEqualTo(example("BadRequest", "cursorWithPage"));
        assertConforms(interaction);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyOperation")
    void nonJsonAcceptIsTheDeclaredNotAcceptableOnEveryOperation(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_XML));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        Interaction interaction = exchange(method, path, Map.of(), headers, body);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(interaction.json().get("code").asText()).isEqualTo("not_acceptable");
        assertResponseConforms(interaction);
    }

    static Stream<Arguments> everyOperation() {
        return Stream.of(
                arguments(HttpMethod.GET, "/order", null),
                arguments(
                        HttpMethod.POST,
                        "/order",
                        toJson(Map.of("description", "Contract XML order", "customerId", 5, "productIds", List.of(1)))),
                arguments(HttpMethod.GET, "/order/1", null),
                arguments(HttpMethod.GET, "/customer", null),
                arguments(HttpMethod.POST, "/customer", toJson(Map.of("name", "Contract Xml Customer"))),
                arguments(HttpMethod.GET, "/customer/1", null),
                arguments(HttpMethod.GET, "/products", null),
                arguments(HttpMethod.POST, "/products", toJson(Map.of("description", "Contract XML product"))),
                arguments(HttpMethod.GET, "/products/2", null));
    }

    @Test
    void notAcceptableResponseEqualsTheDocumentedExample() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_XML));

        Interaction interaction = exchange(HttpMethod.GET, "/order/1", Map.of(), headers, null);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(interaction.json()).isEqualTo(example("NotAcceptable", "orderAsXml"));
    }

    @Test
    void conditionalGetMatchesTheContract() {
        String etag = get("/order/1", Map.of()).headers().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);

        Interaction interaction = exchange(HttpMethod.GET, "/order/1", Map.of(), headers, null);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertConforms(interaction);
    }

    @Test
    void customerPageMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void customerCursorPageMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("after", "0", "size", "3"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("nextCursor").asLong()).isEqualTo(3L);
        assertConforms(interaction);
    }

    @Test
    void conditionalCustomerPageMatchesTheContract() {
        String etag = get("/customer", Map.of("size", "2")).headers().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);

        Interaction interaction = exchange(HttpMethod.GET, "/customer", Map.of("size", "2"), headers, null);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertConforms(interaction);
    }

    @Test
    void customerSearchMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("name", "onne"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("content")).isNotEmpty();
        assertConforms(interaction);
    }

    @Test
    void multiWordCustomerSearchMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("name", "Muriel Donnelly"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("name");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void nulCharacterCustomerSearchMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("name", "a\u0000b"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("name");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void createCustomerMatchesTheContract() {
        Interaction interaction = post("/customer", Map.of("name", "Contract Customer"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.CREATED);
        assertConforms(interaction);
    }

    @Test
    void rejectedCustomerMatchesTheContract() {
        Interaction interaction = post("/customer", Map.of("name", ""));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors")).isNotEmpty();
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void controlCharacterInACustomerNameMatchesTheContract() {
        Interaction interaction = post("/customer", Map.of("name", "Contract\u0000customer"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("name");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void whitespaceOnlyCustomerMatchesTheContract() {
        Interaction interaction = post("/customer", Map.of("name", "   "));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors")).isNotEmpty();
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void nonBreakingSpaceCustomerSearchMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("name", "a\u00a0b"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void c1ControlAndLineSeparatorCustomerSearchMatchesTheContract() {
        Interaction interaction = get("/customer", Map.of("name", "a\u0085b\u2028c"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void customerByIdMatchesTheContract() {
        Interaction interaction = get("/customer/1", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("orders")).isNotEmpty();
        assertThat(interaction.json().get("orderCount").asLong()).isGreaterThan(20L);
        assertConforms(interaction);
    }

    @Test
    void conditionalCustomerByIdMatchesTheContract() {
        String etag = get("/customer/1", Map.of()).headers().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);

        Interaction interaction = exchange(HttpMethod.GET, "/customer/1", Map.of(), headers, null);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertConforms(interaction);
    }

    @Test
    void unknownCustomerMatchesTheContract() {
        Interaction interaction = get("/customer/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(interaction.json().get("detail").asText()).isEqualTo("Customer with id 999999 was not found");
        assertConforms(interaction);
    }

    @Test
    void unknownCustomerResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/customer/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(interaction.json()).isEqualTo(example("NotFound", "unknownCustomer"));
    }

    @Test
    void nonNumericCustomerIdMatchesTheContract() {
        Interaction interaction = get("/customer/abc", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void productPageMatchesTheContract() {
        Interaction interaction = get("/products", Map.of("size", "2"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertConforms(interaction);
    }

    @Test
    void unknownProductSortPropertyMatchesTheContract() {
        Interaction interaction = get("/products", Map.of("sort", "nope"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertConforms(interaction);
    }

    @Test
    void productByIdMatchesTheContract() {
        Interaction interaction = get("/products/2", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("orders")).isNotEmpty();
        assertConforms(interaction);
    }

    @Test
    void conditionalProductMatchesTheContract() {
        String etag = get("/products/2", Map.of()).headers().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);

        Interaction interaction = exchange(HttpMethod.GET, "/products/2", Map.of(), headers, null);

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertConforms(interaction);
    }

    @Test
    void productCursorPageMatchesTheContract() {
        Interaction interaction = get("/products", Map.of("after", "0", "size", "3"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.OK);
        assertThat(interaction.json().get("nextCursor").asLong()).isEqualTo(3L);
        assertConforms(interaction);
    }

    @Test
    void unknownProductMatchesTheContract() {
        Interaction interaction = get("/products/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertConforms(interaction);
    }

    @Test
    void unknownProductResponseEqualsTheDocumentedExample() {
        Interaction interaction = get("/products/999999", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(interaction.json()).isEqualTo(example("NotFound", "unknownProduct"));
    }

    @Test
    void nonNumericProductIdMatchesTheContract() {
        Interaction interaction = get("/products/abc", Map.of());

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void createProductMatchesTheContract() {
        Interaction interaction = post("/products", Map.of("description", "Contract product"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.CREATED);
        assertConforms(interaction);
    }

    @Test
    void rejectedProductMatchesTheContract() {
        Interaction interaction = post("/products", Map.of("description", ""));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors")).isNotEmpty();
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void controlCharacterInAProductDescriptionMatchesTheContract() {
        Interaction interaction = post("/products", Map.of("description", "Contract\u0000product"));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("description");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    @Test
    void numericProductDescriptionMatchesTheContract() {
        Interaction interaction = post("/products", Map.of("description", 123));

        assertThat(interaction.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(interaction.json().get("errors").get(0).get("field").asText())
                .isEqualTo("description");
        assertThat(VALIDATOR.validateRequest(interaction.request()).hasErrors()).isTrue();
        assertResponseConforms(interaction);
    }

    private static void assertConforms(Interaction interaction) {
        assertNoErrors(VALIDATOR.validate(interaction.request(), interaction.response()));
    }

    private static void assertResponseConforms(Interaction interaction) {
        assertNoErrors(VALIDATOR.validateResponse(
                interaction.request().getPath(), interaction.request().getMethod(), interaction.response()));
    }

    private static void assertNoErrors(ValidationReport report) {
        assertThat(report.hasErrors())
                .describedAs(SimpleValidationReportFormat.getInstance().apply(report))
                .isFalse();
    }

    private Interaction get(String path, Map<String, String> query) {
        return exchange(HttpMethod.GET, path, query, new HttpHeaders(), null);
    }

    private Interaction post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return exchange(HttpMethod.POST, path, Map.of(), headers, toJson(body));
    }

    private Interaction post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return exchange(HttpMethod.POST, path, Map.of(), headers, toJson(body));
    }

    private Interaction exchange(
            HttpMethod method, String path, Map<String, String> query, HttpHeaders headers, String body) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        SimpleRequest.Builder request = new SimpleRequest.Builder(method.name(), path);
        query.forEach((name, value) -> {
            uri.queryParam(name, value);
            request.withQueryParam(name, value);
        });
        headers.forEach((name, values) -> request.withHeader(name, values));
        if (body != null) {
            request.withBody(body);
        }

        URI target = URI.create(restTemplate.getRootUri() + uri.build().encode().toUriString());
        ResponseEntity<String> entity =
                restTemplate.exchange(target, method, new HttpEntity<>(body, headers), String.class);
        return new Interaction(request.build(), response(entity), entity);
    }

    private Interaction getAsWritten(String path, String query) throws IOException {
        ResponseEntity<String> entity = RawRequest.get(restTemplate.getRootUri() + path + "?" + query);
        return new Interaction(
                new SimpleRequest.Builder(HttpMethod.GET.name(), path).build(), response(entity), entity);
    }

    private static Response response(ResponseEntity<String> entity) {
        SimpleResponse.Builder response =
                new SimpleResponse.Builder(entity.getStatusCode().value());
        entity.getHeaders().forEach((name, values) -> response.withHeader(name, values));
        if (entity.getBody() != null) {
            response.withBody(entity.getBody());
        }
        return response.build();
    }

    @SneakyThrows
    private static String toJson(Object body) {
        return JSON.writeValueAsString(body);
    }

    @SneakyThrows
    private static JsonNode example(String responseName, String exampleName) {
        return new ObjectMapper(new YAMLFactory())
                .readTree(SPEC.toFile())
                .path("components")
                .path("responses")
                .path(responseName)
                .path("content")
                .path("application/problem+json")
                .path("examples")
                .path(exampleName)
                .path("value");
    }

    private record Interaction(Request request, Response response, ResponseEntity<String> entity) {

        HttpStatus status() {
            return HttpStatus.valueOf(entity.getStatusCode().value());
        }

        HttpHeaders headers() {
            return entity.getHeaders();
        }

        @SneakyThrows
        JsonNode json() {
            return JSON.readTree(entity.getBody());
        }
    }
}
