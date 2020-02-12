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

#include "api/RTCIceServer.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCIceServer
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::IceServer & server)
		{
			const auto javaClass = JavaClasses::get<JavaRTCIceServerClass>(env);
			const auto & urls = server.urls;
			const auto & alpn = server.tls_alpn_protocols;
			const auto & ecv = server.tls_elliptic_curves;

			JavaArrayList urlList(env, urls.size());
			JavaArrayList alpnList(env, alpn.size());
			JavaArrayList ecList(env, ecv.size());

			for (auto & url : urls) {
				urlList.add(JavaString::toJava(env, url));
			}
			for (auto & protocol : alpn) {
				alpnList.add(JavaString::toJava(env, protocol));
			}
			for (auto & ec : ecv) {
				ecList.add(JavaString::toJava(env, ec));
			}

			auto username = JavaString::toJava(env, server.username);
			auto password = JavaString::toJava(env, server.password);
			auto hostname = JavaString::toJava(env, server.hostname);
			auto certPolicy = JavaEnums::toJava(env, server.tls_cert_policy);

			jobject iceServer = env->NewObject(javaClass->cls, javaClass->ctor);

			env->SetObjectField(iceServer, javaClass->urls, urlList.listObject());
			env->SetObjectField(iceServer, javaClass->tlsAlpnProtocols, alpnList.listObject());
			env->SetObjectField(iceServer, javaClass->tlsEllipticCurves, ecList.listObject());
			env->SetObjectField(iceServer, javaClass->username, username.get());
			env->SetObjectField(iceServer, javaClass->password, password.get());
			env->SetObjectField(iceServer, javaClass->hostname, hostname.get());
			env->SetObjectField(iceServer, javaClass->tlsCertPolicy, certPolicy.get());

			return JavaLocalRef<jobject>(env, iceServer);
		}

		webrtc::PeerConnectionInterface::IceServer toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCIceServerClass>(env);

			JavaObject obj(env, javaType);

			JavaLocalRef<jobject> urls = obj.getObject(javaClass->urls);
			JavaLocalRef<jstring> un = obj.getString(javaClass->username);
			JavaLocalRef<jstring> pw = obj.getString(javaClass->password);
			JavaLocalRef<jobject> cp = obj.getObject(javaClass->tlsCertPolicy);
			JavaLocalRef<jstring> hn = obj.getString(javaClass->hostname);
			JavaLocalRef<jobject> ap = obj.getObject(javaClass->tlsAlpnProtocols);
			JavaLocalRef<jobject> ec = obj.getObject(javaClass->tlsEllipticCurves);

			webrtc::PeerConnectionInterface::IceServer server;

			server.hostname = JavaString::toNative(env, hn);
			server.username = JavaString::toNative(env, un);
			server.password = JavaString::toNative(env, pw);

			server.urls = JavaList::toStringVector(env, urls);
			server.tls_alpn_protocols = JavaList::toStringVector(env, ap);
			server.tls_elliptic_curves = JavaList::toStringVector(env, ec);

			if (cp.get()) {
				server.tls_cert_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::TlsCertPolicy>(env, cp);
			}

			return server;
		}

		JavaRTCIceServerClass::JavaRTCIceServerClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCIceServer");

			ctor = GetMethod(env, cls, "<init>", "()V");

			urls = GetFieldID(env, cls, "urls", LIST_SIG);
			username = GetFieldID(env, cls, "username", STRING_SIG);
			password = GetFieldID(env, cls, "password", STRING_SIG);
			tlsCertPolicy = GetFieldID(env, cls, "tlsCertPolicy", "L" PKG "TlsCertPolicy;");
			hostname = GetFieldID(env, cls, "hostname", STRING_SIG);
			tlsAlpnProtocols = GetFieldID(env, cls, "tlsAlpnProtocols", LIST_SIG);
			tlsEllipticCurves = GetFieldID(env, cls, "tlsEllipticCurves", LIST_SIG);
		}
	}
}