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

import dev.onvoid.webrtc.demo.javafx.control.skin.ChatTextMessageViewSkin;
import dev.onvoid.webrtc.demo.model.message.ChatTextMessage;
import dev.onvoid.webrtc.demo.view.ChatTextMessageView;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Skin;

public class FxChatTextMessageView extends FxChatMessageView implements ChatTextMessageView {

	private static final String DEFAULT_STYLE_CLASS = "chat-text-message";

	private ObjectProperty<ChatTextMessage> textMessage;


	public FxChatTextMessageView() {
		super();

		initialize();
	}

	@Override
	public void setTextMessage(ChatTextMessage message) {
		textMessageProperty().set(message);
	}

	public ChatTextMessage getTextMessage() {
		return textMessage.get();
	}

	public final ObjectProperty<ChatTextMessage> textMessageProperty() {
		if (textMessage == null) {
			textMessage = new SimpleObjectProperty<>();
		}
		return textMessage;
	}

	@Override
	public String getUserAgentStylesheet() {
		return getClass().getResource("/resources/css/chat-text-message.css").toExternalForm();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new ChatTextMessageViewSkin(this);
	}

	private void initialize() {
		getStyleClass().add(DEFAULT_STYLE_CLASS);
	}
}
