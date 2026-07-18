package io.regisx001.turbolb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Config} class.
 *
 * Ported from the original C++ test_server.cpp concepts:
 * - Construction / loading
 * - Key/value access with type coercion
 * - Comment handling
 * - Missing key errors
 * - Default values
 * - Config path resolution
 */
class ConfigTest {

    // ── Loading & Basic Access ─────────────────────────────────────────────

    @Test
    void loadsPropertiesFromFile(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("server.host=127.0.0.1\n" +
                        "server.port=9090\n" +
                        "server.debug=true\n").getBytes());

        Config config = Config.load(props.toString());

        assertEquals("127.0.0.1", config.getString("server.host"));
        assertEquals(9090, config.getInt("server.port"));
        assertTrue(config.getBool("server.debug"));
    }

    // ── Comment Handling ──────────────────────────────────────────────────

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("# This is a comment\n" +
                        "; This is also a comment\n" +
                        "\n" +
                        "key=value\n" +
                        "# another comment\n").getBytes());

        Config config = Config.load(props.toString());
        assertEquals("value", config.getString("key"));
        assertFalse(config.getAll().containsKey(""));
    }

    @Test
    void ignoresHashComments(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("# comment line\n" +
                        "host=localhost\n").getBytes());

        Config config = Config.load(props.toString());
        assertEquals("localhost", config.getString("host"));
    }

    @Test
    void ignoresSemicolonComments(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("; comment line\n" +
                        "host=localhost\n").getBytes());

        Config config = Config.load(props.toString());
        assertEquals("localhost", config.getString("host"));
    }

    // ── Whitespace Handling ────────────────────────────────────────────────

    @Test
    void trimsWhitespaceAroundKeysAndValues(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "  key  =  value  \n".getBytes());

        Config config = Config.load(props.toString());
        assertEquals("value", config.getString("key"));
    }

    // ── Type Coercion ─────────────────────────────────────────────────────

    @Test
    void getIntReturnsParsedInteger(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "port=8080\n".getBytes());

        Config config = Config.load(props.toString());
        assertEquals(8080, config.getInt("port"));
    }

    @Test
    void getIntWithDefaultReturnsDefaultWhenMissing(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "other=1\n".getBytes());

        Config config = Config.load(props.toString());
        assertEquals(42, config.getInt("port", 42));
    }

    @Test
    void getIntThrowsOnNonIntegerValue(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "port=notanumber\n".getBytes());

        Config config = Config.load(props.toString());
        assertThrows(IllegalArgumentException.class, () -> config.getInt("port"));
    }

    // ── Boolean Parsing ────────────────────────────────────────────────────

    @Test
    void parsesBooleanTrueVariants(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("a=true\nb=TRUE\nc=yes\nd=YES\ne=1\n").getBytes());

        Config config = Config.load(props.toString());
        assertTrue(config.getBool("a"));
        assertTrue(config.getBool("b"));
        assertTrue(config.getBool("c"));
        assertTrue(config.getBool("d"));
        assertTrue(config.getBool("e"));
    }

    @Test
    void parsesBooleanFalseVariants(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("a=false\nb=FALSE\nc=no\nd=NO\ne=0\n").getBytes());

        Config config = Config.load(props.toString());
        assertFalse(config.getBool("a"));
        assertFalse(config.getBool("b"));
        assertFalse(config.getBool("c"));
        assertFalse(config.getBool("d"));
        assertFalse(config.getBool("e"));
    }

    @Test
    void getBoolThrowsOnInvalidValue(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "flag=maybe\n".getBytes());

        Config config = Config.load(props.toString());
        assertThrows(IllegalArgumentException.class, () -> config.getBool("flag"));
    }

    @Test
    void getBoolWithDefaultReturnsDefaultWhenMissing(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "other=1\n".getBytes());

        Config config = Config.load(props.toString());
        assertTrue(config.getBool("debug", true));
        assertFalse(config.getBool("verbose", false));
    }

    // ── Missing Keys ───────────────────────────────────────────────────────

    @Test
    void getStringThrowsOnMissingKey(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "existing=yes\n".getBytes());

        Config config = Config.load(props.toString());
        assertThrows(IllegalArgumentException.class, () -> config.getString("nonexistent"));
    }

    @Test
    void getStringWithDefaultReturnsDefaultWhenMissing(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "".getBytes());

        Config config = Config.load(props.toString());
        assertEquals("fallback", config.getString("missing", "fallback"));
    }

    // ── getAll ─────────────────────────────────────────────────────────────

    @Test
    void getAllReturnsAllProperties(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("a=1\nb=2\nc=3\n").getBytes());

        Config config = Config.load(props.toString());
        assertEquals(3, config.getAll().size());
        assertEquals("1", config.getAll().get("a"));
    }

    @Test
    void getAllReturnsDefensiveCopy(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "key=value\n".getBytes());

        Config config = Config.load(props.toString());
        config.getAll().put("injected", "bad");
        assertThrows(IllegalArgumentException.class, () -> config.getString("injected"));
    }

    // ── Path Resolution ───────────────────────────────────────────────────

    @Test
    void resolvePathFromCliFlag(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("myconfig.properties");
        Files.write(props, "key=val\n".getBytes());

        String[] args = { "--config", props.toString() };
        String resolved = Config.resolvePath(args);
        assertEquals(props.toString(), resolved);
    }

    @Test
    void resolvePathReturnsNullForMissingCliFlag() {
        String[] args = { "--config", "/nonexistent/path.properties" };
        assertNull(Config.resolvePath(args));
    }

    @Test
    void resolvePathReturnsNullForMissingCliFile() {
        // --config pointing to a non-existent file should return null
        String[] args = { "--config", "/nonexistent/path.properties" };
        assertNull(Config.resolvePath(args));
    }

    // ── Load from args (integration) ──────────────────────────────────────

    @Test
    void loadFromCliFlag(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props, "server.port=9999\n".getBytes());

        Config config = Config.load(new String[] { "--config", props.toString() });
        assertEquals(9999, config.getInt("server.port"));
    }

    @Test
    void loadWithMissingConfigFileThrows() {
        // --config pointing to a file that doesn't exist should throw
        assertThrows(IOException.class,
                () -> Config.load(new String[] { "--config", "/definitely/does/not/exist.properties" }));
    }

    // ── Empty File ─────────────────────────────────────────────────────────

    @Test
    void emptyFileProducesEmptyConfig(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("empty.properties");
        Files.write(props, "".getBytes());

        Config config = Config.load(props.toString());
        assertTrue(config.getAll().isEmpty());
    }

    // ── Line Without Equals Sign ───────────────────────────────────────────

    @Test
    void ignoresLinesWithoutEquals(@TempDir Path tempDir) throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.write(props,
                ("justtext\n" +
                        "key=value\n").getBytes());

        Config config = Config.load(props.toString());
        assertEquals(1, config.getAll().size());
        assertEquals("value", config.getString("key"));
    }
}
