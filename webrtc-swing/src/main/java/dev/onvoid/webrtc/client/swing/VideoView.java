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

package dev.onvoid.webrtc.client.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import javax.swing.JComponent;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

/**
 * A Swing component that shows video frames, such as those of a remote track:
 * <pre>{@code
 * VideoView view = new VideoView();
 * track.asVideo().onFrame(view);
 * }</pre>
 * Frames are converted on the thread that delivers them, so the event dispatch
 * thread only paints. The picture keeps its aspect ratio and its rotation, with
 * the component's background around it.
 * Conversion buffers grow on demand and are reused across resolution changes
 * and clear(). Their retained capacity follows the largest frames received;
 * buffer memory is released when the view becomes unreachable.
 */
public class VideoView extends JComponent implements Consumer<VideoFrame> {

	private static final long serialVersionUID = 1L;

	/** Serializes conversion; never acquired by the event dispatch thread. */
	private final Object convertLock = new Object();

	/** Guards the displayed image and its hand-over. */
	private final Object lock = new Object();

	/** The picture shown. */
	private transient BufferedImage front;

	/** The picture the next frame is converted into. */
	private transient BufferedImage back;

	private transient ByteBuffer pixels;

	private long generation;

	private int rotation;

	private volatile boolean mirrored;


	/**
	 * Creates a view with a black background and a preferred size of 640 by
	 * 360 pixels.
	 */
	public VideoView() {
		setBackground(Color.BLACK);
		setOpaque(true);
		setPreferredSize(new Dimension(640, 360));
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
		synchronized (lock) {
			started = generation;
		}

		synchronized (convertLock) {
			if (back == null || back.getWidth() != width || back.getHeight() != height) {
				back = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			}
			if (pixels == null || pixels.capacity() < byteCount) {
				long previous = pixels == null ? 0 : pixels.capacity();
				int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(byteCount, previous * 2L));
				pixels = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
			}

			try {
				// libyuv's ARGB is B, G, R, A in memory, which read as
				// little-endian ints is what TYPE_INT_RGB holds.
				pixels.clear();
				pixels.limit(byteCount);
				VideoBufferConverter.convertFromI420(buffer, pixels, FourCC.ARGB);
				pixels.rewind();
				pixels.asIntBuffer().get(((DataBufferInt) back.getRaster().getDataBuffer()).getData());
			}
			catch (Exception e) {
				throw new IllegalStateException("The frame could not be converted", e);
			}

			synchronized (lock) {
				if (started != generation) {
					return;
				}
				BufferedImage shown = front;
				front = back;
				back = shown;
				rotation = frame.rotation;
			}
		}

		repaint();
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
	 * Removes the picture, leaving the background, and drops frames already
	 * waiting for or undergoing conversion.
	 */
	public void clear() {
		synchronized (lock) {
			generation++;
			front = null;
		}

		repaint();
	}

	/**
	 * @param mirrored true to flip the picture horizontally, as a local camera
	 *                 preview usually is.
	 */
	public void setMirrored(boolean mirrored) {
		this.mirrored = mirrored;
		repaint();
	}

	/**
	 * @return true when the picture is flipped horizontally.
	 */
	public boolean isMirrored() {
		return mirrored;
	}

	/**
	 * @return a copy of the picture shown, as the frame was, without rotation
	 * or mirroring; null when there is none.
	 */
	public BufferedImage snapshot() {
		synchronized (lock) {
			if (front == null) {
				return null;
			}

			BufferedImage copy = new BufferedImage(front.getWidth(), front.getHeight(), BufferedImage.TYPE_INT_RGB);
			copy.setData(front.getRaster());

			return copy;
		}
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		graphics.setColor(getBackground());
		graphics.fillRect(0, 0, getWidth(), getHeight());

		synchronized (lock) {
			if (front == null) {
				return;
			}

			boolean sideways = rotation == 90 || rotation == 270;
			int imageWidth = sideways ? front.getHeight() : front.getWidth();
			int imageHeight = sideways ? front.getWidth() : front.getHeight();
			double scale = Math.min((double) getWidth() / imageWidth, (double) getHeight() / imageHeight);

			Graphics2D g = (Graphics2D) graphics.create();

			try {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

				// Around the center of the component: scale, rotate, mirror, then
				// draw the image centered on the origin.
				AffineTransform transform = new AffineTransform();
				transform.translate(getWidth() / 2.0, getHeight() / 2.0);
				transform.scale(mirrored ? -scale : scale, scale);
				transform.rotate(Math.toRadians(rotation));
				transform.translate(-front.getWidth() / 2.0, -front.getHeight() / 2.0);

				g.drawImage(front, transform, null);
			}
			finally {
				g.dispose();
			}
		}
	}
}
