package dev.nodera.core.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which of this machine's addresses to tell other peers to dial.
 *
 * <h2>Why "the first site-local IPv4" is not good enough</h2>
 *
 * <p>A node that advertises the wrong address is not broken in any way it can see: it announces, it
 * is accepted, it appears in every directory — and nothing can reach it. The failure surfaces
 * somewhere else entirely, as a peer that was given work and silently never did any of it. That is
 * what happened on a developer machine carrying a container bridge and a VPN alongside its real NIC:
 * a joiner advertised its VPN address, the host addressed that peer's committee seats there, the
 * sends failed, and the observable symptom was "this worker holds zero regions" with nothing at all
 * pointing at the cause.
 *
 * <p>{@code isSiteLocalAddress()} cannot separate these, because it is true for the whole of RFC1918
 * — {@code 10/8}, {@code 172.16/12} and {@code 192.168/16} alike — so a docker bridge on
 * {@code 172.17.x}, a VPN tunnel on {@code 10.x} and a real LAN NIC on {@code 192.168.x} are equally
 * eligible. {@code NetworkInterface.isVirtual()} does not help either: it means "sub-interface"
 * ({@code eth0:1}), not "virtual device", so tunnels and bridges pass it.
 *
 * <p>So this picks by interface NAME as well, which is a heuristic and is documented as one. It
 * prefers the families real NICs use, refuses the families bridges and tunnels use, and when
 * nothing matches either list it falls back to the old behaviour rather than inventing an answer.
 * An operator who knows better sets the address explicitly and none of this runs.
 *
 * <p>Thread-context: pure and stateless; {@link #resolveHost} performs interface enumeration.
 */
public final class NetworkAddresses {

    /** Interface name families that are never a good advertise address. */
    private static final List<String> REFUSED = List.of(
            "docker", "br-", "virbr", "veth", "tun", "tap", "wg", "utun", "ppp", "zt", "tailscale");

    /** Interface name families that usually are one, in preference order. */
    private static final List<String> PREFERRED = List.of("en", "eth", "wl", "wlan", "wi-fi");

    /** What to advertise when nothing can be determined. */
    public static final String LOOPBACK = "127.0.0.1";

    private NetworkAddresses() {
    }

    /**
     * One candidate interface, reduced to what the choice actually depends on.
     *
     * @param name      the interface name, e.g. {@code eth0}, {@code docker0}, {@code tun0}.
     * @param addresses its IPv4 addresses, in enumeration order.
     * @param up        whether it is up.
     * @param loopback  whether it is the loopback interface.
     * @param virtual   whether the OS calls it a sub-interface.
     */
    public record CandidateNic(String name, List<String> addresses, boolean up, boolean loopback,
                               boolean virtual) {

        public CandidateNic {
            addresses = List.copyOf(addresses);
        }
    }

    /**
     * Choose the address to advertise, given every interface on the machine.
     *
     * <p>Pure, so the decision can be tested against a machine that does not exist: the shapes worth
     * covering — a bridge and a VPN alongside a real NIC, a VPN alone, nothing at all — are exactly
     * the ones that are impossible to arrange on a CI runner.
     *
     * @param candidates every interface, in enumeration order.
     * @return the address to advertise, or empty when there is no site-local candidate at all.
     */
    public static Optional<String> selectAdvertiseHost(List<CandidateNic> candidates) {
        List<CandidateNic> usable = new ArrayList<>();
        for (CandidateNic nic : candidates) {
            if (nic.up() && !nic.loopback() && !nic.virtual() && !siteLocal(nic).isEmpty()) {
                usable.add(nic);
            }
        }
        // A real NIC, by name. This is the answer on any ordinary machine.
        for (String family : PREFERRED) {
            for (CandidateNic nic : usable) {
                if (!refused(nic.name()) && lower(nic.name()).startsWith(family)) {
                    return Optional.of(siteLocal(nic).get(0));
                }
            }
        }
        // No recognised NIC name, but something that is at least not a bridge or a tunnel.
        for (CandidateNic nic : usable) {
            if (!refused(nic.name())) {
                return Optional.of(siteLocal(nic).get(0));
            }
        }
        // Everything left is refused. Answering with a bridge address is worse than saying so:
        // the caller falls back to loopback, which fails loudly and locally instead of quietly
        // and somewhere else.
        return Optional.empty();
    }

    /**
     * Resolve {@code "auto"} to a best-guess site-local IPv4; otherwise return the literal host.
     *
     * @param configured an explicit address, or {@code "auto"}/blank to detect one.
     * @return the address to advertise; {@link #LOOPBACK} when detection finds nothing.
     */
    public static String resolveHost(String configured) {
        if (configured != null && !configured.equalsIgnoreCase("auto") && !configured.isBlank()) {
            return configured;
        }
        return selectAdvertiseHost(enumerate()).orElse(LOOPBACK);
    }

    /** Every interface on this machine, reduced to {@link CandidateNic}s. */
    public static List<CandidateNic> enumerate() {
        List<CandidateNic> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                List<String> addresses = new ArrayList<>();
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        addresses.add(address.getHostAddress());
                    }
                }
                out.add(new CandidateNic(nic.getName(), addresses, nic.isUp(), nic.isLoopback(),
                        nic.isVirtual()));
            }
        } catch (Exception cannotEnumerate) {
            return List.of();
        }
        return out;
    }

    private static boolean refused(String name) {
        String lowered = lower(name);
        return REFUSED.stream().anyMatch(lowered::startsWith);
    }

    private static List<String> siteLocal(CandidateNic nic) {
        List<String> out = new ArrayList<>();
        for (String address : nic.addresses()) {
            if (isSiteLocal(address)) {
                out.add(address);
            }
        }
        return out;
    }

    /** RFC1918, decided on the text so the pure selector needs no {@link InetAddress}. */
    private static boolean isSiteLocal(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException notAnAddress) {
            return false;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
