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

package dev.onvoid.webrtc.media.audio;

/**
 * Enumeration of available audio layer implementations across different platforms.
 * This enum defines the various audio backends that can be used for audio input/output
 * operations in WebRTC across different operating systems.
 *
 * @author Rafael Berne
 */
public enum AudioLayer {

    /** Default audio layer based on the current platform. */
    kPlatformDefaultAudio,

    /** Windows Core Audio implementation. */
    kWindowsCoreAudio,

    /** Windows Core Audio 2 implementation (newer version). */
    kWindowsCoreAudio2,

    /** Linux Advanced Linux Sound Architecture (ALSA) implementation. */
    kLinuxAlsaAudio,

    /** Linux PulseAudio implementation. */
    kLinuxPulseAudio,

    /** Android Java audio implementation using AudioRecord/AudioTrack. */
    kAndroidJavaAudio,

    /** Android OpenSL ES audio implementation. */
    kAndroidOpenSLESAudio,

    /** Android hybrid implementation with Java input and OpenSL ES output. */
    kAndroidJavaInputAndOpenSLESOutputAudio,

    /** Android AAudio implementation (introduced in Android 8.0). */
    kAndroidAAudioAudio,

    /** Android hybrid implementation with Java input and AAudio output. */
    kAndroidJavaInputAndAAudioOutputAudio,

    /** Dummy audio implementation for testing or non-audio scenarios. */
    kDummyAudio

}
