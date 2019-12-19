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

package dev.onvoid.webrtc.demo.javafx.view;

import dev.onvoid.webrtc.demo.javafx.control.DesktopSourceView;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSourceType;

public class FxDesktopSourceView extends DesktopSourceView implements dev.onvoid.webrtc.demo.view.DesktopSourceView {

	private DesktopSource source;

	private DesktopSourceType type;


	@Override
	public DesktopSource getDesktopSource() {
		return source;
	}

	@Override
	public void setDesktopSource(DesktopSource source) {
		this.source = source;

		FxUtils.invoke(() -> setText(source.title));
	}

	@Override
	public DesktopSourceType getDesktopSourceType() {
		return type;
	}

	@Override
	public void setDesktopSourceType(DesktopSourceType type) {
		this.type = type;
	}

	@Override
	public void setOnClick(Action action) {
		FxUtils.bindAction(this, action);
	}
}
