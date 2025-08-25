/*
 * Copyright 2025 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc;

/**
 * Port allocator configuration for ICE candidate gathering.
 * <p>
 * This allows restricting the local ephemeral port range used by the ICE
 * agent and configuring transport behavior through bit flags consistent
 * with the native PortAllocator flags.
 * <p>
 * Note: If both {@code minPort} and {@code maxPort} are set to valid values,
 * {@code minPort} must be less than or equal to {@code maxPort}. A value of
 * {@code 0} indicates "not specified" and uses the default behavior.
 *
 * @author Alex Andres
 */
public class PortAllocatorConfig {

    // Flags (must mirror the native PortAllocator flags).
    public static final int PORTALLOCATOR_DISABLE_UDP = 0x01;
    public static final int PORTALLOCATOR_DISABLE_STUN = 0x02;
    public static final int PORTALLOCATOR_DISABLE_RELAY = 0x04;
    public static final int PORTALLOCATOR_DISABLE_TCP = 0x08;
    public static final int PORTALLOCATOR_ENABLE_IPV6 = 0x40;
    public static final int PORTALLOCATOR_ENABLE_SHARED_SOCKET = 0x100;
    public static final int PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE = 0x200;
    public static final int PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION = 0x400;
    public static final int PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE = 0x800;
    public static final int PORTALLOCATOR_DISABLE_UDP_RELAY = 0x1000;
    public static final int PORTALLOCATOR_DISABLE_COSTLY_NETWORKS = 0x2000;
    public static final int PORTALLOCATOR_ENABLE_IPV6_ON_WIFI = 0x4000;
    public static final int PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS = 0x8000;
    public static final int PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS = 0x10000;

    /**
     * Minimum UDP/TCP port to use for candidate gathering, inclusive.
     * Set to 0 to leave unspecified.
     */
    public int minPort = 0;

    /**
     * Maximum UDP/TCP port to use for candidate gathering, inclusive.
     * Set to 0 to leave unspecified.
     */
    public int maxPort = 0;

    /**
     * Port allocator flags. Default is 0 (same as kDefaultPortAllocatorFlags).
     */
    public int flags = 0;


    /**
     * Creates an instance with default values.
     */
    public PortAllocatorConfig() {
    }

    /**
     * Convenience constructor.
     *
     * @param minPort Minimum port inclusive, or 0 to leave unspecified.
     * @param maxPort Maximum port inclusive, or 0 to leave unspecified.
     * @param flags   Bitwise OR of allocator flags.
     */
    public PortAllocatorConfig(int minPort, int maxPort, int flags) {
        this.minPort = minPort;
        this.maxPort = maxPort;
        this.flags = flags;
    }

    /**
     * Enable a flag.
     *
     * @param flag one of the allocator constants.
     *
     * @return this for chaining.
     */
    public PortAllocatorConfig setFlag(int flag) {
        this.flags |= flag;
        return this;
    }

    /**
     * Disable a flag.
     *
     * @param flag one of the allocator constants.
     *
     * @return this for chaining.
     */
    public PortAllocatorConfig clearFlag(int flag) {
        this.flags &= ~flag;
        return this;
    }

    /**
     * Check if a flag is enabled.
     *
     * @param flag one of the allocator constants.
     *
     * @return true if enabled.
     */
    public boolean isFlagEnabled(int flag) {
        return (this.flags & flag) != 0;
    }

    /**
     * Toggle the use of local UDP ports for ICE candidate gathering. Disabling this only
     * prevents binding local UDP sockets; it does not affect how connections to relay
     * servers are made.
     *
     * @param v true to disable local UDP ports, false to allow them.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableUdp(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_UDP) : clearFlag(PORTALLOCATOR_DISABLE_UDP);
    }

    /**
     * Indicates whether local UDP ports are disabled.
     *
     * @return true if local UDP ports are disabled.
     */
    public boolean isUdpDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_UDP);
    }

    /**
     * Disables or enables STUN for ICE candidate gathering.
     *
     * @param v true to disable STUN, false to enable it.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setDisableStun(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_STUN) : clearFlag(PORTALLOCATOR_DISABLE_STUN);
    }

    /**
     * Checks if STUN is disabled for ICE candidate gathering.
     *
     * @return true if STUN is disabled.
     */
    public boolean isStunDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_STUN);
    }

    /**
     * Disables or enables relay servers for ICE candidate gathering.
     *
     * @param v true to disable relay servers, false to enable them.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setDisableRelay(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_RELAY) : clearFlag(PORTALLOCATOR_DISABLE_RELAY);
    }

    /**
     * Checks if relay servers are disabled for ICE candidate gathering.
     *
     * @return true if relay servers are disabled.
     */
    public boolean isRelayDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_RELAY);
    }

    /**
     * Toggle the use of local TCP ports for candidate gathering. Disabling this only
     * prevents binding local TCP sockets; it does not affect how to connect to relay servers.
     *
     * @param v true to disable local TCP ports, false to allow them.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableTcp(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_TCP) : clearFlag(PORTALLOCATOR_DISABLE_TCP);
    }

    /**
     * Checks if TCP is disabled for ICE candidate gathering.
     *
     * @return true if TCP is disabled.
     */
    public boolean isTcpDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_TCP);
    }

    /**
     * Enables or disables IPv6 support for ICE candidate gathering.
     *
     * @param v true to enable IPv6, false to disable it.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setEnableIpv6(boolean v) {
        return v ? setFlag(PORTALLOCATOR_ENABLE_IPV6) : clearFlag(PORTALLOCATOR_ENABLE_IPV6);
    }

    /**
     * Checks if IPv6 is enabled for ICE candidate gathering.
     *
     * @return true if IPv6 is enabled.
     */
    public boolean isIpv6Enabled() {
        return isFlagEnabled(PORTALLOCATOR_ENABLE_IPV6);
    }

    /**
     * Enables or disables shared socket mode for ICE candidate gathering.
     * When enabled, a single UDP socket is used for all local candidates.
     *
     * @param v true to enable a shared socket, false to disable it.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setEnableSharedSocket(boolean v) {
        return v ? setFlag(PORTALLOCATOR_ENABLE_SHARED_SOCKET)
                : clearFlag(PORTALLOCATOR_ENABLE_SHARED_SOCKET);
    }

    /**
     * Checks if shared socket mode is enabled for ICE candidate gathering.
     *
     * @return true if a shared socket is enabled.
     */
    public boolean isSharedSocketEnabled() {
        return isFlagEnabled(PORTALLOCATOR_ENABLE_SHARED_SOCKET);
    }

    /**
     * Enables or disables the STUN_RETRANSMIT_ATTRIBUTE for STUN requests.
     *
     * @param v true to enable the STUN retransmit attribute, false to disable it.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setEnableStunRetransmitAttribute(boolean v) {
        return v ? setFlag(PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE)
                : clearFlag(PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE);
    }

    /**
     * Checks if the STUN retransmit attribute is enabled.
     *
     * @return true if the STUN retransmit attribute is enabled.
     */
    public boolean isStunRetransmitAttributeEnabled() {
        return isFlagEnabled(PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE);
    }

    /**
     * Skip network adapter enumeration. When disabled, only two candidates are gathered:
     * a STUN-derived address representing the public interface as seen by typical HTTP
     * traffic, and the HOST candidate for the default local interface.
     *
     * @param v true to skip adapter enumeration, false to enumerate all adapters.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableAdapterEnumeration(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION)
                : clearFlag(PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION);
    }

    /**
     * Indicates whether adapter enumeration is skipped.
     *
     * @return true if adapter enumeration is disabled.
     */
    public boolean isAdapterEnumerationDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION);
    }

    /**
     * Do not allocate the default local HOST candidate when used together with disabled
     * adapter enumeration. With this set alongside adapter enumeration disabled, only the
     * STUN-derived candidate is gathered.
     *
     * @param v true to suppress the default local candidate, false to allow it.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableDefaultLocalCandidate(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE)
                : clearFlag(PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE);
    }

    /**
     * Checks if the default local candidate is disabled.
     *
     * @return true if the default local candidate is disabled.
     */
    public boolean isDefaultLocalCandidateDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE);
    }

    /**
     * Prevent the use of UDP when connecting to a relay (TURN) server. This avoids leaking
     * the host IP address in environments where a proxy is used and does not handle UDP.
     *
     * @param v true to disallow UDP to relay servers, false to permit it.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableUdpRelay(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_UDP_RELAY) : clearFlag(PORTALLOCATOR_DISABLE_UDP_RELAY);
    }

    /**
     * Checks if UDP relay is disabled for ICE candidate gathering.
     *
     * @return true if UDP relay is disabled.
     */
    public boolean isUdpRelayDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_UDP_RELAY);
    }

    /**
     * Avoid gathering candidates on high-cost networks when multiple networks are available. For
     * example, prefer Wi-Fi over cellular; "unknown" networks are treated as lower cost than
     * cellular but higher than Wi‑Fi/Ethernet.
     *
     * @param v true to skip costly networks, false to consider them.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setDisableCostlyNetworks(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_COSTLY_NETWORKS)
                : clearFlag(PORTALLOCATOR_DISABLE_COSTLY_NETWORKS);
    }

    /**
     * Indicates whether high-cost networks are skipped during candidate gathering.
     *
     * @return true if costly networks are disabled.
     */
    public boolean isCostlyNetworksDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_COSTLY_NETWORKS);
    }

    /**
     * Control collection of IPv6 ICE candidates on Wi-Fi. When set, IPv6 candidates on Wi-Fi
     * are not collected.
     *
     * @param v true to suppress IPv6 on Wi-Fi, false to allow it.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setEnableIpv6OnWifi(boolean v) {
        return v ? setFlag(PORTALLOCATOR_ENABLE_IPV6_ON_WIFI)
                : clearFlag(PORTALLOCATOR_ENABLE_IPV6_ON_WIFI);
    }

    /**
     * Indicates whether IPv6 candidates on Wi-Fi are suppressed.
     *
     * @return true if IPv6 on Wi-Fi is disabled (suppressed).
     */
    public boolean isIpv6OnWifiEnabled() {
        return isFlagEnabled(PORTALLOCATOR_ENABLE_IPV6_ON_WIFI);
    }

    /**
     * Allow using ports bound to the "any" address in addition to interface-bound ports.
     * Without this flag, such ports are only used if adapter enumeration fails or is disabled;
     * enabling it may improve connectivity at the cost of allocating more candidates.
     *
     * @param v true to allow any-address ports, false to restrict to interface-bound ports.
     *
     * @return this instance for chaining.
     */
    public PortAllocatorConfig setEnableAnyAddressPorts(boolean v) {
        return v ? setFlag(PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS)
                : clearFlag(PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS);
    }

    /**
     * Indicates whether ports bound to the "any" address are used in addition to interface-bound
     * ports.
     *
     * @return true if any-address ports are allowed.
     */
    public boolean isAnyAddressPortsEnabled() {
        return isFlagEnabled(PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS);
    }

    /**
     * Exclude link-local network interfaces from consideration after adapter enumeration.
     *
     * @param v true to exclude link-local networks, false to include them.
     *
     * @return this instance for method chaining.
     */
    public PortAllocatorConfig setDisableLinkLocalNetworks(boolean v) {
        return v ? setFlag(PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS)
                : clearFlag(PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS);
    }

    /**
     * Indicates whether link-local interfaces are excluded.
     *
     * @return true if link-local networks are disabled.
     */
    public boolean isLinkLocalNetworksDisabled() {
        return isFlagEnabled(PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS);
    }
}
