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

#ifndef JNI_WEBRTC_API_FIELD_TRIALS_VIEW_H_
#define JNI_WEBRTC_API_FIELD_TRIALS_VIEW_H_

#include "api/field_trials_view.h"

#include <map>
#include <string>

namespace jni
{
	// A minimal FieldTrialsView backed by a std::map<std::string, std::string>.
	// webrtc::FieldTrials (api/field_trials.h) is not used here because its
	// translation unit is not part of the linked webrtc archive for every
	// build configuration of this project.
	class FieldTrialsView : public webrtc::FieldTrialsView
	{
		public:
			explicit FieldTrialsView(std::map<std::string, std::string> trials);
			virtual ~FieldTrialsView() = default;

			// webrtc::FieldTrialsView implementation.
			std::string Lookup(absl::string_view key) const override;

		private:
			std::map<std::string, std::string> trials;
	};
}

#endif
