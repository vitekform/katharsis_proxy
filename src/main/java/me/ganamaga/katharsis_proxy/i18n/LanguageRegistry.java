package me.ganamaga.katharsis_proxy.i18n;

import me.ganamaga.katharsis_proxy.entity.Player;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Holds the translated message templates, keyed by language code.
 *
 * <p>The languages bundled in the jar are written into {@code <data>/lang/} on
 * first run so admins can edit them, and any other {@code .yml} dropped in that
 * directory is picked up as an extra language.</p>
 */
public final class LanguageRegistry {

    /** Language files shipped in the jar under {@code lang/}. */
    private static final List<String> BUNDLED_LANGUAGES = List.of("en_US", "cs_CZ");

    /** Used when a message is missing from both the player's language and the configured default. */
    public static final String ROOT_LANGUAGE = "en_US";

    private static final String DIRECTORY = "lang";
    private static final String EXTENSION = ".yml";

    private final Logger logger;
    private final Path languageDirectory;

    private volatile Map<String, Map<MessageKey, String>> translations = Map.of();
    private volatile String defaultLanguage = ROOT_LANGUAGE;

    public LanguageRegistry(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.languageDirectory = dataDirectory.resolve(DIRECTORY);
    }

    /**
     * Writes out the bundled language files if they are missing, then loads
     * every language file in the directory.
     *
     * @param defaultLanguage language to fall back on, from the config
     * @throws ConfigurateException if the directory cannot be prepared
     */
    public void load(String defaultLanguage) throws ConfigurateException {
        try {
            Files.createDirectories(languageDirectory);
            for (String language : BUNDLED_LANGUAGES) {
                Path target = languageDirectory.resolve(language + EXTENSION);
                if (Files.notExists(target)) {
                    copyBundled(language, target);
                }
            }
        } catch (IOException e) {
            throw new ConfigurateException("Could not prepare " + languageDirectory, e);
        }

        Map<String, Map<MessageKey, String>> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(languageDirectory)) {
            List<Path> sorted = files
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .toList();
            for (Path file : sorted) {
                readLanguage(file, loaded);
            }
        } catch (IOException e) {
            throw new ConfigurateException("Could not list " + languageDirectory, e);
        }

        if (!loaded.containsKey(ROOT_LANGUAGE)) {
            throw new ConfigurateException("No " + ROOT_LANGUAGE + EXTENSION + " in " + languageDirectory
                    + "; it is the final fallback and must be present");
        }

        this.translations = Collections.unmodifiableMap(loaded);
        this.defaultLanguage = loaded.containsKey(defaultLanguage) ? defaultLanguage : ROOT_LANGUAGE;
        if (!loaded.containsKey(defaultLanguage)) {
            logger.warn("default-language '{}' has no file in {}; falling back to {}.",
                    defaultLanguage, languageDirectory, ROOT_LANGUAGE);
        }
        logger.info("Loaded {} language(s): {}", loaded.size(), String.join(", ", loaded.keySet()));
    }

    /** Language codes that have a file, in alphabetical order. */
    public Set<String> languages() {
        return translations.keySet();
    }

    /** Whether {@code language} has a file loaded. */
    public boolean isKnown(String language) {
        return translations.containsKey(language);
    }

    /** The configured fallback language, guaranteed to be loaded. */
    public String defaultLanguage() {
        return defaultLanguage;
    }

    /**
     * Returns the raw MiniMessage template for {@code key}, trying the player's
     * language, then the configured default, then {@value #ROOT_LANGUAGE}.
     * Never returns {@code null}: an entirely missing key yields its own name so
     * a broken translation is visible rather than fatal.
     */
    public String template(String language, MessageKey key) {
        String template = lookup(language, key);
        if (template == null) {
            template = lookup(defaultLanguage, key);
        }
        if (template == null) {
            template = lookup(ROOT_LANGUAGE, key);
        }
        return template != null ? template : key.key();
    }

    private String lookup(String language, MessageKey key) {
        Map<MessageKey, String> messages = translations.get(language);
        return messages == null ? null : messages.get(key);
    }

    private void readLanguage(Path file, Map<String, Map<MessageKey, String>> target) throws ConfigurateException {
        String fileName = file.getFileName().toString();
        String language = fileName.substring(0, fileName.length() - EXTENSION.length());
        if (language.length() > Player.LANGUAGE_MAX_LENGTH) {
            logger.warn("Ignoring {}: a language code may be at most {} characters.",
                    fileName, Player.LANGUAGE_MAX_LENGTH);
            return;
        }

        CommentedConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
        Map<MessageKey, String> messages = new EnumMap<>(MessageKey.class);
        List<String> missing = new ArrayList<>();
        for (MessageKey key : MessageKey.values()) {
            String value = root.node((Object[]) key.key().split("\\.")).getString();
            if (value == null) {
                missing.add(key.key());
            } else {
                messages.put(key, value);
            }
        }
        if (!missing.isEmpty()) {
            logger.warn("{} is missing {} message(s), which will fall back to another language: {}",
                    fileName, missing.size(), String.join(", ", missing));
        }
        target.put(language, Collections.unmodifiableMap(messages));
    }

    private void copyBundled(String language, Path target) throws IOException {
        String resource = DIRECTORY + "/" + language + EXTENSION;
        try (InputStream bundled = LanguageRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (bundled == null) {
                throw new IOException("The plugin jar does not contain " + resource);
            }
            Files.copy(bundled, target);
            logger.info("Wrote the default {} language file.", language);
        }
    }

    /** Normalises user input such as {@code EN-us} to the {@code en_US} file naming. */
    public static String normalise(String language) {
        String trimmed = language.trim().replace('-', '_');
        int underscore = trimmed.indexOf('_');
        if (underscore < 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.substring(0, underscore).toLowerCase(Locale.ROOT)
                + "_" + trimmed.substring(underscore + 1).toUpperCase(Locale.ROOT);
    }
}
