package io.regisx001.turbolb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Smoke test verifying the main entry point loads without error.
 */
class AppTest {

    @Test
    void turboLBClassLoads() {
        assertDoesNotThrow(() -> Class.forName("io.regisx001.turbolb.TurboLB"));
    }
}
