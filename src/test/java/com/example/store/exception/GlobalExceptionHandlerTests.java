package com.example.store.exception;

import jakarta.validation.constraints.Min;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @RestController
    static class NamedParameterController {

        @GetMapping("/named")
        String named(@RequestParam("customer-id") @Min(1) Long customerId) {
            return "ok";
        }
    }
}
