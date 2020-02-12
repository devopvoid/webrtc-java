/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_WINDOWS_COM_INITIALIZER_H_
#define JNI_WINDOWS_COM_INITIALIZER_H_

#include <Windows.h>

namespace jni
{
	class ComInitializer
	{
		public:
			ComInitializer();
			virtual ~ComInitializer();

			bool isInitialized();

		private:
			bool initialized;
	};
}

#endif