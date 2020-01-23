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

package dev.onvoid.webrtc.demo.apprtc;

/**
 * Maintains connection parameters of an AppRTC room.
 */
public class AppRTCConnectionParameters {

	public final String roomUrl;

	public final String roomId;

	public final boolean loopback;

	public final String urlParameters;


	public AppRTCConnectionParameters(String roomUrl, String roomId) {
		this(roomUrl, roomId, false, null);
	}

	public AppRTCConnectionParameters(String roomUrl, String roomId,
									  boolean loopback) {
		this(roomUrl, roomId, loopback, null);
	}

	public AppRTCConnectionParameters(String roomUrl, String roomId, boolean loopback,
									  String urlParameters) {
		this.roomUrl = roomUrl;
		this.roomId = roomId;
		this.loopback = loopback;
		this.urlParameters = urlParameters;
	}

}
