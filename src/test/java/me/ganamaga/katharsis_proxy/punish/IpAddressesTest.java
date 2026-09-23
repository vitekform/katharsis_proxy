package me.ganamaga.katharsis_proxy.punish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressesTest {

    @Test
    @DisplayName("socket addresses are written as bare addresses")
    void ofSocket() {
        assertEquals("10.0.0.1", IpAddresses.of(new InetSocketAddress("10.0.0.1", 25565)).orElseThrow());
        assertEquals("0:0:0:0:0:0:0:1", IpAddresses.of(new InetSocketAddress("::1", 25565)).orElseThrow());
        assertEquals("10.0.0.1", IpAddresses.of(new InetSocketAddress("::ffff:10.0.0.1", 1)).orElseThrow(),
                "an IPv4-mapped address is written as IPv4");
        assertTrue(IpAddresses.of(InetSocketAddress.createUnresolved("example.com", 1)).isEmpty());
        assertTrue(IpAddresses.of(null).isEmpty());
    }

    @Test
    @DisplayName("literals are recognised and normalised; names never are")
    void literals() {
        assertEquals("192.168.1.20", IpAddresses.parseLiteral("192.168.1.20").orElseThrow());
        assertEquals("2001:db8:0:0:0:0:0:1", IpAddresses.parseLiteral("2001:db8::1").orElseThrow());
        assertTrue(IpAddresses.parseLiteral("256.1.1.1").isEmpty());
        assertTrue(IpAddresses.parseLiteral("Kraxie").isEmpty());
        assertTrue(IpAddresses.parseLiteral("1.2.3").isEmpty());
        assertTrue(IpAddresses.parseLiteral("::g").isEmpty());
        assertTrue(IpAddresses.parseLiteral("").isEmpty());
    }
}
