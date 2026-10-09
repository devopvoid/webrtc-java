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

import static java.util.Objects.nonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class TestAddIceObserver implements AddIceCandidateObserver {

	private final CountDownLatch latch = new CountDownLatch(1);

	private volatile String error;


	@Override
	public void onSuccess() {
		latch.countDown();
	}

	@Override
	public void onFailure(String error) {
		this.error = error;

		latch.countDown();
	}

	void get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (!latch.await(timeout, unit)) {
			throw new TimeoutException();
		}
		if (nonNull(error)) {
			throw new ExecutionException(error, new IllegalStateException());
		}
	}
}
