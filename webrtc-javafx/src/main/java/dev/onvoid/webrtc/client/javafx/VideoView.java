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

package dev.onvoid.webrtc.client.javafx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

/**
 * A JavaFX node that shows video frames, such as those of a remote track:
 * <pre>{@code
 * VideoView view = new VideoView();
 * track.asVideo().onFrame(view);
 * }</pre>
 * Frames are converted on the thread that delivers them, so the JavaFX
 * Application Thread only paints, at most once per pulse however many frames
 * arrive. The picture keeps its aspect ratio and its rotation, on a black
 * background, and can be mirrored for a local camera preview.
 * Conversion buffers grow on demand and are reused across resolution changes
 * and clear(). Their retained capacity follows the largest frames received;
 * buffer memory is released when the view becomes unreachable.
 */
public class VideoView extends Region implements Consumer<VideoFrame> {

	private final Canvas canvas = new Canvas();

	private final BooleanProperty mirrored = new SimpleBooleanProperty(this, "mirrored");

	/** Serializes conversions, for frames of more than one subscription. */
	private final Object convertLock = new Object();

	/** Guards the hand-over between the converting thread and the JavaFX thread. */
	private final Object lock = new Object();

	// Three reusable buffers: the converting thread fills a free slot, hands it over as
	// ready, and the JavaFX thread paints from shown. None is written while
	// another thread reads it.

	private final Pixels[] buffers = new Pixels[3];

	private Pixels ready;

	private boolean scheduled;

	/** Counts the calls of clear(), to tell the frames that came before one. */
	private long generation;

	// Set under lock; its pixels are read only on the JavaFX Application Thread.
	private Pixels shown;

	// Confined to the JavaFX Application Thread.

	private WritableImage image;

	private int rotation;


	/**
	 * Creates a view with a preferred size of 640 by 360 pixels.
	 */
	public VideoView() {
		getChildren().add(canvas);
		setPrefSize(640, 360);
		setMinSize(0, 0);

		mirrored.addListener((property, before, now) -> draw());
	}

	/**
	 * Shows a frame. Called with the frames of a track, from any thread; the
	 * frame is not released, which its subscription does.
	 *
	 * @param frame the frame to show.
	 * @throws IllegalArgumentException if dimensions are nonpositive or the
	 * frame cannot be represented by a Java pixel buffer.
	 */
	@Override
	public void accept(VideoFrame frame) {
		VideoFrameBuffer buffer = frame.buffer;
		int width = buffer.getWidth();
		int height = buffer.getHeight();
		int byteCount = frameBytes(width, height);
		long started;

		// Before the wait for convertLock, as the frame came before any clear()
		// that happens during it.
		synchronized (lock) {
			started = generation;
		}

		synchronized (convertLock) {
			// Only this thread chooses/writes a slot; presentation may read the
			// other two. Allocate outside the hand-over lock.
			int slot = 0;
			synchronized (lock) {
				while (buffers[slot] != null && (buffers[slot] == ready || buffers[slot] == shown)) {
					slot++;
				}
			}
			Pixels target = buffers[slot];
			if (target == null || target.data.capacity() < byteCount) {
				long previous = target == null ? 0 : target.data.capacity();
				int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(byteCount, previous * 2L));
				target = new Pixels(capacity);
				buffers[slot] = target;
			}
			target.width = width;
			target.height = height;

			try {
				// libyuv's ARGB is B, G, R, A in memory, which is JavaFX's BGRA.
				target.data.clear();
				target.data.limit(byteCount);
				VideoBufferConverter.convertFromI420(buffer, target.data, FourCC.ARGB);
				target.data.rewind();
			}
			catch (Exception e) {
				throw new IllegalStateException("The frame could not be converted", e);
			}

			target.rotation = frame.rotation;

			synchronized (lock) {
				if (started != generation) {
					return;
				}

				ready = target;

				if (scheduled) {
					return;
				}

				scheduled = true;
			}
		}

		Platform.runLater(this::present);
	}

	/** ByteBuffer capacities and image array lengths must fit in an int. */
	static int frameBytes(int width, int height) {
		long pixels = (long) width * height;
		if (width <= 0 || height <= 0 || pixels > Integer.MAX_VALUE / 4) {
			throw new IllegalArgumentException("Invalid or unrepresentable frame dimensions: " + width + "x" + height);
		}
		return (int) (pixels * 4L);
	}

	/**
	 * Removes the picture, leaving the background, and drops the frames still
	 * being converted. Called on the JavaFX Application Thread.
	 */
	public void clear() {
		synchronized (lock) {
			generation++;
			ready = null;
			shown = null;
		}

		image = null;
		draw();
	}

	/**
	 * @return the property that flips the picture horizontally, as a local
	 * camera preview usually is.
	 */
	public BooleanProperty mirroredProperty() {
		return mirrored;
	}

	/**
	 * @param mirrored true to flip the picture horizontally.
	 */
	public void setMirrored(boolean mirrored) {
		this.mirrored.set(mirrored);
	}

	/**
	 * @return true when the picture is flipped horizontally.
	 */
	public boolean isMirrored() {
		return mirrored.get();
	}

	/**
	 * Returns a copy of the picture shown, as the frame was, without rotation
	 * or mirroring. Called on the JavaFX Application Thread.
	 *
	 * @return the copy, or null when there is no picture.
	 */
	public WritableImage snapshot() {
		if (image == null) {
			return null;
		}

		int width = (int) image.getWidth();
		int height = (int) image.getHeight();
		WritableImage copy = new WritableImage(width, height);
		copy.getPixelWriter().setPixels(0, 0, width, height, image.getPixelReader(), 0, 0);

		return copy;
	}

	@Override
	protected void layoutChildren() {
		canvas.setWidth(getWidth());
		canvas.setHeight(getHeight());
		draw();
	}

	/** Takes the newest frame over, on the JavaFX Application Thread. */
	private void present() {
		Pixels next;

		synchronized (lock) {
			scheduled = false;
			next = ready;

			if (next == null) {
				return;
			}

			ready = shown;
			shown = next;
		}

		if (image == null || image.getWidth() != next.width || image.getHeight() != next.height) {
			image = new WritableImage(next.width, next.height);
		}

		image.getPixelWriter().setPixels(0, 0, next.width, next.height,
				PixelFormat.getByteBgraPreInstance(), next.data, next.width * 4);
		rotation = next.rotation;

		draw();
	}

	private void draw() {
		GraphicsContext g = canvas.getGraphicsContext2D();
		double width = canvas.getWidth();
		double height = canvas.getHeight();

		g.setFill(Color.BLACK);
		g.fillRect(0, 0, width, height);

		if (image == null || width <= 0 || height <= 0) {
			return;
		}

		boolean sideways = rotation == 90 || rotation == 270;
		double imageWidth = sideways ? image.getHeight() : image.getWidth();
		double imageHeight = sideways ? image.getWidth() : image.getHeight();
		double scale = Math.min(width / imageWidth, height / imageHeight);

		// Around the center: scale, rotate, mirror, then draw the image
		// centered on the origin, as the Swing view does.
		g.save();
		g.translate(width / 2, height / 2);
		g.scale(isMirrored() ? -scale : scale, scale);
		g.rotate(rotation);
		g.drawImage(image, -image.getWidth() / 2, -image.getHeight() / 2);
		g.restore();
	}



	private static final class Pixels {

		int width;

		int height;

		final ByteBuffer data;

		int rotation;


		Pixels(int capacity) {
			this.data = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
		}

	}
}
