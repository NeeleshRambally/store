package com.example.store.config;

import com.example.store.exception.ResourceNotFoundException;

import jakarta.validation.constraints.Min;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestIdFilterTests.ProbeController.class)
@Import(RequestIdFilterTests.ProbeController.class)
class RequestIdFilterTests {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void echoesAnAcceptableInboundRequestId() throws Exception {
        mockMvc.perform(get("/probe").header("X-Request-Id", "gw-7f3A.b_c-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "gw-7f3A.b_c-1"))
                .andExpect(content().string("gw-7f3A.b_c-1"));
    }

    @Test
    void generatesAUuidWhenNoneIsSent() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void replacesAnInboundRequestIdWithUnexpectedCharacters() throws Exception {
        mockMvc.perform(get("/probe").header("X-Request-Id", "bad id\nX-Injected: 1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void replacesAnInboundRequestIdLongerThan128Characters() throws Exception {
        String tooLong = "a".repeat(129);
        mockMvc.perform(get("/probe").header("X-Request-Id", tooLong))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", not(tooLong)))
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void badRequestsCarryTheRequestId() throws Exception {
        mockMvc.perform(get("/probe").param("min", "0").header("X-Request-Id", "req-400"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "req-400"));
    }

    @Test
    void notFoundResponsesCarryTheRequestId() throws Exception {
        mockMvc.perform(get("/probe/missing").header("X-Request-Id", "req-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req-404"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe(@RequestParam(defaultValue = "1") @Min(1) int min) {
            return MDC.get("requestId");
        }

        @GetMapping("/probe/missing")
        String missing() {
            throw new ResourceNotFoundException("Probe", 1L);
        }
    }
}
