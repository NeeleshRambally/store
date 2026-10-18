package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RequestBodyLimitFilterSliceTests.ProbeController.class)
@Import(RequestBodyLimitFilterSliceTests.ProbeController.class)
class RequestBodyLimitFilterSliceTests {

    private static final int LIMIT = 65536;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aBodyOneByteOverTheBoundIsAPayloadTooLargeProblemDetail() throws Exception {
        mockMvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-413")
                        .content(jsonOfLength(LIMIT + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("X-Request-Id", "req-413"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("Request body exceeds 65536 bytes"));
    }

    @Test
    void aBodyAtTheBoundPasses() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(jsonOfLength(LIMIT)))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(LIMIT - 14)));
    }

    @Test
    void aNormalBodyPasses() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"payload\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("3"));
    }

    @Test
    void aGetPasses() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("get"));
    }

    private static String jsonOfLength(int length) {
        String prefix = "{\"payload\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix;
    }

    @RestController
    static class ProbeController {

        @PostMapping("/probe")
        String probe(@RequestBody Map<String, String> body) {
            return String.valueOf(body.get("payload").length());
        }

        @GetMapping("/probe")
        String probe() {
            return "get";
        }
    }
}
