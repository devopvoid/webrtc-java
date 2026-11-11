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

package dev.onvoid.webrtc.client;

/**
 * A WHIP or WHEP endpoint answered a request with an error: its status, and
 * what problem details (RFC 9457) it sent with it say, where it sent any.
 * <p>
 * The status tells the class of the error, such as a client error for 4xx.
 * RFC 9725 (4.1) asks clients not to read a specific cause into a status
 * beyond that, since endpoints use them differently; the title and the detail
 * are meant for people, the problem type for programs.
 */
public class HttpStatusException extends WebRtcException {

	private static final long serialVersionUID = 1L;

	private final int status;

	private final String problemType;

	private final String title;

	private final String detail;


	/**
	 * Creates the exception with a message that says what went wrong.
	 *
	 * @param message     the detail message.
	 * @param status      the status of the response.
	 * @param problemType the type of the problem details, or null.
	 * @param title       the title of the problem details, or null.
	 * @param detail      the detail of the problem details, or null.
	 */
	public HttpStatusException(String message, int status, String problemType, String title, String detail) {
		super(message);

		this.status = status;
		this.problemType = problemType;
		this.title = title;
		this.detail = detail;
	}

	/**
	 * @return the status of the response, such as 403.
	 */
	public int status() {
		return status;
	}

	/**
	 * @return the URI that identifies the type of the problem, as the
	 * problem details give it, such as {@code
	 * https://example.com/probs/stream-key}; null if the endpoint sent none.
	 */
	public String problemType() {
		return problemType;
	}

	/**
	 * @return the short summary of the problem the endpoint sent, such as
	 * {@code Forbidden}, or null.
	 */
	public String title() {
		return title;
	}

	/**
	 * @return the explanation of this occurrence of the problem the endpoint
	 * sent, such as {@code The stream key has expired}, or null.
	 */
	public String detail() {
		return detail;
	}

}
