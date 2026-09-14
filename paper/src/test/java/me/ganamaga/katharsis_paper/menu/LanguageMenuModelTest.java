package me.ganamaga.katharsis_paper.menu;

import me.ganamaga.katharsis_paper.protocol.ProxyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageMenuModelTest {

    private static ProxyResponse reply(String current, String... codes) {
        LinkedHashMap<String, String> languages = new LinkedHashMap<>();
        for (String code : codes) {
            languages.put(code, "Name of " + code);
        }
        return new ProxyResponse("get_language_menu", "r1", "uuid", true, current, "",
                new ProxyResponse.Menu("<b>Title", "select", "current", languages), "");
    }

    @Test
    @DisplayName("a short menu is centred in one row")
    void shortMenuIsCentred() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("en_US", "cs_CZ", "en_US"));

        assertEquals(9, model.size(), "one row is enough");
        assertEquals(List.of(3, 4), model.entries().stream().map(LanguageMenuModel.Entry::slot).toList());
    }

    @Test
    @DisplayName("a single language sits in the middle")
    void singleLanguageIsCentred() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("en_US", "en_US"));

        assertEquals(9, model.size());
        assertEquals(4, model.entries().get(0).slot());
    }

    @Test
    @DisplayName("a full row is not shifted")
    void fullRowStartsAtZero() {
        LanguageMenuModel model = LanguageMenuModel.from(
                reply("a", "a", "b", "c", "d", "e", "f", "g", "h", "i"));

        assertEquals(9, model.size());
        assertEquals(0, model.entries().get(0).slot());
        assertEquals(8, model.entries().get(8).slot());
    }

    @Test
    @DisplayName("more than a row grows the chest and fills in order")
    void multipleRowsFillSequentially() {
        LanguageMenuModel model = LanguageMenuModel.from(
                reply("a", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

        assertEquals(18, model.size(), "ten languages need two rows");
        assertEquals(0, model.entries().get(0).slot());
        assertEquals(9, model.entries().get(9).slot());
    }

    @Test
    @DisplayName("the size is always a whole number of rows")
    void sizeIsAlwaysWholeRows() {
        for (int count = 1; count <= 30; count++) {
            String[] codes = new String[count];
            for (int i = 0; i < count; i++) {
                codes[i] = "l" + i;
            }
            LanguageMenuModel model = LanguageMenuModel.from(reply("l0", codes));

            assertEquals(0, model.size() % 9, () -> "size for " + model.entries().size() + " languages");
            assertTrue(model.size() >= 9 && model.size() <= LanguageMenuModel.MAX_SLOTS);
            assertTrue(model.entries().stream().allMatch(entry -> entry.slot() < model.size()),
                    "every language must fit inside the inventory");
        }
    }

    @Test
    @DisplayName("more languages than a double chest holds are dropped, not overflowed")
    void cappedAtADoubleChest() {
        String[] codes = new String[80];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = "l" + i;
        }

        LanguageMenuModel model = LanguageMenuModel.from(reply("l0", codes));

        assertEquals(LanguageMenuModel.MAX_SLOTS, model.size());
        assertEquals(LanguageMenuModel.MAX_SLOTS, model.entries().size());
        assertTrue(model.entries().stream().allMatch(entry -> entry.slot() < LanguageMenuModel.MAX_SLOTS));
    }

    @Test
    @DisplayName("exactly one entry is marked as the current language")
    void currentLanguageIsMarked() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("cs_CZ", "cs_CZ", "en_US", "de_DE"));

        assertEquals("cs_CZ", model.currentLanguage());
        assertEquals(1, model.entries().stream().filter(LanguageMenuModel.Entry::current).count());
        assertTrue(model.entryAt(3).orElseThrow().current());
        assertFalse(model.entryAt(4).orElseThrow().current());
    }

    @Test
    @DisplayName("a current language that is not on the menu marks nothing")
    void unknownCurrentMarksNothing() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("zz_ZZ", "cs_CZ", "en_US"));

        assertTrue(model.entries().stream().noneMatch(LanguageMenuModel.Entry::current));
    }

    @Test
    @DisplayName("an empty slot maps to no language")
    void emptySlotsMapToNothing() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("en_US", "cs_CZ", "en_US"));

        assertTrue(model.entryAt(0).isEmpty(), "slot 0 is blank in a centred row");
        assertTrue(model.entryAt(8).isEmpty());
        assertTrue(model.entryAt(-1).isEmpty(), "a click outside the menu");
    }

    @Test
    @DisplayName("the proxy's strings are carried through untouched")
    void carriesProxyStrings() {
        LanguageMenuModel model = LanguageMenuModel.from(reply("en_US", "en_US"));

        assertEquals("<b>Title", model.title());
        assertEquals("select", model.selectHint());
        assertEquals("current", model.currentHint());
        assertEquals("Name of en_US", model.entries().get(0).displayName());
    }
}
