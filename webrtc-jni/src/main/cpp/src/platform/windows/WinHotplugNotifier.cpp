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

#include "platform/windows/WinHotplugNotifier.h"
#include "platform/windows/WinUtils.h"

#include <dbt.h>
#include <ks.h>
#include <ksmedia.h>
#include <windowsx.h>
#include <algorithm>
#include <locale>

#include "rtc_base/logging.h"

namespace jni
{
	WinHotplugNotifier::WinHotplugNotifier(std::list<GUID> devInterfaces) :
		deviceInterfaces(devInterfaces),
		hWnd(nullptr),
		threadHandle(nullptr)
	{
	}

	WinHotplugNotifier::~WinHotplugNotifier()
	{
		stop();
	}

	void WinHotplugNotifier::start()
	{
		threadHandle = CreateThread(nullptr, 0, run, this, 0, nullptr);
	}

	void WinHotplugNotifier::stop()
	{
		PostMessage(hWnd, WM_CLOSE, 0, 0);

		if (threadHandle != nullptr) {
			WaitForSingleObject(threadHandle, INFINITE);
			CloseHandle(threadHandle);
			threadHandle = nullptr;
		}
	}

	DWORD WINAPI WinHotplugNotifier::run(void * context)
	{
		WinHotplugNotifier * notifier = static_cast<WinHotplugNotifier *>(context);

		if (!notifier->initializeWindow()) {
			RTC_LOG(LS_ERROR) << "MMF: Initialize notification window failed";
			return 0;
		}
		if (!notifier->registerForDeviceNotification()) {
			RTC_LOG(LS_INFO) << "MMF: Register for device notification failed";
			return 0;
		}

		MSG msg = { 0 };

		while (GetMessage(&msg, notifier->hWnd, 0, 0)) {
			TranslateMessage(&msg);
			DispatchMessage(&msg);
		}

		return 0;
	}

	bool WinHotplugNotifier::initializeWindow()
	{
		HINSTANCE hInstance = GetModuleHandle(nullptr);

		WNDCLASSEX wcex = { 0 };
		wcex.cbSize = sizeof(WNDCLASSEX);
		wcex.style = CS_OWNDC | CS_HREDRAW | CS_VREDRAW;
		wcex.lpfnWndProc = WinHotplugNotifier::wndProc;
		wcex.cbClsExtra = 0;
		wcex.cbWndExtra = 0;
		wcex.hInstance = hInstance;
		wcex.hIcon = NULL;
		wcex.hCursor = NULL;
		wcex.hbrBackground = (HBRUSH)(COLOR_WINDOW);
		wcex.lpszMenuName = NULL;
		wcex.lpszClassName = CLASS_NAME;
		wcex.hIconSm = NULL;

		if (!RegisterClassEx(&wcex)) {
			return false;
		}

		hWnd = CreateWindow(CLASS_NAME, WINDOW_NAME, WS_ICONIC, 0, 0, CW_USEDEFAULT, 0, nullptr, nullptr, hInstance, nullptr);
		
		if (!hWnd) {
			return false;
		}

		SetWindowLongPtr(hWnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));

		ShowWindow(hWnd, SW_HIDE);
		UpdateWindow(hWnd);

		return true;
	}

	bool WinHotplugNotifier::registerForDeviceNotification()
	{
		DEV_BROADCAST_DEVICEINTERFACE di = { 0 };
		di.dbcc_size = sizeof(DEV_BROADCAST_DEVICEINTERFACE);
		di.dbcc_devicetype = DBT_DEVTYP_DEVICEINTERFACE;

		for (GUID & devInterface : deviceInterfaces) {
			di.dbcc_classguid = devInterface;

			HDEVNOTIFY handle = RegisterDeviceNotification(hWnd, &di, DEVICE_NOTIFY_WINDOW_HANDLE);

			if (handle != nullptr) {
				deviceNotifyHandles.push_back(handle);
			}
			else {
				RTC_LOG(LS_ERROR) << "Register device notification failed";
			}
		}

		return (deviceInterfaces.size() == deviceNotifyHandles.size());
	}

	void WinHotplugNotifier::onClose(HWND hWnd)
	{
		WinHotplugNotifier * win = reinterpret_cast<WinHotplugNotifier *>(GetWindowLongPtr(hWnd, GWLP_USERDATA));
		
		for (auto i = win->deviceNotifyHandles.begin(); i != win->deviceNotifyHandles.end();) {
			HDEVNOTIFY devNotify = *i;

			if (devNotify != nullptr) {
				UnregisterDeviceNotification(devNotify);
			}

			i = win->deviceNotifyHandles.erase(i);
		}

		PostQuitMessage(0);
	}

	void WinHotplugNotifier::onDeviceChange(HWND hWnd, WPARAM wParam, LPARAM lParam)
	{
		if (lParam == 0) {
			return;
		}
		if (wParam == DBT_USERDEFINED) {
			return;
		}
		
		// All remaining wParams use DEV_BROADCAST_HDR.
		auto header = reinterpret_cast<PDEV_BROADCAST_HDR>(lParam);

		if (header == nullptr) {
			return;
		}
		
		if (header->dbch_devicetype != DBT_DEVTYP_DEVICEINTERFACE) {
			return;
		}
		
		auto devInterface = reinterpret_cast<PDEV_BROADCAST_DEVICEINTERFACE>(lParam);

		if (devInterface == nullptr) {
			return;
		}

		WinHotplugNotifier * win = reinterpret_cast<WinHotplugNotifier *>(GetWindowLongPtr(hWnd, GWLP_USERDATA));

		char buffer[512] = { 0 };
		strncpy_s(buffer, devInterface->dbcc_name, 512);

		std::string symLink(buffer);
		std::transform(symLink.begin(), symLink.end(), symLink.begin(), ::tolower);

		std::wstring symLink_w = UTF8Decode(symLink);

		switch (wParam) {
			case DBT_DEVICEARRIVAL:
				win->onDeviceConnected(symLink_w);
				break;

			case DBT_DEVICEREMOVECOMPLETE:
				win->onDeviceDisconnected(symLink_w);
				break;
		}
	}

	LRESULT CALLBACK WinHotplugNotifier::wndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam)
	{
		LRESULT ret = 1;

		switch (message) {
			case WM_CLOSE:
				onClose(hWnd);
				break;

			case WM_DEVICECHANGE:
				onDeviceChange(hWnd, wParam, lParam);
				break;

			default:
				ret = DefWindowProc(hWnd, message, wParam, lParam);
				break;
		}

		return ret;
	}
}