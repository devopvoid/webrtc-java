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

package dev.onvoid.webrtc.media;

/**
 * A device change listener is notified whenever a media device such as a
 * camera, microphone, or speaker is connected to or removed from the system.
 *
 * @author Alex Andres
 */
public interface DeviceChangeListener {

	/**
	 * A media device has been connected to the system.
	 *
	 * @param device The connected device.
	 */
	void deviceConnected(Device device);

	/**
	 * A media device has been disconnected from the system.
	 *
	 * @param device The disconnected device.
	 */
	void deviceDisconnected(Device device);

}
