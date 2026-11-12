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

package dev.onvoid.webrtc.media.video.desktop;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A black window in a process of its own, for capturing a window that this
 * process does not own. The test drives it through the child's standard
 * input.
 */
public final class TestWindowProcess implements AutoCloseable {

	private final Process process;

	private final PrintWriter commands;

	private final String title;


	private TestWindowProcess(Process process, String title) {
		this.process = process;
		this.title = title;
		this.commands = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
	}

	/**
	 * Opens a window of the given outer size, in points.
	 */
	public static TestWindowProcess open(int width, int height) throws Exception {
		String title = "webrtc-java " + UUID.randomUUID();
		Path classes = Path.of(TestWindowProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");

		Process process = new ProcessBuilder(java.toString(), "-cp", classes.toString(),
				Child.class.getName(), title, Integer.toString(width), Integer.toString(height))
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start();

		return new TestWindowProcess(process, title);
	}

	/**
	 * Returns the window as listed by a WindowCapturer, or null if it does
	 * not show up within a few seconds. Without screen recording permission,
	 * the windows of other processes are listed without their titles.
	 */
	public DesktopSource find() throws InterruptedException {
		return findWindow(title);
	}

	public void setColor(boolean white) {
		commands.println(white ? "white" : "black");
	}

	public void setSize(int width, int height) {
		commands.println("size " + width + " " + height);
	}

	public void closeWindow() {
		commands.println("close");
	}

	@Override
	public void close() throws InterruptedException {
		commands.println("exit");

		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly();
		}
	}

	/**
	 * Returns the window of the given title as listed by a WindowCapturer, or
	 * null if it does not show up within a few seconds.
	 */
	public static DesktopSource findWindow(String title) throws InterruptedException {
		WindowCapturer capturer = new WindowCapturer();

		try {
			for (int i = 0; i < 100; i++) {
				for (DesktopSource window : capturer.getDesktopSources()) {
					if (title.equals(window.title)) {
						return window;
					}
				}

				Thread.sleep(100);
			}
		}
		finally {
			capturer.dispose();
		}

		return null;
	}

	/**
	 * The window's process. It needs nothing but AWT on its class path, so it
	 * refers to no other class of this library.
	 */
	static final class Child {

		public static void main(String[] args) throws Exception {
			Frame window = new Frame(args[0]);

			EventQueue.invokeAndWait(() -> {
				window.setBackground(Color.BLACK);
				window.setBounds(100, 100, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
				window.setVisible(true);
				// Not hidden behind other windows, where it may not be redrawn.
				window.toFront();
			});

			BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			String line;

			while ((line = readLine(input)) != null && !line.equals("exit")) {
				String[] command = line.split(" ");

				EventQueue.invokeAndWait(() -> {
					if (command[0].equals("white") || command[0].equals("black")) {
						window.setBackground(command[0].equals("white") ? Color.WHITE : Color.BLACK);
						window.repaint();
					}
					else if (command[0].equals("size")) {
						window.setSize(Integer.parseInt(command[1]), Integer.parseInt(command[2]));
					}
					else if (command[0].equals("close")) {
						window.dispose();
					}
				});
			}

			System.exit(0);
		}

		private static String readLine(BufferedReader input) {
			try {
				return input.readLine();
			}
			catch (IOException e) {
				return null;
			}
		}
	}
}
