package me.ganamaga.katharsis_proxy.punish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    private static Duration parsed(String input) {
        return DurationParser.parse(input).orElseThrow().duration();
    }

    @Test
    @DisplayName("each unit is read at its size")
    void units() {
        assertEquals(Duration.ofSeconds(10), parsed("10s"));
        assertEquals(Duration.ofMinutes(30), parsed("30m"));
        assertEquals(Duration.ofHours(5), parsed("5h"));
        assertEquals(Duration.ofDays(4), parsed("4d"));
        assertEquals(Duration.ofDays(21), parsed("3w"));
        assertEquals(Duration.ofDays(60), parsed("2mo"));
        assertEquals(Duration.ofDays(365), parsed("1y"));
    }

    @Test
    @DisplayName("units add up, in any order and case")
    void combined() {
        assertEquals(Duration.ofDays(365 + 60 + 21 + 4).plusHours(5).plusMinutes(30).plusSeconds(10),
                parsed("1y2mo3w4d5h30m10s"));
        assertEquals(Duration.ofHours(1).plusMinutes(30), parsed("30M1H"));
        assertEquals(Duration.ofDays(2), parsed("1d1d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"perm", "permanent", "forever", "-1", "PERM"})
    @DisplayName("permanent spellings")
    void permanent(String input) {
        assertTrue(DurationParser.parse(input).orElseThrow().isPermanent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "10", "d", "10x", "1d 2h", "0s", "0d0h", "-5m",
            "99999999999999999999d", "999999999999y"})
    @DisplayName("anything else is not a length")
    void invalid(String input) {
        assertTrue(DurationParser.parse(input).isEmpty(), input);
    }

    @Test
    @DisplayName("format writes lengths back in the form parse reads")
    void format() {
        assertEquals("perm", DurationParser.format(PunishmentLength.PERMANENT));
        assertEquals("1d12h", DurationParser.format(PunishmentLength.of(Duration.ofHours(36))));
        assertEquals("1y2mo3w4d5h30m10s", DurationParser.format(DurationParser.parse("1y2mo3w4d5h30m10s").orElseThrow()));
    }
}
