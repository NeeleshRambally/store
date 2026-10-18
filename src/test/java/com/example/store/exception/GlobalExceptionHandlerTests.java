package com.example.store.exception;

import jakarta.validation.constraints.Min;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @RestController
    static class NamedParameterController {

        @PostMapping("/named")
        String named(@RequestBody Map<String, String> body) {
            return "ok";
        }

        @GetMapping("/named")
        String named(@RequestParam("customer-id") @Min(1) Long customerId) {
            return "ok";
        }
    }
}
