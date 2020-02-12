/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_WINDOWS_COM_PTR_H_
#define JNI_WINDOWS_COM_PTR_H_

#include <Windows.h>

namespace jni
{
	template <class Interface, const IID * piid = nullptr>
	class ComPtr
	{
		public:
			ComPtr() : ptr(nullptr)
			{
			}

			ComPtr(Interface * p) : ptr(p)
			{
				if (p != nullptr) {
					ptr->AddRef();
				}
			}

			ComPtr(const ComPtr<Interface, piid> & refComPtr) : ptr(refComPtr.ptr)
			{
				if (ptr) {
					ptr->AddRef();
				}
			}

			~ComPtr()
			{
				Release();
			}

		public:
			operator Interface*() const
			{
				return ptr;
			}

			bool operator!() const
			{
				return (ptr == nullptr);
			}

			Interface ** operator&()
			{
				return &ptr;
			}

			Interface & operator*() const
			{
				return *ptr;
			}

			bool operator<(Interface * p) const
			{
				return ptr < p;
			}

			bool operator!=(Interface * p) const
			{
				return !operator==(p);
			}

			bool operator==(Interface * p) const
			{
				return ptr == p;
			}

			Interface * operator->() const
			{
				return ptr;
			}

			void Attach(Interface * p)
			{
				if (p) {
					Release();
					ptr = p;
				}
			}

			Interface * Detach()
			{
				Interface * copy = ptr;
				ptr = nullptr;
				return copy;
			}

			void Release()
			{
				if (ptr) {
					ptr->Release();
					ptr = nullptr;
				}
			}

			template <typename Query>
			HRESULT QueryInterface(Query ** interfacePtr)
			{
				return ptr->QueryInterface(interfacePtr);
			}

			HRESULT QueryInterface(const IID & iid, void ** object) {
				return ptr->QueryInterface(iid, object);
			}

			BOOL IsEqualObject(IUnknown * other)
			{
				if (!ptr || !other)
					return false;

				if (!ptr && !other)
					return true;

				IUnknown * mid = nullptr;
				ptr->QueryInterface(IID_IUnknown, (void**)&mid);

				IUnknown * oid = nullptr;
				other->QueryInterface(IID_IUnknown, (void**)&oid);

				BOOL equal = static_cast<IUnknown *>(mid) == static_cast<IUnknown *>(oid);

				mid->Release();
				oid->Release();

				return equal;
			}

		private:
			Interface * ptr;
	};
}

#endif