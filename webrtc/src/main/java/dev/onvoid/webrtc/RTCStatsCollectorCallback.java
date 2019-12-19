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

/**
 * An RTCStatsCollectorCallback reports back when an {@link RTCStatsReport} is
 * ready.
 *
 * @author Alex Andres
 */
public interface RTCStatsCollectorCallback {

	/**
	 * All necessary statistics have been gathered and an stats report has been
	 * generated.
	 *
	 * @param report The stats report with updated statistics.
	 */
	void onStatsDelivered(RTCStatsReport report);

}
