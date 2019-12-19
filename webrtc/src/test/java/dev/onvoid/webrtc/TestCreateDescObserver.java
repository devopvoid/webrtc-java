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

import static java.util.Objects.nonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link CreateSessionDescriptionObserver} implementation providing
 * synchronized behaviour, causing the current thread to wait until the session
 * description is created.
 *
 * @author Alex Andres
 */
class TestCreateDescObserver implements CreateSessionDescriptionObserver, Future<RTCSessionDescription> {

	private CountDownLatch latch = new CountDownLatch(1);

	private RTCSessionDescription description;

	private String error;


	@Override
	public void onSuccess(RTCSessionDescription description) {
		this.description = description;

		latch.countDown();
	}

	@Override
	public void onFailure(String error) {
		this.error = error;

		latch.countDown();
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public boolean isDone() {
		return latch.getCount() == 0;
	}

	@Override
	public RTCSessionDescription get() throws InterruptedException, ExecutionException {
		latch.await();

		checkError();

		return description;
	}

	@Override
	public RTCSessionDescription get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (latch.await(timeout, unit)) {
			checkError();
			return description;
		}
		else {
			throw new TimeoutException();
		}
	}

	private void checkError() throws ExecutionException {
		if (nonNull(error)) {
			throw new ExecutionException(error, new IllegalStateException());
		}
	}
}
