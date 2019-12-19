/*
 * Copyright 2019 Alex Andres
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

import dev.onvoid.webrtc.internal.NativeObject;

/**
 * Allows an application access to information about the ICE transport over
 * which packets are sent and received. In particular, ICE manages peer-to-peer
 * connections which involve state which the application may want to access.
 * RTCIceTransport objects are constructed as a result of calls to {@link
 * RTCPeerConnection#setLocalDescription setLocalDescription} and {@link
 * RTCPeerConnection#setRemoteDescription setRemoteDescription}. Each
 * RTCIceTransport object represents the ICE transport layer for the RTP or RTCP
 * component of a specific {@link RTCRtpTransceiver}, or a group of
 * RTCRtpTransceivers if such a group has been negotiated via [BUNDLE].
 *
 * @author Alex Andres
 */
public class RTCIceTransport extends NativeObject {



}
