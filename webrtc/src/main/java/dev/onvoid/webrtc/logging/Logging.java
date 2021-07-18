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

package dev.onvoid.webrtc.logging;

import java.io.PrintWriter;
import java.io.StringWriter;

import dev.onvoid.webrtc.internal.NativeLoader;

public class Logging {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	public enum Severity {

		/**
		 * For data which should not appear in the normal debug log, but should
		 * appear in diagnostic logs.
		 */
		VERBOSE,

		/**
		 * Used in debugging.
		 */
		INFO,

		/**
		 * Something that may warrant investigation.
		 */
		WARNING,

		/**
		 * A critical error has occurred.
		 */
		ERROR,

		/**
		 * Do not log.
		 */
		NONE;

	}

	public static native void addLogSink(Severity severity, LogSink sink);

	public static native void log(Severity severity, String message);

	public static native void logToDebug(Severity severity);

	public static native void logThreads(boolean enable);

	public static native void logTimestamps(boolean enable);

	public static void verbose(String message) {
		log(Severity.VERBOSE, message);
	}

	public static void info(String message) {
		log(Severity.INFO, message);
	}

	public static void warn(String message) {
		log(Severity.WARNING, message);
	}

	public static void error(String message) {
		log(Severity.ERROR, message);
	}

	public static void error(String message, Throwable e) {
		log(Severity.ERROR, message);
		log(Severity.ERROR, e.toString());
		log(Severity.ERROR, getStackTraceString(e));
	}

	private static String getStackTraceString(Throwable e) {
		if (e == null) {
			return "";
		}

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);

		e.printStackTrace(pw);

		return sw.toString();
	}
}
