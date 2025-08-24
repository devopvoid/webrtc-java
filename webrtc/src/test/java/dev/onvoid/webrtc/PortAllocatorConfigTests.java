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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PortAllocatorConfigTests {

    @Test
    void defaults() {
        PortAllocatorConfig cfg = new PortAllocatorConfig();
        assertEquals(0, cfg.minPort);
        assertEquals(0, cfg.maxPort);
        assertEquals(0, cfg.flags);

        // All feature checks should be false by default
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_UDP));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_STUN));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_RELAY));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_SHARED_SOCKET));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_UDP_RELAY));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_COSTLY_NETWORKS));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6_ON_WIFI));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS));
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS));

        // Convenience getters
        assertFalse(cfg.isUdpDisabled());
        assertFalse(cfg.isStunDisabled());
        assertFalse(cfg.isRelayDisabled());
        assertFalse(cfg.isTcpDisabled());
        assertFalse(cfg.isIpv6Enabled());
        assertFalse(cfg.isSharedSocketEnabled());
        assertFalse(cfg.isStunRetransmitAttributeEnabled());
        assertFalse(cfg.isAdapterEnumerationDisabled());
        assertFalse(cfg.isDefaultLocalCandidateDisabled());
        assertFalse(cfg.isUdpRelayDisabled());
        assertFalse(cfg.isCostlyNetworksDisabled());
        assertFalse(cfg.isIpv6OnWifiEnabled());
        assertFalse(cfg.isAnyAddressPortsEnabled());
        assertFalse(cfg.isLinkLocalNetworksDisabled());
    }

    @Test
    void constructorValuesAndChaining() {
        int flags = PortAllocatorConfig.PORTALLOCATOR_DISABLE_UDP
                | PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP
                | PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6;
        PortAllocatorConfig cfg = new PortAllocatorConfig(10000, 10100, flags);

        assertEquals(10000, cfg.minPort);
        assertEquals(10100, cfg.maxPort);
        assertEquals(flags, cfg.flags);

        assertTrue(cfg.isUdpDisabled());
        assertTrue(cfg.isTcpDisabled());
        assertTrue(cfg.isIpv6Enabled());

        // Clear and set using chaining helpers.
        cfg.clearFlag(PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP)
           .setDisableStun(true)
           .setDisableRelay(true)
           .setEnableSharedSocket(true)
           .setEnableStunRetransmitAttribute(true)
           .setDisableAdapterEnumeration(true)
           .setDisableDefaultLocalCandidate(true)
           .setDisableUdpRelay(true)
           .setDisableCostlyNetworks(true)
           .setEnableIpv6OnWifi(true)
           .setEnableAnyAddressPorts(true)
           .setDisableLinkLocalNetworks(true);

        assertFalse(cfg.isTcpDisabled());
        assertTrue(cfg.isStunDisabled());
        assertTrue(cfg.isRelayDisabled());
        assertTrue(cfg.isSharedSocketEnabled());
        assertTrue(cfg.isStunRetransmitAttributeEnabled());
        assertTrue(cfg.isAdapterEnumerationDisabled());
        assertTrue(cfg.isDefaultLocalCandidateDisabled());
        assertTrue(cfg.isUdpRelayDisabled());
        assertTrue(cfg.isCostlyNetworksDisabled());
        assertTrue(cfg.isIpv6OnWifiEnabled());
        assertTrue(cfg.isAnyAddressPortsEnabled());
        assertTrue(cfg.isLinkLocalNetworksDisabled());

        // Toggle back some flags using the boolean setters.
        cfg.setDisableStun(false)
           .setDisableRelay(false)
           .setEnableSharedSocket(false)
           .setEnableStunRetransmitAttribute(false)
           .setDisableAdapterEnumeration(false)
           .setDisableDefaultLocalCandidate(false)
           .setDisableUdpRelay(false)
           .setDisableCostlyNetworks(false)
           .setEnableIpv6OnWifi(false)
           .setEnableAnyAddressPorts(false)
           .setDisableLinkLocalNetworks(false)
           .setDisableUdp(false);

        assertFalse(cfg.isUdpDisabled());
        assertFalse(cfg.isStunDisabled());
        assertFalse(cfg.isRelayDisabled());
        assertFalse(cfg.isSharedSocketEnabled());
        assertFalse(cfg.isStunRetransmitAttributeEnabled());
        assertFalse(cfg.isAdapterEnumerationDisabled());
        assertFalse(cfg.isDefaultLocalCandidateDisabled());
        assertFalse(cfg.isUdpRelayDisabled());
        assertFalse(cfg.isCostlyNetworksDisabled());
        assertFalse(cfg.isIpv6OnWifiEnabled());
        assertFalse(cfg.isAnyAddressPortsEnabled());
        assertFalse(cfg.isLinkLocalNetworksDisabled());
    }

    @Test
    void setAndClearFlagsDirectly() {
        PortAllocatorConfig cfg = new PortAllocatorConfig();

        cfg.setFlag(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6);
        assertTrue(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6));
        cfg.clearFlag(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6);
        assertFalse(cfg.isFlagEnabled(PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6));

        // Multiple flags combined.
        int combo = PortAllocatorConfig.PORTALLOCATOR_DISABLE_UDP | PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP
                | PortAllocatorConfig.PORTALLOCATOR_ENABLE_SHARED_SOCKET;
        cfg.setFlag(combo);
        assertTrue(cfg.isUdpDisabled());
        assertTrue(cfg.isTcpDisabled());
        assertTrue(cfg.isSharedSocketEnabled());

        // Clearing just one of them.
        cfg.clearFlag(PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP);
        assertTrue(cfg.isUdpDisabled());
        assertFalse(cfg.isTcpDisabled());
        assertTrue(cfg.isSharedSocketEnabled());
    }
}
