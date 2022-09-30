/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_REF_H_
#define JNI_JAVA_REF_H_

#include "JavaUtils.h"

#include <jni.h>

namespace jni
{
	template <class T>
	class JavaRef;


	template <>
	class JavaRef<jobject>
	{
		public:
			JavaRef(const JavaRef &) = delete;
			JavaRef & operator=(const JavaRef &) = delete;

			operator jobject() const
			{
				return obj;
			}

			jobject get() const
			{
				return obj;
			}

		protected:
			JavaRef() :
				obj(nullptr)
			{
			}

			explicit JavaRef(jobject obj) :
				obj(obj)
			{
			}

		protected:
			jobject obj;
	};

	template <class T>
	class JavaRef : public JavaRef<jobject>
	{
		public:
			JavaRef(const JavaRef &) = delete;
			JavaRef & operator=(const JavaRef &) = delete;

			operator T() const
			{
				return static_cast<T>(obj);
			}

			T get() const
			{
				return static_cast<T>(obj);
			}

		protected:
			JavaRef() :
				JavaRef<jobject>(nullptr)
			{
			}
			
			explicit JavaRef(T obj) :
				JavaRef<jobject>(obj)
			{
			}
	};


	template <class T, typename Enable = void>
	class JavaLocalRef;

	template <class T>
	class JavaLocalRef<T, typename std::enable_if_t<std::is_convertible<T, jobject>::value>> : public JavaRef<T>
	{
		public:
			JavaLocalRef() = default;
			
			JavaLocalRef(std::nullptr_t) :
				env(nullptr)
			{
			}

			JavaLocalRef(JNIEnv * env, T obj) :
				JavaRef<T>(obj),
				env(env)
			{
			}

			JavaLocalRef(JNIEnv * env, const JavaRef<T> & other) :
				env(env)
			{
				retain(other);
			}

			JavaLocalRef(JavaLocalRef && other) :
				env(other.env)
			{
				adopt(other.release());
			}

			template <typename U, typename = std::enable_if_t<std::is_convertible<U, T>::value>>
			JavaLocalRef(JavaLocalRef<U> && other) :
				env(other.env)
			{
				adopt(other.release());
			}

			JavaLocalRef(const JavaLocalRef & other) :
				env(other.env)
			{
				retain(other);
			}

			~JavaLocalRef()
			{
				deleteLocalRef();
			}

			JavaLocalRef & operator=(const JavaLocalRef & other)
			{
				env = other.env;

				retain(other);

				return *this;
			}

			JavaLocalRef & operator=(JavaLocalRef && other)
			{
				env = AttachCurrentThread();

				adopt(other.release());

				return *this;
			}

			T release()
			{
				T ret = static_cast<T>(this->obj);
				this->obj = nullptr;
				return ret;
			}

			T release() const
			{
				T ret = static_cast<T>(this->obj);
				this->obj = nullptr;
				return ret;
			}

			JNIEnv * getEnv()
			{
				return env;
			}

		private:
			void adopt(T obj)
			{
				deleteLocalRef();

				this->obj = obj;
			}

			void retain(T obj)
			{
				deleteLocalRef();

				if (obj != nullptr) {
					this->obj = env->NewLocalRef(obj);
				}
			}

			void deleteLocalRef()
			{
				if (env == nullptr) {
					env = AttachCurrentThread();
				}

				if (this->obj != nullptr) {
					env->DeleteLocalRef(this->obj);
				}
			}

		private:
			JNIEnv * env;
	};



	template <typename T>
	class JavaGlobalRef : public JavaRef<T>
	{
		public:
			explicit JavaGlobalRef(std::nullptr_t)
			{
			}

			JavaGlobalRef(JNIEnv * env, T obj) :
				JavaRef<T>(static_cast<T>(env->NewGlobalRef(obj)))
			{
			}

			JavaGlobalRef(JNIEnv * env, const JavaRef<T> & other)
				: JavaRef<T>(static_cast<T>(env->NewGlobalRef(other.obj)))
			{
			}

			JavaGlobalRef(const JavaGlobalRef & other)
			{
				reset(other);
			}

			JavaGlobalRef(JavaGlobalRef && other) :
				JavaRef<T>(other.release())
			{
			}

			~JavaGlobalRef()
			{
				deleteGlobalRef();
			}

			JavaGlobalRef & operator=(const JavaGlobalRef & other) {
				reset(other);
				
				return *this;
			}

			JavaGlobalRef & operator=(JavaGlobalRef && other) {
				deleteGlobalRef();

				this->obj = other.release();
				
				return *this;
			}

			T release()
			{
				T ret = static_cast<T>(this->obj);
				this->obj = nullptr;
				return ret;
			}

		private:
			void reset(const JavaGlobalRef & other)
			{
				deleteGlobalRef();

				if (other.get()) {
					JNIEnv * env = AttachCurrentThread();
					this->obj = env->NewGlobalRef(other.get());
				}
			}

			void deleteGlobalRef()
			{
				if (this->obj != nullptr) {
					JNIEnv * env = nullptr;

					if (env) {
						env->DeleteGlobalRef(this->obj);
					}

					this->obj = nullptr;
				}
			}
	};


	template <typename T>
	inline JavaLocalRef<T> static_java_ref_cast(JNIEnv * env, const JavaRef<jobject> & ref) {
		JavaLocalRef<jobject> owned_ref(env, ref);
		return JavaLocalRef<T>(env, static_cast<T>(owned_ref.release()));
	}
}

#endif