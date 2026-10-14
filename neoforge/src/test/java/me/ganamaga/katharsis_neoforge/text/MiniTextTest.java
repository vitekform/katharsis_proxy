package me.ganamaga.katharsis_neoforge.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniTextTest {

    private static String json(String template) {
        return MiniText.toJson(template, false).toString();
    }

    @Test
    @DisplayName("colours apply until closed")
    void colours() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"Pick \",\"color\":\"gold\"},"
                        + "{\"text\":\"one\",\"color\":\"white\"},{\"text\":\".\",\"color\":\"gold\"},{\"text\":\" done\"}]}",
                json("<gold>Pick <white>one</white>.</gold> done"));
    }

    @Test
    @DisplayName("decorations stack on colours, and a negation turns one off")
    void decorations() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"a\",\"color\":\"red\",\"bold\":true},"
                        + "{\"text\":\"b\",\"color\":\"red\",\"bold\":false}]}",
                json("<red><b>a<!bold>b"));
    }

    @Test
    @DisplayName("hex, grey and color: all name a colour")
    void colourForms() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"x\",\"color\":\"#00ff00\"},"
                        + "{\"text\":\"y\",\"color\":\"gray\"},{\"text\":\"z\",\"color\":\"aqua\"}]}",
                json("<#00FF00>x<grey>y<color:aqua>z"));
    }

    @Test
    @DisplayName("newline breaks the line and reset clears every style")
    void newlineAndReset() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"a\\nb\",\"color\":\"red\"},{\"text\":\"c\"}]}",
                json("<red>a<newline>b<reset>c"));
    }

    @Test
    @DisplayName("unknown tags and escapes stay as text")
    void literals() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"<player> < 3 <b\"}]}",
                json("<player> \\< 3 <b"));
    }

    @Test
    @DisplayName("hover and gradient keep their text without the effect")
    void ignored() {
        assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"hi\"}]}",
                json("<gradient:red:blue><hover:show_text:'x'>hi</hover></gradient>"));
    }

    @Test
    @DisplayName("upright text says so on the root, for item names and lore")
    void upright() {
        assertEquals("{\"text\":\"\",\"italic\":false}", MiniText.toJson("", true).toString());
        assertEquals("Čeština", MiniText.plain("<white>Čeština</white>"));
    }
}
