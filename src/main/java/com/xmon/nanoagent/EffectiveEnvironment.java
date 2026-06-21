package com.xmon.nanoagent;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class EffectiveEnvironment {

    private static final String DOTENV_FILE = ".env";

    private final Map<String, String> values;

    private EffectiveEnvironment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    static EffectiveEnvironment load(Path workingDirectory, Map<String, String> inherited) {
        Map<String, String> merged = new HashMap<>(inherited);
        Path dotenvDirectory = findDotenvDirectory(workingDirectory.toAbsolutePath().normalize());
        if (dotenvDirectory != null) {
            Dotenv dotenv = Dotenv.configure()
                    .directory(dotenvDirectory.toString())
                    .filename(DOTENV_FILE)
                    .load();
            for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        String baseUrl = merged.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            merged.remove("ANTHROPIC_AUTH_TOKEN");
        }
        return new EffectiveEnvironment(merged);
    }

    private static Path findDotenvDirectory(Path start) {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(DOTENV_FILE))) {
                return directory;
            }
        }
        return null;
    }

    String require(String name) {
        if (!values.containsKey(name)) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return values.get(name);
    }

    String get(String name) {
        return values.get(name);
    }

    Map<String, String> values() {
        return values;
    }
}
