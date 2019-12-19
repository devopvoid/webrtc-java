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

package dev.onvoid.webrtc.demo.view;

import static java.util.Objects.isNull;

import java.util.Objects;

/**
 * Represents an action that accepts a single input argument and returns no
 * result.
 *
 * @param <T> The type of the input to the action.
 *
 * @author Alex Andres
 */
@FunctionalInterface
public interface ConsumerAction<T> {

	/**
	 * Performs this operation on the given argument.
	 *
	 * @param value The input value.
	 */
	void execute(T value);

	/**
	 * Concatenates two given consumer actions. If the first {@code ConsumerAction} is
	 * {@code null}, then the next {@code ConsumerAction} is returned.
	 *
	 * @param first the first action.
	 * @param next the action to concatenate to the first action.
	 *
	 * @return the concatenated actions.
	 *
	 * @see #andThen(ConsumerAction)
	 */
	static <T> ConsumerAction<T> concatenate(ConsumerAction<T> first,
			ConsumerAction<T> next) {
		return isNull(first) ? next : first.andThen(next);
	}

	/**
	 * Returns a composed {@code ConsumerAction} that executes, in sequence, this
	 * action followed by the {@code next} action. If performing either
	 * action throws an exception, it is relayed to the caller of the
	 * composed action. If performing this action throws an exception,
	 * the {@code next} action will not be performed.
	 *
	 * @param next the action to perform after this action.
	 *
	 * @return a composed {@code ConsumerAction} that executes in sequence this
	 * action followed by the {@code next} action.
	 *
	 * @throws NullPointerException if {@code next} is null.
	 */
	default ConsumerAction<T> andThen(ConsumerAction<? super T> next) {
		Objects.requireNonNull(next);

		return value -> {
			execute(value);
			next.execute(value);
		};
	}
}
