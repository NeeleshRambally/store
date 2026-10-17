package com.example.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineGateTest {

    @Test
    void deliberatelyFailsToProveThePipelineGate() {
        assertEquals(1, 2, "deliberate failure to prove CI blocks on a red test");
    }
}
