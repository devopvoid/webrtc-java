/*
 * Copyright 2021 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

public class AudioProcessingStats {

	/**
	 * True if voice is detected in the last capture frame, after processing.
	 * <p>
	 * It is conservative in flagging audio as speech, with low likelihood of
	 * incorrectly flagging a frame as voice. Only reported if voice detection
	 * is enabled via {@code AudioProcessingConfig}.
	 */
	public boolean voiceDetected;

	/**
	 * AEC Statistic: ERL = 10log_10(P_far / P_echo)
	 */
	public double echoReturnLoss;

	/**
	 * AEC Statistic: ERLE = 10log_10(P_echo / P_out)
	 */
	public double echoReturnLossEnhancement;

	/**
	 * Fraction of time that the AEC linear filter is divergent, in a 1-second
	 * non-overlapped aggregation window.
	 */
	public double divergentFilterFraction;

	/**
	 * The instantaneous delay estimate produced in the AEC. The unit is in
	 * milliseconds and the value is the instantaneous value at the time of the
	 * call to getStatistics().
	 */
	public int delayMs;

	/**
	 * The delay metrics consists of the delay median and standard deviation. It
	 * also consists of the fraction of delay estimates that can make the echo
	 * cancellation perform poorly. The values are aggregated until the first
	 * call to getStatistics() and afterwards aggregated and updated every
	 * second. Note that if there are several clients pulling metrics from
	 * getStatistics() during a session the first call from any of them will
	 * change to one-second aggregation window for all.
	 */
	public int delayMedianMs;

	public int delayStandardDeviationMs;

	/**
	 * Residual echo detector likelihood.
	 */
	public double residualEchoLikelihood;

	/**
	 * Maximum residual echo likelihood from the last time period.
	 */
	public double residualEchoLikelihoodRecentMax;

}
