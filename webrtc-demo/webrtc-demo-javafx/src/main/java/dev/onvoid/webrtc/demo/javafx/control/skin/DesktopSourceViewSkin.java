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

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.demo.javafx.control.DesktopSourceView;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.media.video.desktop.DesktopFrame;

import java.nio.ByteBuffer;

import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.ButtonSkin;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.layout.StackPane;

public class DesktopSourceViewSkin extends ButtonSkin {

	private ImageView imageView;

	private PixelBuffer<ByteBuffer> pixelBuffer;

	private ByteBuffer byteBuffer;


	/**
	 * Creates a new DesktopSourceViewSkin.
	 *
	 * @param control The control for which this Skin should attach to.
	 */
	public DesktopSourceViewSkin(DesktopSourceView control) {
		super(control);

		initLayout(control);
	}

	@Override
	public void dispose() {
		getSkinnable().setGraphic(null);

		super.dispose();
	}

	public void setDesktopFrame(DesktopFrame frame) {
		ByteBuffer buffer = frame.buffer;

		int width = frame.frameSize.width;
		int height = frame.frameSize.height;

		int bufferSize = buffer.capacity();
		int rectBufferSize = (width + 8) * height * 4;

		width += (bufferSize != rectBufferSize) ? 0 : 8;

		if (isNull(pixelBuffer) || pixelBuffer.getWidth() != width || pixelBuffer.getHeight() != height) {
			byteBuffer = ByteBuffer.allocate(bufferSize);
			pixelBuffer = new PixelBuffer<>(width, height, byteBuffer, WritablePixelFormat.getByteBgraPreInstance());

			imageView.setImage(new WritableImage(pixelBuffer));
		}

		try {
			byteBuffer.put(buffer);
			byteBuffer.clear();
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		FxUtils.invoke(() -> pixelBuffer.updateBuffer(pixBuffer -> null));
	}

	private void initLayout(DesktopSourceView control) {
		StackPane imagePane = new StackPane();
		imagePane.getStyleClass().add("image-pane");

		imageView = new ImageView();
		imageView.setSmooth(false);
		imageView.setCache(false);
		imageView.setPreserveRatio(true);
		imageView.fitWidthProperty().bind(imagePane.widthProperty());
		imageView.fitHeightProperty().bind(imagePane.heightProperty());

		Tooltip textTooltip = new Tooltip();
		textTooltip.textProperty().bind(control.textProperty());

		imagePane.getChildren().add(imageView);

		control.setGraphic(imagePane);
		control.setTooltip(textTooltip);
	}
}
