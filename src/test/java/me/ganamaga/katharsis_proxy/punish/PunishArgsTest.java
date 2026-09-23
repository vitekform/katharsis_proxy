package me.ganamaga.katharsis_proxy.punish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishArgsTest {

    private static PunishArgs parse(String line) {
        return PunishArgs.parse(line.isEmpty() ? new String[0] : line.split(" ")).orElseThrow();
    }

    @Test
    @DisplayName("the first word is the target and the rest are kept in order")
    void targetAndWords() {
        PunishArgs args = parse("Kraxie 7d spamming the chat");
        assertEquals("Kraxie", args.target());
        assertEquals(List.of("7d", "spamming", "the", "chat"), args.words());
        assertEquals("spamming the chat", args.rest(1));
        assertNull(args.rest(4));
        assertFalse(args.global() || args.accountOnly() || args.ipOnly() || args.silent() || args.force());
        assertNull(args.scope());
    }

    @Test
    @DisplayName("flags are picked out wherever they stand")
    void flagsAnywhere() {
        PunishArgs args = parse("-q Kraxie 1h -s Survival x-ray -A use -f");
        assertEquals("Kraxie", args.target());
        assertEquals("Survival", args.scope());
        assertTrue(args.silent() && args.accountOnly() && args.force());
        assertEquals("1h x-ray use", args.rest(0), "words merely containing a dash are left alone");
    }

    @Test
    @DisplayName("-s without a name leaves an empty scope, which names nothing")
    void scopeWithoutName() {
        assertEquals("", parse("Kraxie -s").scope());
    }

    @Test
    @DisplayName("no target, no arguments")
    void noTarget() {
        assertTrue(PunishArgs.parse(new String[0]).isEmpty());
        assertTrue(PunishArgs.parse(new String[]{"-g", "-q"}).isEmpty());
    }
}
