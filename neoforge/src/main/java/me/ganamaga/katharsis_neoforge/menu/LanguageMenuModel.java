package me.ganamaga.katharsis_neoforge.menu;

import me.ganamaga.katharsis_neoforge.protocol.ProxyResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where each language sits in the selector.
 *
 * <p>Pure layout arithmetic, with no Minecraft types, so it can be tested without
 * a server. {@link LanguageMenu} turns it into a chest.</p>
 */
public final class LanguageMenuModel {

    /** A chest row. */
    private static final int ROW = 9;

    /** A double chest, the largest inventory that can be opened. */
    public static final int MAX_SLOTS = 54;

    private final String currentLanguage;
    private final List<Entry> entries;
    private final int size;
    private final String title;
    private final String selectHint;
    private final String currentHint;

    private LanguageMenuModel(String currentLanguage, List<Entry> entries, int size,
                              String title, String selectHint, String currentHint) {
        this.currentLanguage = currentLanguage;
        this.entries = List.copyOf(entries);
        this.size = size;
        this.title = title;
        this.selectHint = selectHint;
        this.currentHint = currentHint;
    }

    /**
     * Lays out the languages from a {@code get_language_menu} reply.
     *
     * <p>A single row is centred, so a two-language menu reads as a pair in the
     * middle rather than two items pushed against the left edge. Beyond one row
     * the slots simply fill in order.</p>
     */
    public static LanguageMenuModel from(ProxyResponse response) {
        ProxyResponse.Menu menu = response.requireMenu();
        List<Map.Entry<String, String>> languages =
                new ArrayList<>(menu.languages().entrySet()).stream()
                        .limit(MAX_SLOTS)
                        .toList();

        int size = Math.min(MAX_SLOTS, Math.max(ROW, ceilToRow(languages.size())));
        int firstSlot = languages.size() <= ROW ? (ROW - languages.size()) / 2 : 0;

        List<Entry> entries = new ArrayList<>(languages.size());
        for (int i = 0; i < languages.size(); i++) {
            Map.Entry<String, String> language = languages.get(i);
            entries.add(new Entry(firstSlot + i, language.getKey(), language.getValue(),
                    language.getKey().equals(response.language())));
        }

        return new LanguageMenuModel(response.language(), entries, size,
                menu.title(), menu.selectHint(), menu.currentHint());
    }

    private static int ceilToRow(int count) {
        return ((count + ROW - 1) / ROW) * ROW;
    }

    /** The language the viewer currently uses. */
    public String currentLanguage() {
        return currentLanguage;
    }

    /** Every language on show, in slot order. */
    public List<Entry> entries() {
        return entries;
    }

    /** Inventory size in slots, always a multiple of nine. */
    public int size() {
        return size;
    }

    /** Menu title, as a MiniMessage template from the proxy. */
    public String title() {
        return title;
    }

    /** Lore for a language the viewer may pick, as a MiniMessage template. */
    public String selectHint() {
        return selectHint;
    }

    /** Lore for the language the viewer already uses, as a MiniMessage template. */
    public String currentHint() {
        return currentHint;
    }

    /** The language at {@code slot}, if a language sits there at all. */
    public Optional<Entry> entryAt(int slot) {
        return entries.stream().filter(entry -> entry.slot() == slot).findFirst();
    }

    /**
     * One language in the selector.
     *
     * @param slot        inventory slot it occupies
     * @param code        language code, e.g. {@code cs_CZ}
     * @param displayName the language's own name, as a MiniMessage template
     * @param current     whether the viewer already uses this language
     */
    public record Entry(int slot, String code, String displayName, boolean current) {

        /**
         * The resource pack model showing this language's flag: {@code flags_}
         * and the code's region in lower case, so {@code cs_CZ} is
         * {@code flags_cz} and {@code en_US} is {@code flags_us}. Empty for a
         * code with no region.
         */
        public Optional<String> flagModel() {
            return LanguageMenuModel.flagModel(code);
        }
    }

    /**
     * The resource pack model showing {@code code}'s flag, as described on
     * {@link Entry#flagModel()}.
     */
    public static Optional<String> flagModel(String code) {
        int separator = code.indexOf('_');
        if (separator < 0 || separator == code.length() - 1) {
            return Optional.empty();
        }
        return Optional.of("flags_" + code.substring(separator + 1).toLowerCase(Locale.ROOT));
    }
}
