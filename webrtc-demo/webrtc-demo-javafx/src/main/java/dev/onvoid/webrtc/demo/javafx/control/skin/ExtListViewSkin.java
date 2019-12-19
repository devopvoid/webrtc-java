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

import java.util.Set;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.ListViewSkin;

public class ExtListViewSkin<T> extends ListViewSkin<T> {

	private BooleanProperty hBarVisible;

	private BooleanProperty vBarVisible;

	private ScrollBar hBar;

	private ScrollBar vBar;


	/**
	 * Creates a new ListViewSkin instance, installing the necessary child
	 * nodes into the Control {@link Control#getChildren() children} list, as
	 * well as the necessary input mappings for handling key, mouse, etc events.
	 *
	 * @param control The control that this skin should be installed onto.
	 */
	public ExtListViewSkin(final ListView<T> control) {
		super(control);

		initialize(control);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void layoutChildren(final double x, final double y, final double w, final double h) {
		super.layoutChildren(x, y, w, h);

		Insets insets = getSkinnable().getInsets();

		final double prefWidth = vBar.prefWidth(-1);
		final double prefHeight = hBar.prefHeight(-1);

		final double hBarX = insets.getLeft();
		final double hBarY = h - prefHeight - insets.getBottom();
		final double hBarW = w - insets.getLeft() - insets.getRight() - prefWidth;

		final double vBarX = w - prefWidth - insets.getRight();
		final double vBarY = insets.getTop();
		final double vBarH = h - insets.getTop() - insets.getBottom();

		hBar.resizeRelocate(hBarX, hBarY, hBarW, prefHeight);
		vBar.resizeRelocate(vBarX, vBarY, prefWidth, vBarH);
	}

	private void initialize(final ListView<T> control) {
		control.getStyleClass().add("ext-list-view");

		vBarVisible = new SimpleBooleanProperty();
		hBarVisible = new SimpleBooleanProperty();

		vBar = new ScrollBar();
		vBar.setManaged(false);
		vBar.setOrientation(Orientation.VERTICAL);
		vBar.getStyleClass().add("ext-scroll-bar");
		vBar.visibleProperty().bind(vBar.visibleAmountProperty().isNotEqualTo(0).and(vBarVisible));

		hBar = new ScrollBar();
		hBar.setManaged(false);
		hBar.setOrientation(Orientation.HORIZONTAL);
		hBar.getStyleClass().add("ext-scroll-bar");
		hBar.visibleProperty().bind(hBar.visibleAmountProperty().isNotEqualTo(0).and(hBarVisible));

		control.setOnMouseEntered(event -> {
			hBarVisible.set(true);
			vBarVisible.set(true);
		});
		control.setOnMouseExited(event -> {
			hBarVisible.set(false);
			vBarVisible.set(false);
		});

		getChildren().addAll(vBar, hBar);

		bindScrollBars();
	}

	private void bindScrollBars() {
		final Set<Node> nodes = getSkinnable().lookupAll("VirtualScrollBar");

		for (Node node : nodes) {
			if (node instanceof ScrollBar) {
				ScrollBar bar = (ScrollBar) node;

				if (bar.getOrientation().equals(Orientation.HORIZONTAL)) {
					bindScrollBars(hBar, bar);
				}
				else if (bar.getOrientation().equals(Orientation.VERTICAL)) {
					bindScrollBars(vBar, bar);
				}
			}
		}
	}

	private static void bindScrollBars(ScrollBar scrollBarA, ScrollBar scrollBarB) {
		scrollBarA.valueProperty().bindBidirectional(scrollBarB.valueProperty());
		scrollBarA.minProperty().bindBidirectional(scrollBarB.minProperty());
		scrollBarA.maxProperty().bindBidirectional(scrollBarB.maxProperty());
		scrollBarA.visibleAmountProperty().bindBidirectional(scrollBarB.visibleAmountProperty());
		scrollBarA.unitIncrementProperty().bindBidirectional(scrollBarB.unitIncrementProperty());
		scrollBarA.blockIncrementProperty().bindBidirectional(scrollBarB.blockIncrementProperty());
	}
}
