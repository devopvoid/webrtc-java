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

package dev.onvoid.webrtc.demo.javafx.control.skin;

import dev.onvoid.webrtc.demo.javafx.view.FxChatTextMessageView;
import dev.onvoid.webrtc.demo.model.message.ChatMessage;
import dev.onvoid.webrtc.demo.model.message.ChatTextMessage;

import java.time.format.DateTimeFormatter;

import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class ChatTextMessageViewSkin extends SkinBase<FxChatTextMessageView> {

	private DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm");

	private Label timeLabel;

	private Label messageLabel;


	/**
	 * Creates a new ChatTextMessageViewSkin.
	 *
	 * @param control The control for which this Skin should attach to.
	 */
	public ChatTextMessageViewSkin(FxChatTextMessageView control) {
		super(control);

		initLayout(control);
	}

	@Override
	public void dispose() {
		unregisterChangeListeners(getSkinnable().alignmentProperty());
		unregisterChangeListeners(getSkinnable().textMessageProperty());

		super.dispose();
	}

	private void initLayout(FxChatTextMessageView control) {
		Region parent = (Region) control.getParent();

		timeLabel = new Label();
		timeLabel.getStyleClass().add("time");

		messageLabel = new Label();
		messageLabel.getStyleClass().add("message");
		messageLabel.setWrapText(true);
		messageLabel.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
		messageLabel.setMinHeight(Region.USE_PREF_SIZE);
		messageLabel.maxWidthProperty().bind(parent.widthProperty().multiply(0.7));

		VBox pane = new VBox(3, timeLabel, messageLabel);
		pane.setFillWidth(false);

		registerChangeListener(control.alignmentProperty(), o -> {
			HPos alignment = control.getAlignment();

			setAlignment(alignment, pane);
		});
		registerChangeListener(control.textMessageProperty(), o -> {
			updateMessage(control);
		});

		setAlignment(control.getAlignment(), pane);

		updateMessage(control);

		getChildren().add(pane);
	}

	private void updateMessage(FxChatTextMessageView control) {
		ChatTextMessage textMessage = control.getTextMessage();

		timeLabel.setText(textMessage.getTime().format(dtf));
		messageLabel.setText(textMessage.getMessage());

		if (textMessage.getOrigin() == ChatMessage.Origin.LOCAL) {
			control.setAlignment(HPos.RIGHT);
		}
	}

	private void setAlignment(HPos alignment, VBox pane) {
		if (alignment == HPos.LEFT) {
			pane.setAlignment(Pos.CENTER_LEFT);
		}
		else if (alignment == HPos.RIGHT) {
			pane.setAlignment(Pos.CENTER_RIGHT);
		}
	}
}
