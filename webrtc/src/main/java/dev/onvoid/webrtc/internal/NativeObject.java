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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Binds a native object pointer to a Java object extending this class.
 *
 * @author Alex Andres
 */
public abstract class NativeObject {

	// Loaded before any class that wraps a native object is used, also when
	// it is used on its own, without a factory that loaded the library.
	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

	/**
	 * Field used to store a pointer to the native instance allocated on the
	 * heap. Don't modify this value directly, or else you risk causing
	 * segfaults or memory leaks.
	 */
	@SuppressWarnings("unused")
	private long nativeHandle;

	/** The hash code of {@link #nativeHashCode()}, or 0 until it is asked for. */
	private volatile int nativeHash;

	private static final AtomicIntegerFieldUpdater<NativeObject> NATIVE_HASH =
			AtomicIntegerFieldUpdater.newUpdater(NativeObject.class, "nativeHash");


	/**
	 * Returns the native pointer bound to this object, so that subclasses
	 * can implement identity-based {@code equals()}/{@code hashCode()}. A
	 * value of {@code 0} means no native object is currently bound (e.g.
	 * after disposal).
	 *
	 * @return the native handle value.
	 */
	protected final long getNativeHandle() {
		return nativeHandle;
	}

	/**
	 * Returns a hash code for subclasses whose {@code equals()} compares
	 * native handles, which stays the same for the whole life of this
	 * object, unlike the handle: it is taken from the handle the first time
	 * it is asked for, and kept after disposal, so that a disposed object
	 * is still found in the hash set it was added to before.
	 * <p>
	 * Equal objects share their handle, and so their hash code: a native
	 * object stays alive, at the same address, while any undisposed wrapper
	 * of it does, and a disposed wrapper is equal only to itself. A new
	 * native object at the address of a freed one hashes the same as the
	 * old wrapper without being equal to it, which costs a collision only.
	 *
	 * @return the hash code of this object's native identity.
	 */
	protected final int nativeHashCode() {
		int hash = nativeHash;

		if (hash == 0) {
			long handle = nativeHandle;

			hash = handle != 0 ? Long.hashCode(handle) : System.identityHashCode(this);

			// Zero marks a hash code not taken yet.
			if (hash == 0) {
				hash = 1;
			}

			// The first one taken wins, should a disposal on another thread
			// race with the first call.
			if (!NATIVE_HASH.compareAndSet(this, 0, hash)) {
				hash = nativeHash;
			}
		}

		return hash;
	}

}
