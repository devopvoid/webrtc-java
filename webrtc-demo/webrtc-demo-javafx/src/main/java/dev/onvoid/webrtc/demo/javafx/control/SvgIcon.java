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

package dev.onvoid.webrtc.demo.javafx.control;

import static java.util.Objects.nonNull;

import java.util.List;

import javafx.beans.value.ObservableValue;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableBooleanProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleablePropertyFactory;
import javafx.css.StyleableStringProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;

public class SvgIcon extends Region {

	private static final StyleablePropertyFactory<SvgIcon> FACTORY = new StyleablePropertyFactory<>(Region.getClassCssMetaData());

	private final StyleableStringProperty content;

	private final StyleableObjectProperty<Paint> fill;

	private final StyleableObjectProperty<Paint> stroke;

	private final StyleableObjectProperty<Number> strokeWidth;

	private final StyleableObjectProperty<Number> size;

	private final StyleableBooleanProperty smooth;

	private final SVGPath svgPath;


	public SvgIcon() {
		super();

		content = (StyleableStringProperty) FACTORY.createStyleableStringProperty(this, "content", "-fx-content", s -> s.content, "");
		fill = (StyleableObjectProperty<Paint>) FACTORY.createStyleablePaintProperty(this, "fill", "-fx-fill", s -> s.fill, Color.BLACK);
		stroke = (StyleableObjectProperty<Paint>) FACTORY.createStyleablePaintProperty(this, "stroke", "-fx-stroke", s -> s.stroke, Color.BLACK);
		strokeWidth = (StyleableObjectProperty<Number>) FACTORY.createStyleableNumberProperty(this, "stroke-width", "-fx-stroke-width", s -> s.strokeWidth, 1);
		size = (StyleableObjectProperty<Number>) FACTORY.createStyleableNumberProperty(this, "size", "-fx-size", s -> s.size, Font.getDefault().getSize());
		smooth = (StyleableBooleanProperty) FACTORY.createStyleableBooleanProperty(this, "smooth", "-fx-smooth", s -> s.smooth, true);
		svgPath = new SVGPath();

		initialize();
	}

	public final String getContent() {
		return content.get();
	}

	public final void setContent(String value) {
		content.set(value);
	}

	public final ObservableValue<String> contentProperty() {
		return content;
	}

	public final void setFill(Paint value) {
		fill.set(value);
	}

	public final Paint getFill() {
		return fill.get();
	}

	public final ObservableValue<Paint> fillProperty() {
		return fill;
	}

	public final void setStroke(Paint value) {
		stroke.set(value);
	}

	public final Paint getStroke() {
		return stroke.get();
	}

	public final ObservableValue<Paint> strokeProperty() {
		return stroke;
	}

	public final void setStrokeWidth(double value) {
		strokeWidth.set(value);
	}

	public final double getStrokeWidth() {
		return strokeWidth.get().doubleValue();
	}

	public final ObservableValue<Number> strokeWidthProperty() {
		return strokeWidth;
	}

	public final double getSize() {
		return size.get().doubleValue();
	}

	public final void setSize(double value) {
		size.set(value);
	}

	public final ObservableValue<Number> sizeProperty() {
		return size;
	}

	public final void setSmooth(boolean value) {
		smooth.set(value);
	}

	public final boolean isSmooth() {
		return smooth.get();
	}

	public final ObservableValue<Boolean> smoothProperty() {
		return smooth;
	}

	public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
		return FACTORY.getCssMetaData();
	}

	@Override
	public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
		return getClassCssMetaData();
	}

	@Override
	protected double computeMinWidth(double height) {
		return computePrefWidth(height);
	}

	@Override
	protected double computeMinHeight(double width) {
		return computePrefHeight(width);
	}

	@Override
	protected double computePrefWidth(double height) {
		Insets insets = getInsets();

		return getSize() + insets.getLeft() + insets.getRight();
	}

	@Override
	protected double computePrefHeight(double width) {
		Insets insets = getInsets();

		return getSize() + insets.getTop() + insets.getBottom();
	}

	@Override
	protected void layoutChildren() {
		transformShape();
	}

	private void transformShape() {
		String content = getContent();

		if (nonNull(content) && !getContent().isEmpty()) {
			Bounds bounds = svgPath.getBoundsInLocal();

			double width = bounds.getWidth();
			double height = bounds.getHeight();
			double size = getSize();
			double scale = size / height;

			if (width * scale > size) {
				scale = size / width;
			}

			double sWidth = width * scale;
			double sHeight = height * scale;

			// Initial size pivot offset.
			double tx = -bounds.getMinX() - (width / 2);
			double ty = -bounds.getMinY() - (height / 2);

			// Center scaled size.
			tx += (sWidth / 2) - (sWidth - getLayoutBounds().getWidth()) / 2;
			ty += (sHeight / 2) - (sHeight - getLayoutBounds().getHeight()) / 2;

			svgPath.setTranslateX(tx);
			svgPath.setTranslateY(ty);
			svgPath.setScaleX(scale);
			svgPath.setScaleY(scale);
		}
	}

	private void initialize() {
		svgPath.contentProperty().bind(contentProperty());
		svgPath.fillProperty().bind(fillProperty());
		svgPath.strokeProperty().bind(strokeProperty());
		svgPath.strokeWidthProperty().bind(strokeWidthProperty());
		svgPath.smoothProperty().bind(smoothProperty());
		svgPath.contentProperty().addListener(observable -> requestLayout());

		getChildren().add(svgPath);

		sizeProperty().addListener(observable -> requestLayout());

		if (nonNull(svgPath.getContent()) && !svgPath.getContent().isEmpty()) {
			requestLayout();
		}

		parentProperty().addListener((observable, oldParent, newParent) -> {
			if (nonNull(newParent)) {
				if (Labeled.class.isAssignableFrom(newParent.getClass())) {
					Labeled labeled = (Labeled) newParent;
					labeled.fontProperty().addListener((observable1, oldFont, newFont) -> setSize(newFont.getSize()));

					setSize(labeled.getFont().getSize());
				}
			}
		});
	}
}
