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

package dev.onvoid.webrtc.demo.view;

import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.media.video.VideoFrame;

public interface CallView extends View {

	void showDesktopSourcesView(DesktopSourcesView view);

	void setRemoteFrame(Contact contact, VideoFrame frame);

	void setLocalFrame(VideoFrame frame);

	void setStatsReport(RTCStatsReport report);

	void showRemoteVideo(Contact contact, boolean show);

	void showLocalVideo(boolean show);

	void setOnFullscreenActive(ConsumerAction<Boolean> action);

	void setOnDesktopActive(ConsumerAction<Boolean> action);

	void setOnMicrophoneActive(ConsumerAction<Boolean> action);

	void setOnCameraActive(ConsumerAction<Boolean> action);

	void setOnStatsActive(ConsumerAction<Boolean> action);

	void setOnDisconnect(Action action);

}
