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

import dev.onvoid.webrtc.demo.view.ChatMessageView;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.BooleanPropertyBase;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.css.StyleableProperty;
import javafx.geometry.HPos;
import javafx.scene.control.Control;

public abstract class FxChatMessageView extends Control implements
		ChatMessageView {

	private static final PseudoClass LEFT_PSEUDO_CLASS = PseudoClass.getPseudoClass("left");
	private static final PseudoClass RIGHT_PSEUDO_CLASS = PseudoClass.getPseudoClass("right");

	private static final String DEFAULT_STYLE_CLASS = "chat-message";

	private ObjectProperty<HPos> alignment;


	public FxChatMessageView() {
		super();

		initialize();
	}

	public boolean isLeft() {
		return left.get();
	}

	public BooleanProperty leftProperty() {
		return left;
	}

	public BooleanProperty left = new BooleanPropertyBase(false) {

		@Override
		protected void invalidated() {
			pseudoClassStateChanged(LEFT_PSEUDO_CLASS, get());
		}

		@Override
		public Object getBean() {
			return FxChatMessageView.this;
		}

		@Override
		public String getName() {
			return "left";
		}
	};

	public boolean isRight() {
		return right.get();
	}

	public BooleanProperty rightProperty() {
		return right;
	}

	public BooleanProperty right = new BooleanPropertyBase(false) {

		@Override
		protected void invalidated() {
			pseudoClassStateChanged(RIGHT_PSEUDO_CLASS, get());
		}

		@Override
		public Object getBean() {
			return FxChatMessageView.this;
		}

		@Override
		public String getName() {
			return "right";
		}
	};

	public final HPos getAlignment() {
		return alignment != null ? alignment.get() : HPos.LEFT;
	}

	public final void setAlignment(final HPos pos) {
		alignmentProperty().set(pos);
	}

	public final ObjectProperty<HPos> alignmentProperty() {
		if (alignment == null) {
			alignment = new SimpleObjectProperty<>(HPos.LEFT) {

				@Override
				protected void invalidated() {
					pseudoClassStateChanged(LEFT_PSEUDO_CLASS, get() == HPos.LEFT);
					pseudoClassStateChanged(RIGHT_PSEUDO_CLASS, get() == HPos.RIGHT);
				}

				@Override
				public Object getBean() {
					return FxChatMessageView.this;
				}

				@Override
				public String getName() {
					return "alignment";
				}
			};
		}
		return alignment;
	}

	@Override
	protected Boolean getInitialFocusTraversable() {
		return Boolean.FALSE;
	}

	private void initialize() {
		getStyleClass().setAll(DEFAULT_STYLE_CLASS);

		pseudoClassStateChanged(LEFT_PSEUDO_CLASS, true);

		((StyleableProperty<Boolean>) focusTraversableProperty()).applyStyle(null, false);
	}
}
