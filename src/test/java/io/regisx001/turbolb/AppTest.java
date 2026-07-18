package io.regisx001.turbolb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Smoke test verifying the App class loads without error.
 */
class AppTest {

    @Test
    void appClassLoads() {
        assertDoesNotThrow(() -> Class.forName("io.regisx001.turbolb.App"));
    }
}
