package io.regisx001.turbolb.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration loader for .properties files.
 *
 * Resolves config path with this precedence:
 * 1. --config <path> command-line flag
 * 2. TURBOLB_CONFIG environment variable
 * 3. $HOME/.turbolb/config.properties (user default)
 * 4. .turbolb/config.properties (CWD default)
 *
 * Supports key=value pairs, # and ; comment lines, and
 * whitespace trimming around keys and values.
 */
public class Config {

    private final Map<String, String> properties;

    public Config(Map<String, String> properties) {
        this.properties = new HashMap<>(properties);
    }

    // ── Factory ────────────────────────────────────────────────────────────

    /**
     * Loads configuration from a .properties file.
     *
     * @param path path to the .properties file
     * @return a populated Config instance
     * @throws IOException if the file cannot be read
     */
    public static Config load(String path) throws IOException {
        Map<String, String> props = new HashMap<>();
        List<String> lines = Files.readAllLines(Paths.get(path));

        for (String raw : lines) {
            String line = raw.trim();

            // Skip comments and blank lines
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }

            int sep = line.indexOf('=');
            if (sep < 0)
                continue;

            String key = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (!key.isEmpty()) {
                props.put(key, value);
            }
        }

        return new Config(props);
    }

    /**
     * Resolves the config file path and loads it.
     *
     * Precedence:
     * 1. --config <path> in args
     * 2. TURBOLB_CONFIG environment variable
     * 3. $HOME/.turbolb/config.properties
     * 4. .turbolb/config.properties (relative to CWD)
     *
     * @param args command-line arguments (may contain --config <path>)
     * @return a populated Config instance
     * @throws IOException if the file cannot be read or no config source found
     */
    public static Config load(String[] args) throws IOException {
        String path = resolvePath(args);
        if (path == null) {
            throw new IOException(
                    "No configuration file found. Use --config <path>, " +
                            "set TURBOLB_CONFIG, or place .turbolb/config.properties " +
                            "in your home or working directory.");
        }
        return load(path);
    }

    // ── Path Resolution ────────────────────────────────────────────────────

    static String resolvePath(String[] args) {
        // 1. --config <path> flag
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                String path = args[i + 1];
                if (Files.exists(Paths.get(path))) {
                    return path;
                }
                return null;
            }
        }

        // 2. TURBOLB_CONFIG environment variable
        String envPath = System.getenv("TURBOLB_CONFIG");
        if (envPath != null && Files.exists(Paths.get(envPath))) {
            return envPath;
        }

        // 3. $HOME/.turbolb/config.properties
        String home = System.getenv("HOME");
        if (home != null) {
            Path homeConfig = Paths.get(home, ".turbolb", "config.properties");
            if (Files.exists(homeConfig)) {
                return homeConfig.toString();
            }
        }

        // 4. .turbolb/config.properties (CWD)
        Path cwdConfig = Paths.get(".turbolb", "config.properties");
        if (Files.exists(cwdConfig)) {
            return cwdConfig.toAbsolutePath().toString();
        }

        return null;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /**
     * Returns the string value for the given key.
     *
     * @param key the property key
     * @return the string value
     * @throws IllegalArgumentException if the key is not found
     */
    public String getString(String key) {
        String value = properties.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return value;
    }

    /**
     * Returns the string value for the given key, or a default if missing.
     */
    public String getString(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the integer value for the given key.
     *
     * @throws IllegalArgumentException if the key is missing or not a valid integer
     */
    public int getInt(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Config key '" + key + "' has non-integer value: " + value, e);
        }
    }

    /**
     * Returns the integer value for the given key, or a default if missing.
     */
    public int getInt(String key, int defaultValue) {
        String value = properties.get(key);
        if (value == null)
            return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Config key '" + key + "' has non-integer value: " + value, e);
        }
    }

    /**
     * Returns the boolean value for the given key.
     *
     * Supports: "true"/"false", "yes"/"no", "1"/"0" (case-insensitive).
     *
     * @throws IllegalArgumentException if the key is missing or not a valid boolean
     */
    public boolean getBool(String key) {
        String value = getString(key);
        return parseBool(key, value);
    }

    /**
     * Returns the boolean value for the given key, or a default if missing.
     */
    public boolean getBool(String key, boolean defaultValue) {
        String value = properties.get(key);
        if (value == null)
            return defaultValue;
        return parseBool(key, value);
    }

    private static boolean parseBool(String key, String value) {
        String lower = value.toLowerCase();
        switch (lower) {
            case "true":
            case "yes":
            case "1":
                return true;
            case "false":
            case "no":
            case "0":
                return false;
            default:
                throw new IllegalArgumentException(
                        "Config key '" + key + "' has non-boolean value: " + value +
                                " (expected true/false, yes/no, 1/0)");
        }
    }

    /**
     * Returns the raw map of all properties (defensive copy).
     */
    public Map<String, String> getAll() {
        return new HashMap<>(properties);
    }
}
