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
 * Represents a DTMF (Dual-Tone Multi-Frequency) sender for WebRTC.
 * This class allows sending DTMF tones over a WebRTC connection.
 * <p>
 * The RTCDtmfSender interface enables the generation of DTMF tones on an audio track.
 * This is typically used in Voice over IP (VoIP) applications to send DTMF tones to
 * a remote telephony system or interactive voice response (IVR) system.
 * <p>
 * DTMF tones are the audible tones generated when pressing keys on a telephone keypad.
 * The supported DTMF tones are: 0-9, A-D, *, and #. In addition, the special characters
 * ',' (comma) can be used to insert a 2-second delay between tones.
 *
 * @author Alex Andres
 *
 * @see <a href="https://w3c.github.io/webrtc-pc/#rtcdtmfsender">W3C WebRTC 1.0: RTCDtmfSender</a>
 */
public class RTCDtmfSender extends NativeObject {

	RTCDtmfSender() {
		// Default constructor for native object instantiation.
	}

	/**
	 * Checks whether this RTCDtmfSender is capable of sending DTMF tones.
	 * <p>
	 * This method returns true if and only if the associated RTCRtpSender's
	 * track is non-null and is of kind "audio".
	 * To be able to send DTMF, the associated RTCDtmfSender must be able to
	 * send packets, and a "telephone-event" codec must be negotiated.
	 * <p>
	 * When this method returns false, calling {@link #insertDtmf} will have no effect.
	 *
	 * @return true if DTMF tones can be inserted.
	 */
	public native boolean canInsertDtmf();

	/**
	 * Inserts DTMF tones to be transmitted on the audio track.
	 * <p>
	 * The tones parameter is treated as a series of characters. Characters
	 * 0 through 9, A through D, #, and * generate the associated DTMF tones. 
	 * The characters a through d are equivalent to A through D. The character ',' (comma) 
	 * indicates a delay of 2 seconds before processing the next character in the tones parameter.
	 * <p>
	 * Unrecognized characters in the tones parameter are ignored.
	 * <p>
	 * If {@link #canInsertDtmf} is false, this method will have no effect and return false.
	 * <p>
	 * The duration parameter indicates the duration in milliseconds to use for each
	 * character passed in the tones parameter. The duration cannot be more than 6000 
	 * milliseconds or less than 70 milliseconds. The default duration is 100 milliseconds.
	 * <p>
	 * The interToneGap parameter indicates the gap between tones in milliseconds.
	 * The interToneGap cannot be less than 50 milliseconds. The default value is 50 milliseconds.
	 *
	 * @param tones        String containing the DTMF tones to be transmitted.
	 *                     Valid characters are 0-9, A-D, #, *, and , (comma).
	 * @param duration     Duration in milliseconds for each tone. Must be between 40 and 6000 ms.
	 * @param interToneGap Gap between tones in milliseconds. Must be at least 30 ms.
	 *
	 * @return true if the tones were successfully queued for transmission.
	 */
	public native boolean insertDtmf(String tones, int duration, int interToneGap);

	/**
	 * Gets the tones remaining in the transmission queue.
	 * <p>
	 * This method returns the list of DTMF tones that have been queued for transmission
	 * but not yet sent. This includes any tones currently being transmitted.
	 * <p>
	 * If the value is an empty string, it indicates that there are no tones
	 * currently queued for transmission.
	 *
	 * @return String containing the remaining DTMF tones in the queue, or an empty string if none.
	 */
	public native String tones();

	/**
	 * Gets the current tone duration setting.
	 * <p>
	 * This method returns the current value of the duration parameter that will be used for
	 * future calls to {@link #insertDtmf}. The duration is the length of time, in milliseconds, 
	 * that each DTMF tone is played.
	 * <p>
	 * The default value is 100 milliseconds. Valid values are between 70 and 6000 milliseconds.
	 *
	 * @return The duration in milliseconds for each tone.
	 */
	public native int duration();

	/**
	 * Gets the current gap between tones setting.
	 * <p>
	 * This method returns the current value of the interToneGap parameter that will be used for
	 * future calls to {@link #insertDtmf}. The interToneGap is the length of time, in milliseconds,
	 * between the end of one DTMF tone and the start of the next.
	 * <p>
	 * The default value is 50 milliseconds. Valid values must be at least 50 milliseconds but should
	 * be as short as possible.
	 *
	 * @return The gap between tones in milliseconds.
	 */
	public native int interToneGap();

	/**
	 * Registers an observer to receive notifications about DTMF-related events.
	 * <p>
	 * Only one observer can be registered at a time. Registering a new observer
	 * when one is already registered will replace the existing observer.
	 *
	 * @param observer The DTMF sender observer to register.
	 */
	public native void registerObserver(RTCDtmfSenderObserver observer);

	/**
	 * Unregisters the previously registered DTMF sender observer.
	 * <p>
	 * After calling this method, no observer will receive notifications about
	 * DTMF-related events until a new observer is registered.
	 * <p>
	 * If no observer is currently registered, this method has no effect.
	 */
	public native void unregisterObserver();

}
