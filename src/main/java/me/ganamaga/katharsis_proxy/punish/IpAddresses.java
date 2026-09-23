package me.ganamaga.katharsis_proxy.punish;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;

/**
 * Turns connection addresses into the text stored with punishments and
 * history, so the same address is always written the same way.
 */
public final class IpAddresses {

    private IpAddresses() {
    }

    /**
     * The address of {@code socket}, IPv4-mapped IPv6 addresses written as
     * IPv4 and any IPv6 zone dropped; empty if it is not an IP socket or was
     * never resolved.
     */
    public static Optional<String> of(SocketAddress socket) {
        if (!(socket instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return Optional.empty();
        }
        return Optional.of(text(inet.getAddress()));
    }

    static String text(InetAddress address) {
        String text = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int zone = text.indexOf('%');
            if (zone >= 0) {
                text = text.substring(0, zone);
            }
        }
        return text;
    }

    /**
     * Whether {@code input} is an IP address literal, as staff may type one
     * instead of a player name. Never resolves host names.
     */
    public static boolean isLiteral(String input) {
        return parseLiteral(input).isPresent();
    }

    /** {@code input} as stored, if it is an IPv4 or IPv6 literal. */
    public static Optional<String> parseLiteral(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        // Out-of-range octets are refused here: InetAddress would take them
        // for a host name and go to DNS.
        boolean ipv4 = input.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                && java.util.Arrays.stream(input.split("\\.")).allMatch(octet -> Integer.parseInt(octet) <= 255);
        boolean ipv6 = input.indexOf(':') >= 0 && input.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return Optional.empty();
        }
        try {
            // A literal is parsed, never looked up.
            return Optional.of(text(InetAddress.getByName(input)));
        } catch (java.net.UnknownHostException e) {
            return Optional.empty();
        }
    }
}
