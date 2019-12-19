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
 * Allows to dispose the native object bound to the Java object and free native
 * resources.
 *
 * @author Alex Andres
 */
public abstract class DisposableNativeObject extends NativeObject {

	/**
	 * Invoke native object destructors and free native resources.
	 */
	public abstract void dispose();

}
