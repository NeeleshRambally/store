package com.example.store.exception;

import jakarta.validation.constraints.Min;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLTransientConnectionException;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTests.NamedParameterController.class)
@Import(GlobalExceptionHandlerTests.NamedParameterController.class)
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

    @RestController
    static class NamedParameterController {

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
