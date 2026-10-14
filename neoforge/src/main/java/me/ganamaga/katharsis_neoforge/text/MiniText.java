package me.ganamaga.katharsis_neoforge.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the proxy's MiniMessage templates into Minecraft's JSON text format.
 *
 * <p>NeoForge ships no Adventure, and bundling it into a mod is more trouble
 * than the templates are worth: the proxy's {@code lang/} files only colour and
 * decorate text. So this reads that part of MiniMessage — named and hex
 * colours, the decorations and their negations, {@code <reset>} and
 * {@code <newline>} — and leaves anything else as MiniMessage would leave an
 * unknown tag, as literal text. Interactive and gradient tags keep their text
 * and drop the effect.</p>
 *
 * <p>Free of Minecraft types so it can be tested without a server;
 * {@link Texts} turns the JSON into a component.</p>
 */
public final class MiniText {

    private static final Set<String> COLORS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    private static final Map<String, String> DECORATIONS = Map.ofEntries(
            Map.entry("bold", "bold"), Map.entry("b", "bold"),
            Map.entry("italic", "italic"), Map.entry("i", "italic"), Map.entry("em", "italic"),
            Map.entry("underlined", "underlined"), Map.entry("u", "underlined"),
            Map.entry("strikethrough", "strikethrough"), Map.entry("st", "strikethrough"),
            Map.entry("obfuscated", "obfuscated"), Map.entry("obf", "obfuscated"));

    /** Tags whose effect is not drawn here; their text still is. */
    private static final Set<String> IGNORED = Set.of(
            "gradient", "rainbow", "transition", "hover", "click", "insert", "insertion", "font", "shadow",
            "pride", "key", "lang", "tr", "translate");

    private MiniText() {
    }

    /**
     * Renders {@code template}.
     *
     * @param upright clears the italics Minecraft puts on item names and lore
     *                unless told otherwise
     */
    public static JsonObject toJson(String template, boolean upright) {
        Parser parser = new Parser();
        parser.parse(template == null ? "" : template);
        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        if (upright) {
            root.addProperty("italic", false);
        }
        if (!parser.spans.isEmpty()) {
            JsonArray extra = new JsonArray();
            parser.spans.forEach(extra::add);
            root.add("extra", extra);
        }
        return root;
    }

    /** The template's text without any formatting, for when the JSON cannot be read. */
    public static String plain(String template) {
        JsonObject json = toJson(template, false);
        StringBuilder text = new StringBuilder();
        if (json.has("extra")) {
            json.getAsJsonArray("extra").forEach(span -> text.append(span.getAsJsonObject().get("text").getAsString()));
        }
        return text.toString();
    }

    /** One open tag and the style it adds. */
    private record Open(String name, Map<String, Object> style) {
    }

    private static final class Parser {

        private final List<JsonObject> spans = new ArrayList<>();
        private final List<Open> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        void parse(String template) {
            int i = 0;
            while (i < template.length()) {
                char c = template.charAt(i);
                if (c == '\\' && i + 1 < template.length()
                        && (template.charAt(i + 1) == '<' || template.charAt(i + 1) == '\\')) {
                    text.append(template.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '<') {
                    int end = template.indexOf('>', i + 1);
                    if (end > i + 1 && tag(template.substring(i + 1, end))) {
                        i = end + 1;
                        continue;
                    }
                }
                text.append(c);
                i++;
            }
            flush();
        }

        /** Applies one tag's contents; false if it is not a tag, which then stays as text. */
        private boolean tag(String body) {
            if (body.indexOf('<') >= 0) {
                return false;
            }
            if (body.startsWith("/")) {
                return close(name(body.substring(1)));
            }
            String name = name(body);
            String argument = body.indexOf(':') < 0 ? "" : body.substring(body.indexOf(':') + 1);
            if (name.equals("newline") || name.equals("br")) {
                text.append('\n');
                return true;
            }
            if (name.equals("reset")) {
                flush();
                stack.clear();
                return true;
            }
            Map<String, Object> style = style(name, argument);
            if (style == null) {
                return false;
            }
            flush();
            stack.add(new Open(name, style));
            return true;
        }

        private static String name(String body) {
            int colon = body.indexOf(':');
            return (colon < 0 ? body : body.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        }

        /** The style {@code name} adds, or null if it is not a tag this knows. */
        private static Map<String, Object> style(String name, String argument) {
            Map<String, Object> style = new LinkedHashMap<>();
            String color = color(name);
            if (name.equals("color") || name.equals("colour") || name.equals("c")) {
                color = color(argument.strip().toLowerCase(Locale.ROOT));
            }
            if (color != null) {
                style.put("color", color);
                return style;
            }
            boolean negated = name.startsWith("!");
            String decoration = DECORATIONS.get(negated ? name.substring(1) : name);
            if (decoration != null) {
                style.put(decoration, !negated && !argument.strip().equalsIgnoreCase("false"));
                return style;
            }
            return IGNORED.contains(name) ? style : null;
        }

        private static String color(String name) {
            if (name.equals("grey")) {
                return "gray";
            }
            if (name.equals("dark_grey")) {
                return "dark_gray";
            }
            if (COLORS.contains(name)) {
                return name;
            }
            if (name.matches("#[0-9a-f]{6}")) {
                return name;
            }
            return null;
        }

        /** Closes the innermost {@code name} and whatever was opened inside it. */
        private boolean close(String name) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).name().equals(name)) {
                    flush();
                    stack.subList(i, stack.size()).clear();
                    return true;
                }
            }
            // A stray closing tag closes nothing, and is dropped as MiniMessage does.
            return DECORATIONS.containsKey(name) || color(name) != null || IGNORED.contains(name)
                    || name.equals("color") || name.equals("colour") || name.equals("c");
        }

        private void flush() {
            if (text.isEmpty()) {
                return;
            }
            JsonObject span = new JsonObject();
            span.addProperty("text", text.toString());
            Map<String, Object> style = new LinkedHashMap<>();
            stack.forEach(open -> style.putAll(open.style()));
            style.forEach((key, value) -> {
                if (value instanceof Boolean flag) {
                    span.addProperty(key, flag);
                } else {
                    span.addProperty(key, (String) value);
                }
            });
            spans.add(span);
            text.setLength(0);
        }
    }
}
