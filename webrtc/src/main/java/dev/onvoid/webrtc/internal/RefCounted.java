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
 * Interface for ref counted objects in WebRTC. These objects have significant
 * resources that need to be freed when they are no longer in use. Each objects
 * starts with ref count of one when created. If a reference is passed as a
 * parameter to a method, the caller has ownesrship of the object by default -
 * calling release is not necessary unless retain is called.
 */
public interface RefCounted {
	
	/** Increases ref count by one. */
	void retain();

	/**
	 * Decreases ref count by one. When the ref count reaches zero, resources
	 * related to the object will be freed.
	 */
	void release();
	
}
