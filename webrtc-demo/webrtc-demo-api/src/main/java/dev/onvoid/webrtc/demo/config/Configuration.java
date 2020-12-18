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

package dev.onvoid.webrtc.demo.config;

import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.demo.beans.ObjectProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.inject.Singleton;

@Singleton
public class Configuration {

	/** The version of the application. */
	private String version;

	/** The build date of the application. */
	private LocalDateTime buildDate;

	/** The locale of the application. */
	private final ObjectProperty<Locale> locale;

	private final AudioConfiguration audioConfig;

	private final VideoConfiguration videoConfig;

	private final DesktopCaptureConfiguration desktopCaptureConfig;

	private final RTCConfiguration rtcConfig;


	public Configuration() {
		locale = new ObjectProperty<>();
		audioConfig = new AudioConfiguration();
		videoConfig = new VideoConfiguration();
		desktopCaptureConfig = new DesktopCaptureConfiguration();
		rtcConfig = new RTCConfiguration();

		initMetaData();
	}

	/**
	 * Get the current version of the application.
	 *
	 * @return the version of the application.
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Set the current version of the application.
	 *
	 * @param version The new version to set.
	 */
	public void setVersion(String version) {
		this.version = version;
	}

	/**
	 * Get the build date of the application.
	 *
	 * @return the build date of the application.
	 */
	public LocalDateTime getBuildDate() {
		return buildDate;
	}

	/**
	 * Set the build date of the application.
	 *
	 * @param date The build date of the application.
	 */
	public void setBuildDate(LocalDateTime date) {
		this.buildDate = date;
	}

	/**
	 * Get the current locale of the application.
	 *
	 * @return the current locale.
	 */
	public Locale getLocale() {
		return locale.get();
	}

	/**
	 * Set the new locale of the application.
	 *
	 * @param locale The new locale to set.
	 */
	public void setLocale(Locale locale) {
		this.locale.set(locale);
	}

	/**
	 * Get the locale property.
	 *
	 * @return the locale property.
	 */
	public ObjectProperty<Locale> localeProperty() {
		return locale;
	}

	public AudioConfiguration getAudioConfiguration() {
		return audioConfig;
	}

	public VideoConfiguration getVideoConfiguration() {
		return videoConfig;
	}

	public DesktopCaptureConfiguration getDesktopCaptureConfiguration() {
		return desktopCaptureConfig;
	}

	public RTCConfiguration getRTCConfig() {
		return rtcConfig;
	}

	private void initMetaData() {
		String version = "1.0";
		LocalDateTime date = LocalDateTime.now();

		try {
			Manifest manifest = new Manifest(getClass().getResourceAsStream(JarFile.MANIFEST_NAME));
			Attributes attr = manifest.getMainAttributes();
			String buildDateAttr = attr.getValue("Build-Date");
			String versionAttr = attr.getValue("Version");

			if (nonNull(versionAttr)) {
				version = versionAttr;
			}
			if (nonNull(buildDateAttr)) {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
				date = LocalDateTime.parse(buildDateAttr, formatter);
			}
		}
		catch (Exception e) {
			// Ignore
		}

		setVersion(version);
		setBuildDate(date);
	}
}
