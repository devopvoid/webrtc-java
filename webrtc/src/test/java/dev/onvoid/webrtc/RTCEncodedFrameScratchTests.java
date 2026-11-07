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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class RTCEncodedFrameScratchTests {

	@Test
	void oversizedFramesStayTemporary() throws Exception {
		Field field = RTCEncodedFrame.class.getDeclaredField("SCRATCH");
		field.setAccessible(true);
		ThreadLocal<?> cache = (ThreadLocal<?>) field.get(null);
		Method method = RTCEncodedFrame.class.getDeclaredMethod("scratch", int.class);
		method.setAccessible(true);
		cache.remove();
		try {
			int limit = 256 * 1024;
			ByteBuffer large = (ByteBuffer) method.invoke(null, limit + 1);
			assertTrue(large.isDirect());
			assertEquals(limit + 1, large.capacity());
			assertNull(cache.get());

			ByteBuffer small = (ByteBuffer) method.invoke(null, 4096);
			assertSame(small, cache.get());
			ByteBuffer boundary = (ByteBuffer) method.invoke(null, limit);
			assertEquals(limit, boundary.capacity());
			assertSame(boundary, cache.get());
			assertNotSame(large, method.invoke(null, limit + 1));
			assertSame(boundary, cache.get());
			assertSame(boundary, method.invoke(null, 4096));
		}
		finally {
			cache.remove();
		}
	}
}
