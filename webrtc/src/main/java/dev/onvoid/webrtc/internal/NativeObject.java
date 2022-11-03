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

package dev.onvoid.webrtc.internal;

/**
 * Binds a native object pointer to a Java object extending this class.
 *
 * @author Alex Andres
 */
public abstract class NativeObject {

	/**
	 * Field used to store a pointer to the native instance allocated on the
	 * heap. Don't modify this value directly, or else you risk causing
	 * segfaults or memory leaks.
	 */
	@SuppressWarnings("unused")
	private long nativeHandle;

}
