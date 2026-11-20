/*
 * Copyright 2026 Alex Andres
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

module webrtc.java.livekit {

	requires transitive webrtc.java;
	requires java.net.http;
	requires com.google.protobuf;

	exports dev.onvoid.webrtc.livekit;

	// The generated protocol classes stay internal. Protobuf reaches them by
	// reflection for its descriptor-based operations, such as toString().
	opens dev.onvoid.webrtc.livekit.internal.proto to com.google.protobuf;
	opens dev.onvoid.webrtc.livekit.internal.proto.logger to com.google.protobuf;

}
