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

package dev.onvoid.webrtc;

/**
 * TLS certificate policy.
 *
 * @author Alex Andres
 */
public enum TlsCertPolicy {

	/**
	 * For TLS based protocols, ensure the connection is secure by not
	 * circumventing certificate validation.
	 */
	SECURE,

	/**
	 * For TLS based protocols, disregard security completely by skipping
	 * certificate validation. This is insecure and should never be used unless
	 * security is irrelevant in that particular context.
	 */
	INSECURE_NO_CHECK;

}
