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

#include "api/FieldTrialsView.h"

namespace jni
{
	FieldTrialsView::FieldTrialsView(std::map<std::string, std::string> trials) :
		trials(std::move(trials))
	{
	}

	std::string FieldTrialsView::Lookup(absl::string_view key) const
	{
		auto it = trials.find(std::string(key));

		return it != trials.end() ? it->second : std::string();
	}
}
