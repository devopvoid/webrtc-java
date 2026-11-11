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

/**
 * The end-to-end encryption of webrtc-java for browsers: the SFrame frame
 * format (RFC 9605) as the guide "End-to-End Encryption" specifies it, on
 * WebCrypto alone. It runs in a worker with RTCRtpScriptTransform, and in
 * Node.js, where webrtc-java's tests check it against the Java side.
 *
 * In a worker:
 *
 *   import { KeyProvider, attach } from './webrtc-java-sframe.js';
 *
 *   const keys = new KeyProvider({ participant: 2 });
 *   onmessage = async ({ data }) => { ...setEncryptionKey / setDecryptionKey... };
 *   onrtctransform = ({ transformer }) => attach(keys, transformer);
 *
 * and on the page:
 *
 *   sender.transform = new RTCRtpScriptTransform(worker, { side: 'send' });
 *   receiver.transform = new RTCRtpScriptTransform(worker, { side: 'receive' });
 */

const subtle = globalThis.crypto.subtle;

/** The name of the frame format, as the Java KeyProvider.FORMAT has it. */
export const FORMAT = 'webrtc-java-sframe/1';

/** The cipher suites of RFC 9605, Section 4.5, by their registry value. */
export const CipherSuite = Object.freeze({
	AES_128_CTR_HMAC_SHA256_80: 1,
	AES_128_CTR_HMAC_SHA256_64: 2,
	AES_128_CTR_HMAC_SHA256_32: 3,
	AES_128_GCM_SHA256_128: 4,
	AES_256_GCM_SHA512_128: 5,
});

const SUITES = {
	1: { id: 1, gcm: false, hash: 'SHA-256', nh: 32, nka: 16, nk: 48, nt: 10 },
	2: { id: 2, gcm: false, hash: 'SHA-256', nh: 32, nka: 16, nk: 48, nt: 8 },
	3: { id: 3, gcm: false, hash: 'SHA-256', nh: 32, nka: 16, nk: 48, nt: 4 },
	4: { id: 4, gcm: true, hash: 'SHA-256', nh: 32, nk: 16, nt: 16 },
	5: { id: 5, gcm: true, hash: 'SHA-512', nh: 64, nk: 32, nt: 16 },
};

/** Why a frame was dropped, as in the Java CryptoFailure. */
export const Failure = Object.freeze({
	MISSING_KEY: 'MISSING_KEY',
	UNKNOWN_KEY: 'UNKNOWN_KEY',
	AUTHENTICATION: 'AUTHENTICATION',
	REPLAY: 'REPLAY',
	MALFORMED: 'MALFORMED',
	UNSUPPORTED_CODEC: 'UNSUPPORTED_CODEC',
	CONTEXTS_EXHAUSTED: 'CONTEXTS_EXHAUSTED',
});

const NONCE_LENGTH = 12;
const MAX_PARTICIPANT = 0xffffffff;
const MAX_CONTEXT = 0xffff;

/** OBU_FRAME and OBU_SEQUENCE_HEADER, no extension, no size field. */
const AV1_WRAPPER = 6 << 3;
const AV1_KEY_WRAPPER = 1 << 3;

const encoder = new TextEncoder();
const KEY_LABEL = encoder.encode('SFrame 1.0 Secret key ');
const SALT_LABEL = encoder.encode('SFrame 1.0 Secret salt ');

// ---------------------------------------------------------------- SFrame

function concat(...parts) {
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let offset = 0;

	for (const part of parts) {
		out.set(part, offset);
		offset += part.length;
	}

	return out;
}

function bigEndian(value, length) {
	const out = new Uint8Array(length);
	let v = BigInt(value);

	for (let i = length - 1; i >= 0; i--) {
		out[i] = Number(v & 0xffn);
		v >>= 8n;
	}

	return out;
}

function extendedLength(value) {
	if (value < 8n) {
		return 0;
	}

	let length = 0;

	for (let v = value; v > 0n; v >>= 8n) {
		length++;
	}

	return length;
}

/** Encodes an SFrame header (RFC 9605, Section 4.3); KID and CTR are BigInts. */
export function encodeHeader(kid, ctr) {
	const kidLength = extendedLength(kid);
	const ctrLength = extendedLength(ctr);

	let config = kidLength === 0 ? Number(kid) << 4 : 0x80 | (kidLength - 1) << 4;
	config |= ctrLength === 0 ? Number(ctr) : 0x08 | (ctrLength - 1);

	return concat(Uint8Array.of(config), bigEndian(kid, kidLength), bigEndian(ctr, ctrLength));
}

/**
 * Parses an SFrame header in its minimal encoding.
 *
 * @returns {{kid: bigint, ctr: bigint, length: number} | null}
 */
export function parseHeader(bytes, offset = 0) {
	if (bytes.length - offset < 1) {
		return null;
	}

	const config = bytes[offset];
	const values = [];
	let position = offset + 1;

	for (const bits of [config >> 4, config & 0x0f]) {
		if ((bits & 0x08) === 0) {
			values.push(BigInt(bits & 0x07));
			continue;
		}

		const size = (bits & 0x07) + 1;

		if (bytes.length - position < size || bytes[position] === 0) {
			return null;
		}

		let value = 0n;

		for (let i = 0; i < size; i++) {
			value = value << 8n | BigInt(bytes[position + i]);
		}

		if (value < 8n) {
			return null;
		}

		values.push(value);
		position += size;
	}

	return { kid: values[0], ctr: values[1], length: position - offset };
}

function label(prefix, kid, suite) {
	return concat(prefix, bigEndian(kid, 8), bigEndian(suite.id, 2));
}

/** derive_key_salt of RFC 9605, Section 4.4.2. */
export async function deriveKeySalt(suiteId, kid, baseKey) {
	const suite = SUITES[suiteId];
	const ikm = await subtle.importKey('raw', baseKey, 'HKDF', false, ['deriveBits']);
	const hkdf = (info) => ({ name: 'HKDF', hash: suite.hash, salt: new Uint8Array(0), info });

	const key = new Uint8Array(await subtle.deriveBits(hkdf(label(KEY_LABEL, kid, suite)), ikm, suite.nk * 8));
	const salt = new Uint8Array(await subtle.deriveBits(hkdf(label(SALT_LABEL, kid, suite)), ikm, NONCE_LENGTH * 8));

	return { key, salt };
}

/** The derived key of one KID: encrypt and decrypt of RFC 9605, Section 4.4. */
export class SFrameKey {

	static async derive(suiteId, kid, baseKey) {
		const { key, salt } = await deriveKeySalt(suiteId, kid, baseKey);

		return SFrameKey.of(suiteId, kid, key, salt);
	}

	static async of(suiteId, kid, sframeKey, salt) {
		const suite = SUITES[suiteId];
		const sframe = new SFrameKey();

		sframe.suite = suite;
		sframe.kid = kid;
		sframe.salt = salt;

		if (suite.gcm) {
			sframe.aesKey = await subtle.importKey('raw', sframeKey, 'AES-GCM', false, ['encrypt', 'decrypt']);
		}
		else {
			sframe.aesKey = await subtle.importKey('raw', sframeKey.slice(0, suite.nka), 'AES-CTR', false,
				['encrypt', 'decrypt']);
			sframe.authKey = await subtle.importKey('raw', sframeKey.slice(suite.nka),
				{ name: 'HMAC', hash: suite.hash }, false, ['sign']);
		}

		return sframe;
	}

	nonce(ctr) {
		const nonce = this.salt.slice();
		const counter = bigEndian(ctr, 8);

		for (let i = 0; i < 8; i++) {
			nonce[NONCE_LENGTH - 8 + i] ^= counter[i];
		}

		return nonce;
	}

	/** Returns the header followed by the ciphertext and the tag. */
	async encrypt(ctr, metadata, plaintext) {
		const header = encodeHeader(this.kid, ctr);
		const body = await this.seal(this.nonce(ctr), concat(header, metadata), plaintext);

		return concat(header, body);
	}

	/** Returns the plaintext, or null if the ciphertext did not authenticate. */
	async decrypt(ctr, header, metadata, ciphertext) {
		return this.open(this.nonce(ctr), concat(header, metadata), ciphertext);
	}

	async seal(nonce, aad, plaintext) {
		const suite = this.suite;

		if (suite.gcm) {
			return new Uint8Array(await subtle.encrypt(
				{ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, this.aesKey, plaintext));
		}

		const ct = new Uint8Array(await subtle.encrypt(
			{ name: 'AES-CTR', counter: concat(nonce, new Uint8Array(4)), length: 32 }, this.aesKey, plaintext));

		return concat(ct, await this.tag(nonce, aad, ct));
	}

	async open(nonce, aad, ciphertext) {
		const suite = this.suite;

		if (ciphertext.length < suite.nt) {
			return null;
		}

		if (suite.gcm) {
			try {
				return new Uint8Array(await subtle.decrypt(
					{ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, this.aesKey, ciphertext));
			}
			catch (e) {
				return null;
			}
		}

		const ct = ciphertext.subarray(0, ciphertext.length - suite.nt);
		const expected = await this.tag(nonce, aad, ct);
		const actual = ciphertext.subarray(ciphertext.length - suite.nt);

		let difference = 0;

		for (let i = 0; i < suite.nt; i++) {
			difference |= expected[i] ^ actual[i];
		}

		if (difference !== 0) {
			return null;
		}

		return new Uint8Array(await subtle.decrypt(
			{ name: 'AES-CTR', counter: concat(nonce, new Uint8Array(4)), length: 32 }, this.aesKey, ct));
	}

	async tag(nonce, aad, ct) {
		const authData = concat(bigEndian(aad.length, 8), bigEndian(ct.length, 8), bigEndian(this.suite.nt, 8),
			nonce, aad, ct);

		return new Uint8Array(await subtle.sign('HMAC', this.authKey, authData)).slice(0, this.suite.nt);
	}
}

// ---------------------------------------------------------- Frame format

/**
 * KID = context (16 bits) | participant (32 bits) | generation (8 bits) |
 * ratchet step (8 bits). This version always sends a ratchet step of 0, and
 * takes any other step for a key it does not have.
 */
export function keyId(context, participant, generation, ratchetStep = 0) {
	return BigInt(context) << 48n | BigInt(participant) << 16n | BigInt(generation) << 8n | BigInt(ratchetStep);
}

function codecOf(mimeType) {
	const type = (mimeType || '').toLowerCase();

	switch (type) {
		case 'video/vp8': return 'vp8';
		case 'video/vp9': return 'vp9';
		case 'video/av1': return 'av1';
		case 'audio/opus': return 'opus';
		default: return type.startsWith('audio/') ? 'audio' : null;
	}
}

function clearBytes(codec, keyFrame) {
	switch (codec) {
		case 'vp8': return keyFrame ? 10 : 3;
		case 'opus': return 1;
		default: return 0;
	}
}

/** Where the SFrame ciphertext of an AV1 frame starts, or -1. */
function av1PayloadOffset(frame) {
	if (frame.length < 1) {
		return -1;
	}

	const header = frame[0] & ~0x02;

	if (header !== AV1_WRAPPER && header !== AV1_KEY_WRAPPER) {
		return -1;
	}
	if ((frame[0] & 0x02) === 0) {
		return 1;
	}

	let size = 0;
	let position = 1;

	for (let i = 0; i < 8; i++, position++) {
		if (position >= frame.length) {
			return -1;
		}

		const b = frame[position];
		size += (b & 0x7f) * 2 ** (7 * i);

		if ((b & 0x80) === 0) {
			position++;

			return size === frame.length - position ? position : -1;
		}
	}

	return -1;
}

function isNewer(a, b) {
	const distance = (a - b) & 0xff;

	return distance > 0 && distance < 128;
}

class ReplayWindow {

	constructor(size) {
		this.size = BigInt(size);
		this.seen = new Uint32Array(size / 32);
		this.highest = -1n;
	}

	isFresh(ctr) {
		if (ctr > this.highest) {
			return true;
		}

		return this.highest - ctr < this.size && !this.test(ctr);
	}

	accept(ctr) {
		if (ctr > this.highest) {
			if (this.highest < 0n || ctr - this.highest >= this.size) {
				this.seen.fill(0);
			}
			else {
				for (let c = this.highest + 1n; c < ctr; c++) {
					this.set(c, false);
				}
			}

			this.highest = ctr;
			this.set(ctr, true);
			return true;
		}
		if (this.highest - ctr >= this.size || this.test(ctr)) {
			return false;
		}

		this.set(ctr, true);
		return true;
	}

	test(ctr) {
		const bit = Number(ctr % this.size);

		return (this.seen[bit >>> 5] & (1 << (bit & 31))) !== 0;
	}

	set(ctr, value) {
		const bit = Number(ctr % this.size);

		if (value) {
			this.seen[bit >>> 5] |= 1 << (bit & 31);
		}
		else {
			this.seen[bit >>> 5] &= ~(1 << (bit & 31));
		}
	}
}

function equalBytes(a, b) {
	return a.length === b.length && a.every((v, i) => v === b[i]);
}

/**
 * The keys of a call, as the Java KeyProvider holds them: the local
 * participant's encryption key and the others' decryption keys, each of a
 * generation from 0 to 255.
 */
export class KeyProvider {

	/**
	 * @param {object} options
	 * @param {number} options.participant The local participant's ID, 0 to 2^32-1.
	 * @param {number} [options.cipherSuite] A CipherSuite value, AES-128-GCM by default.
	 * @param {number} [options.replayWindow] Frames, a multiple of 32; 0 turns it off.
	 * @param {number} [options.keyRetentionMs] How long older generations decrypt after a newer one did.
	 * @param {function} [options.onFailure] Hears of streams that begin to drop frames.
	 */
	constructor({ participant, cipherSuite = CipherSuite.AES_128_GCM_SHA256_128, replayWindow = 128,
		keyRetentionMs = 5000, onFailure } = {}) {
		if (!Number.isInteger(participant) || participant < 0 || participant > MAX_PARTICIPANT) {
			throw new RangeError('A participant ID is from 0 to 2^32-1');
		}
		if (!SUITES[cipherSuite]) {
			throw new RangeError('Unknown cipher suite ' + cipherSuite);
		}

		this.participant = participant;
		this.suite = cipherSuite;
		this.replayWindow = Math.ceil(replayWindow / 32) * 32;
		this.keyRetentionMs = keyRetentionMs;
		this.onFailure = onFailure;
		this.encryptionKey = null;
		this.participants = new Map();
		this.nextContext = 0;
		this.stats = { encrypted: 0, decrypted: 0, dropped: {} };
	}

	/** A new random key, as long as the suite's hash output. */
	generateKey() {
		return globalThis.crypto.getRandomValues(new Uint8Array(SUITES[this.suite].nh));
	}

	setEncryptionKey(generation, key) {
		checkGeneration(generation);
		this.encryptionKey = { generation, baseKey: copyKey(key), derived: new Map() };
	}

	removeEncryptionKey() {
		this.encryptionKey = null;
	}

	setDecryptionKey(participant, generation, key) {
		checkGeneration(generation);

		if (participant === this.participant) {
			throw new RangeError('A key of the local participant is only for encryption');
		}

		const baseKey = copyKey(key);
		let remote = this.participants.get(participant);

		if (!remote) {
			remote = { keys: new Array(256).fill(null), newest: -1 };
			this.participants.set(participant, remote);
		}

		const current = remote.keys[generation];

		if (current && equalBytes(current.baseKey, baseKey)) {
			current.retireAt = 0;
			return;
		}

		remote.keys[generation] = { generation, baseKey, openers: new Map(), retireAt: 0 };
	}

	removeDecryptionKey(participant, generation) {
		const remote = this.participants.get(participant);

		if (!remote || !remote.keys[generation]) {
			return false;
		}

		remote.keys[generation] = null;
		return true;
	}

	removeParticipant(participant) {
		return this.participants.delete(participant);
	}

	/** @param {function} [keyFrameRequest] Asks the sender for a key frame. */
	createEncryptor(keyFrameRequest) {
		return new FrameEncryptor(this, keyFrameRequest);
	}

	/** @param {function} [keyFrameRequest] Asks the remote side for a key frame. */
	createDecryptor(keyFrameRequest) {
		return new FrameDecryptor(this, keyFrameRequest);
	}

	allocateContext() {
		return this.nextContext > MAX_CONTEXT ? -1 : this.nextContext++;
	}

	decryptionKey(participant, generation, now) {
		const remote = this.participants.get(participant);
		const key = remote ? remote.keys[generation] : null;

		if (key && key.retireAt !== 0 && now > key.retireAt) {
			remote.keys[generation] = null;
			return null;
		}

		return key;
	}

	decryptedWith(participant, key, now) {
		this.stats.decrypted++;

		const remote = this.participants.get(participant);

		if (!remote || remote.newest === key.generation) {
			return;
		}
		if (remote.newest !== -1 && !isNewer(key.generation, remote.newest)) {
			return;
		}

		remote.newest = key.generation;

		for (const other of remote.keys) {
			if (other && other !== key && other.retireAt === 0 && isNewer(key.generation, other.generation)) {
				other.retireAt = now + this.keyRetentionMs;
			}
		}
	}
}

function checkGeneration(generation) {
	if (!Number.isInteger(generation) || generation < 0 || generation > 255) {
		throw new RangeError('A key generation is from 0 to 255');
	}
}

function copyKey(key) {
	const copy = new Uint8Array(key);

	if (copy.length < 16) {
		throw new RangeError('A key has at least 16 bytes');
	}

	return copy;
}

class FrameCryptor {

	constructor(keys, keyFrameRequest) {
		this.keys = keys;
		this.keyFrameRequest = keyFrameRequest;
		this.streams = new Map();
	}

	stream(ssrc) {
		let stream = this.streams.get(ssrc);

		if (!stream) {
			stream = { ssrc, context: -1, counter: 0n, generation: -1, failure: null, interrupted: false,
				keyFramePending: false, lastKeyFrameRequest: 0 };
			this.streams.set(ssrc, stream);
		}

		return stream;
	}

	fail(stream, failure, participant, generation, info) {
		const dropped = this.keys.stats.dropped;
		dropped[failure] = (dropped[failure] || 0) + 1;

		if (failure !== Failure.REPLAY) {
			stream.interrupted = true;
		}
		if (stream.failure !== failure) {
			stream.failure = failure;

			if (this.keys.onFailure) {
				this.keys.onFailure({ failure, outgoing: this.outgoing, participant, generation,
					ssrc: stream.ssrc, mimeType: info.mimeType });
			}
		}

		return null;
	}

	succeed(stream, info, needsKeyFrame) {
		const wanted = needsKeyFrame || stream.interrupted;

		stream.failure = null;
		stream.interrupted = false;

		if (!info.video || info.keyFrame) {
			stream.keyFramePending = false;
			return;
		}
		if (wanted) {
			stream.keyFramePending = true;
		}

		const now = Date.now();

		if (stream.keyFramePending && this.keyFrameRequest && now - stream.lastKeyFrameRequest >= 500) {
			stream.lastKeyFrameRequest = now;

			Promise.resolve().then(this.keyFrameRequest).catch(() => { });
		}
	}

	/**
	 * Transforms one RTCEncodedVideoFrame or RTCEncodedAudioFrame in a
	 * TransformStream: enqueues it changed, or drops it.
	 */
	async transformFrame(frame, controller) {
		const metadata = frame.getMetadata();
		const info = {
			ssrc: metadata.synchronizationSource,
			mimeType: metadata.mimeType,
			video: frame.type !== undefined,
			keyFrame: frame.type === 'key',
		};

		const out = await this.process(info, new Uint8Array(frame.data));

		if (out) {
			frame.data = out.buffer.slice(out.byteOffset, out.byteOffset + out.byteLength);
			controller.enqueue(frame);
		}
	}
}

class FrameEncryptor extends FrameCryptor {

	get outgoing() {
		return true;
	}

	/**
	 * @param {{ssrc: number, mimeType: string, video: boolean, keyFrame: boolean}} info
	 * @param {Uint8Array} frame
	 * @returns {Promise<Uint8Array|null>} The encrypted frame, or null to drop it.
	 */
	async process(info, frame) {
		const keys = this.keys;
		const stream = this.stream(info.ssrc);

		if (stream.context < 0) {
			stream.context = keys.allocateContext();

			if (stream.context < 0) {
				return this.fail(stream, Failure.CONTEXTS_EXHAUSTED, keys.participant, -1, info);
			}
		}

		const key = keys.encryptionKey;

		if (!key) {
			return this.fail(stream, Failure.MISSING_KEY, keys.participant, -1, info);
		}

		const codec = codecOf(info.mimeType);

		if (!codec) {
			return this.fail(stream, Failure.UNSUPPORTED_CODEC, keys.participant, key.generation, info);
		}

		const clear = clearBytes(codec, info.keyFrame);

		if (frame.length < clear) {
			return this.fail(stream, Failure.MALFORMED, keys.participant, key.generation, info);
		}

		const prefix = codec === 'av1'
			? Uint8Array.of(info.keyFrame ? AV1_KEY_WRAPPER : AV1_WRAPPER)
			: frame.slice(0, clear);

		let sframe = key.derived.get(stream.context);

		if (!sframe) {
			sframe = SFrameKey.derive(keys.suite, keyId(stream.context, keys.participant, key.generation),
				key.baseKey);
			key.derived.set(stream.context, sframe);
		}

		// Never reset, not even by a new key.
		const ctr = stream.counter++;
		const sealed = await (await sframe).encrypt(ctr, prefix, frame.subarray(clear));

		const newKey = stream.generation !== -1 && stream.generation !== key.generation;
		stream.generation = key.generation;

		keys.stats.encrypted++;
		this.succeed(stream, info, newKey);

		return concat(prefix, sealed);
	}
}

class FrameDecryptor extends FrameCryptor {

	get outgoing() {
		return false;
	}

	/**
	 * @param {{ssrc: number, mimeType: string, video: boolean, keyFrame: boolean}} info
	 * @param {Uint8Array} frame
	 * @returns {Promise<Uint8Array|null>} The decrypted frame, or null to drop it.
	 */
	async process(info, frame) {
		const keys = this.keys;
		const stream = this.stream(info.ssrc);
		const codec = codecOf(info.mimeType);

		if (!codec) {
			return this.fail(stream, Failure.UNSUPPORTED_CODEC, -1, -1, info);
		}

		let clear = 0;
		let headerOffset;
		let metadata;

		if (codec === 'av1') {
			headerOffset = av1PayloadOffset(frame);

			if (headerOffset < 0) {
				return this.fail(stream, Failure.MALFORMED, -1, -1, info);
			}

			metadata = Uint8Array.of(frame[0] & ~0x02);
		}
		else {
			clear = clearBytes(codec, info.keyFrame);
			headerOffset = clear;
			metadata = frame.subarray(0, clear);
		}

		const header = headerOffset <= frame.length ? parseHeader(frame, headerOffset) : null;

		if (!header) {
			return this.fail(stream, Failure.MALFORMED, -1, -1, info);
		}

		const participant = Number(header.kid >> 16n & 0xffffffffn);
		const generation = Number(header.kid >> 8n & 0xffn);
		const ratchetStep = Number(header.kid & 0xffn);
		const context = Number(header.kid >> 48n);
		const now = Date.now();

		// A key moved forward by the ratchet is one this version never has.
		const key = ratchetStep === 0 ? keys.decryptionKey(participant, generation, now) : null;

		if (!key) {
			return this.fail(stream, Failure.UNKNOWN_KEY, participant, generation, info);
		}

		let opener = key.openers.get(context);

		if (!opener) {
			opener = SFrameKey.derive(keys.suite, header.kid, key.baseKey).then((sframe) => ({
				sframe, window: keys.replayWindow > 0 ? new ReplayWindow(keys.replayWindow) : null,
			}));
			key.openers.set(context, opener);
		}

		const { sframe, window } = await opener;

		if (window && !window.isFresh(header.ctr)) {
			return this.fail(stream, Failure.REPLAY, participant, generation, info);
		}

		const headerBytes = frame.subarray(headerOffset, headerOffset + header.length);
		const plaintext = await sframe.decrypt(header.ctr, headerBytes, metadata,
			frame.subarray(headerOffset + header.length));

		if (!plaintext) {
			return this.fail(stream, Failure.AUTHENTICATION, participant, generation, info);
		}
		if (window && !window.accept(header.ctr)) {
			return this.fail(stream, Failure.REPLAY, participant, generation, info);
		}

		keys.decryptedWith(participant, key, now);
		this.succeed(stream, info, false);

		return concat(frame.subarray(0, clear), plaintext);
	}
}

/**
 * Pipes the frames of an RTCRtpScriptTransformer through an encryptor or
 * decryptor of the key provider, by the transformer's options.side ('send'
 * or 'receive'), with key frame requests going to the transformer.
 */
export function attach(keys, transformer) {
	const send = transformer.options && transformer.options.side === 'send';
	const cryptor = send
		? keys.createEncryptor(() => transformer.generateKeyFrame())
		: keys.createDecryptor(() => transformer.sendKeyFrameRequest());

	return transformer.readable
		.pipeThrough(new TransformStream({ transform: (frame, controller) => cryptor.transformFrame(frame, controller) }))
		.pipeTo(transformer.writable);
}
