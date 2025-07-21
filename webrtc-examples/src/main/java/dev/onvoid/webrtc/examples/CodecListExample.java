/*
 * Copyright 2025 WebRTC Java Contributors
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

package dev.onvoid.webrtc.examples;

import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpCapabilities;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.media.MediaType;

/**
 * Example demonstrating how to list all supported codecs with the WebRTC peer-connection-factory.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Create a PeerConnectionFactory</li>
 *   <li>Get the supported codecs for both sending and receiving audio and video</li>
 *   <li>Display detailed information about each codec</li>
 * </ul>
 * 
 * @author Alex Andres
 */
public class CodecListExample {

    public static void main(String[] args) {
        PeerConnectionFactory factory = new PeerConnectionFactory();

        try {
            // Get receiver capabilities for audio and video.
            System.out.println("\nRECEIVER CAPABILITIES:");
            System.out.println("---------------------");

            System.out.println("\nAudio Receiver Codecs:");
            RTCRtpCapabilities audioReceiverCapabilities = factory.getRtpReceiverCapabilities(MediaType.AUDIO);
            printCodecInfo(audioReceiverCapabilities.getCodecs());

            System.out.println("\nVideo Receiver Codecs:");
            RTCRtpCapabilities videoReceiverCapabilities = factory.getRtpReceiverCapabilities(MediaType.VIDEO);
            printCodecInfo(videoReceiverCapabilities.getCodecs());

            // Get sender capabilities for audio and video.
            System.out.println("\nSENDER CAPABILITIES:");
            System.out.println("-------------------");

            System.out.println("\nAudio Sender Codecs:");
            RTCRtpCapabilities audioSenderCapabilities = factory.getRtpSenderCapabilities(MediaType.AUDIO);
            printCodecInfo(audioSenderCapabilities.getCodecs());

            System.out.println("\nVideo Sender Codecs:");
            RTCRtpCapabilities videoSenderCapabilities = factory.getRtpSenderCapabilities(MediaType.VIDEO);
            printCodecInfo(videoSenderCapabilities.getCodecs());
        }
        finally {
            // Dispose the factory when done.
            factory.dispose();
        }
    }

    /**
     * Prints detailed information about each codec in the provided list.
     * All information for a codec is printed in a single line for conciseness.
     *
     * @param codecs List of codec capabilities to display.
     */
    private static void printCodecInfo(List<RTCRtpCodecCapability> codecs) {
        if (codecs.isEmpty()) {
            System.out.println("  No codecs found.");
            return;
        }

        for (int i = 0; i < codecs.size(); i++) {
            RTCRtpCodecCapability codec = codecs.get(i);
            StringBuilder sb = new StringBuilder();

            sb.append("  Codec #").append(i + 1).append(": ");
            sb.append("MIME Type: ").append(codec.getMimeType()).append(" | ");
            sb.append("Media Type: ").append(codec.getMediaType()).append(" | ");
            sb.append("Name: ").append(codec.getName()).append(" | ");
            sb.append("Clock Rate: ").append(codec.getClockRate()).append(" Hz");

            if (codec.getMediaType() == MediaType.AUDIO) {
                int channels = codec.getChannels();
                sb.append(" | Channels: ").append(channels)
                        .append(channels == 1 ? " (mono)" : channels == 2 ? " (stereo)" : "");
            }

            // Add SDP format parameters if available.
            Map<String, String> sdpFmtp = codec.getSDPFmtp();
            if (sdpFmtp != null && !sdpFmtp.isEmpty()) {
                sb.append(" | SDP Params: {");
                boolean first = true;
                for (Map.Entry<String, String> entry : sdpFmtp.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
                sb.append("}");
            }

            System.out.println(sb);
        }
    }
}