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
 * Represents an action that takes no arguments and returns no result.
 *
 * @author Alex Andres
 */
@FunctionalInterface
public interface Action {

	/**
	 * Performs this operation.
	 */
	void execute();

	/**
	 * Concatenates two given actions. If the first {@code Action} is {@code null},
	 * then the next {@code Action} is returned.
	 *
	 * @param first the first action.
	 * @param next the action to concatenate to the first action.
	 *
	 * @return the concatenated actions.
	 *
	 * @see #andThen(Action)
	 */
	static Action concatenate(Action first, Action next) {
		return isNull(first) ? next : first.andThen(next);
	}

	/**
	 * Returns a composed {@code Action} that executes, in sequence, this
	 * action followed by the {@code next} action. If performing either
	 * action throws an exception, it is relayed to the caller of the
	 * composed action. If performing this action throws an exception,
	 * the {@code next} action will not be performed.
	 *
	 * @param next the action to perform after this action.
	 *
	 * @return a composed {@code Action} that executes in sequence this
	 * action followed by the {@code next} action.
	 *
	 * @throws NullPointerException if {@code next} is null.
	 */
	default Action andThen(Action next) {
		Objects.requireNonNull(next);

		return () -> {
			execute();
			next.execute();
		};
	}

}
