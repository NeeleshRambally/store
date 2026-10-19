package com.example.store.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTests.ProbeController.class)
@Import(GlobalExceptionHandlerTests.ProbeController.class)
class GlobalExceptionHandlerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void queryParameterErrorsUseThePublicParameterName() throws Exception {
        mockMvc.perform(get("/named").param("customer-id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customer-id"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
    }

    @Test
    void aRepeatedParameterIsNamedAsTheHandlerDeclaresIt() throws Exception {
        mockMvc.perform(get("/named").param("customer-id", "1", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'customer-id' must not be repeated"))
                .andExpect(jsonPath("$.errors[0].field").value("customer-id"));
    }

    @Test
    void aRepeatedParameterOnAPathWithoutAHandlerMethodIsStillANotFound() throws Exception {
        mockMvc.perform(get("/nowhere").param("name", "a", "b")).andExpect(status().isNotFound());
    }

    @Test
    void malformedJsonIsStillABadRequest() throws Exception {
        mockMvc.perform(post("/named").contentType(MediaType.APPLICATION_JSON).content("{\"broken\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
    }

    @Test
    void aTypeFailureInsideANestedObjectNamesThePathToTheField() throws Exception {
        mockMvc.perform(post("/nested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outer\":{\"inner\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("outer.inner"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
    }

    @Test
    void aConnectionThatCannotBeObtainedIsAServiceUnavailableProblemDetail() throws Exception {
        mockMvc.perform(get("/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The service cannot obtain a database connection"))
                .andExpect(jsonPath("$.instance").value("/unavailable"))
                .andExpect(content().string(not(containsString("HikariPool"))));
    }

    @Test
    void aConnectionLostInsideTheTransactionIsAServiceUnavailableProblemDetail() throws Exception {
        mockMvc.perform(get("/lost"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("The service cannot obtain a database connection"))
                .andExpect(content().string(not(containsString("Connection reset"))));
    }

    @ParameterizedTest(name = "{0}: {2} {3}")
    @MethodSource("problemsByCause")
    void everyProblemCarriesTheCodeOfItsCause(String cause, RequestBuilder request, int status, String code)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.code").value(code));
    }

    static Stream<Arguments> problemsByCause() {
        return Stream.of(
                arguments("a body constraint", json("/validated", "{\"name\":\"\"}"), 400, "validation_failed"),
                arguments("a parameter constraint", get("/named").param("customer-id", "0"), 400, "validation_failed"),
                arguments(
                        "a wrong JSON type",
                        json("/nested", "{\"outer\":{\"inner\":\"x\"}}"),
                        400,
                        "validation_failed"),
                arguments(
                        "an overflowing integer",
                        json("/validated", "{\"name\":\"a\",\"count\":9223372036854775808}"),
                        400,
                        "validation_failed"),
                arguments(
                        "an unconvertible parameter",
                        get("/named").param("customer-id", "abc"),
                        400,
                        "invalid_parameter"),
                arguments(
                        "a repeated parameter", get("/named").param("customer-id", "1", "1"), 400, "invalid_parameter"),
                arguments(
                        "an unknown property",
                        json("/validated", "{\"name\":\"a\",\"id\":5}"),
                        400,
                        "unknown_property"),
                arguments("malformed JSON", json("/named", "{\"broken\":"), 400, "malformed_body"),
                arguments("an unknown sort property", get("/sorted"), 400, "invalid_sort"),
                arguments("a cursor with a page", get("/cursor"), 400, "invalid_cursor"),
                arguments("both order filters", get("/filtered"), 400, "conflicting_filters"),
                arguments("a missing resource", get("/missing"), 404, "not_found"),
                arguments("an unmapped path", get("/nowhere"), 404, "not_found"),
                arguments("an unsupported method", delete("/named"), 405, "method_not_allowed"),
                arguments(
                        "an unacceptable Accept",
                        get("/json").accept(MediaType.APPLICATION_XML),
                        406,
                        "not_acceptable"),
                arguments("a data integrity violation", get("/conflict"), 409, "conflict"),
                arguments(
                        "an oversized body",
                        json("/named", "{\"a\":\"" + "a".repeat(65536) + "\"}"),
                        413,
                        "payload_too_large"),
                arguments("an oversized upload", get("/upload"), 413, "payload_too_large"),
                arguments(
                        "a body that is not JSON",
                        post("/named").contentType(MediaType.TEXT_PLAIN).content("x"),
                        415,
                        "unsupported_media_type"),
                arguments("an unexpected failure", get("/boom"), 500, "internal_error"),
                arguments("an unwritable response", get("/unwritable"), 500, "internal_error"),
                arguments("an unobtainable connection", get("/unavailable"), 503, "service_unavailable"),
                arguments("an expired async request", get("/timeout"), 503, "service_unavailable"));
    }

    private static RequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    record ProbeBody(@NotBlank String name, Long count) {}

    @RestController
    static class ProbeController {

        @GetMapping("/unavailable")
        String unavailable() {
            throw new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction",
                    new SQLTransientConnectionException(
                            "HikariPool-1 - Connection is not available, request timed out after 5000ms."));
        }

        @GetMapping("/lost")
        String lost() {
            throw new DataAccessResourceFailureException("Connection reset by peer");
        }

        @GetMapping("/missing")
        String missing() {
            throw new ResourceNotFoundException("Order", 9L);
        }

        @GetMapping("/sorted")
        String sorted() {
            throw new InvalidSortPropertyException("nope");
        }

        @GetMapping("/cursor")
        String cursor() {
            throw new InvalidCursorRequestException();
        }

        @GetMapping("/filtered")
        String filtered() {
            throw new InvalidOrderFilterException();
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new DataIntegrityViolationException("duplicate key");
        }

        @GetMapping("/upload")
        String upload() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/unwritable")
        String unwritable() {
            throw new HttpMessageNotWritableException("Could not write JSON");
        }

        @GetMapping("/timeout")
        String timeout() {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/json")
        Map<String, String> json() {
            return Map.of("a", "b");
        }

        @PostMapping("/validated")
        String validated(@Valid @RequestBody ProbeBody body) {
            return "ok";
        }

        @PostMapping("/named")
        String named(@RequestBody Map<String, String> body) {
            return "ok";
        }

        @PostMapping("/nested")
        String nested(@RequestBody Map<String, Map<String, Long>> body) {
            return "ok";
        }

        @GetMapping("/named")
        String named(@RequestParam("customer-id") @Min(1) Long customerId) {
            return "ok";
        }
    }
}
