// Runs the browser implementation of the frame format (docs/public/e2ee) in
// Node.js for BrowserInteropTests: checks it against the RFC 9605 test
// vectors, decrypts the frames the Java side encrypted, and encrypts the
// same frames for the Java side to decrypt.
//
//   node sframe-interop.mjs <module> <vectors> <frames>
//
// The frames file has one item per line:
//   suite <id>
//   javaKey <participant> <hex>     the key the Java side encrypts with
//   jsKey <participant> <hex>       the key this side encrypts with
//   frame <mime> <key 0|1> <ssrc> <plain hex> <sealed hex>
//
// Prints "vector <n> ok|fail", "decrypt <n> ok|fail <why>" and
// "sealed <n> <hex>", then "done".

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [moduleFile, vectorsFile, framesFile] = process.argv.slice(2);
const sframe = await import(pathToFileURL(moduleFile).href);

const hex = (s) => Uint8Array.from(s.match(/../g) || [], (b) => parseInt(b, 16));
const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

// The SFrame vectors of RFC 9605, Appendix C.3.
let index = 0;

for (const line of readFileSync(vectorsFile, 'utf8').split('\n')) {
	if (!line.startsWith('section=sframe')) {
		continue;
	}

	const v = Object.fromEntries(line.trim().split(' ').map((field) => field.split('=')));
	const suite = parseInt(v.cipher_suite, 16);
	const key = await sframe.SFrameKey.derive(suite, BigInt('0x' + v.kid), hex(v.base_key));
	const ct = await key.encrypt(BigInt('0x' + v.ctr), hex(v.metadata), hex(v.pt));

	const header = sframe.parseHeader(ct);
	const pt = await key.decrypt(header.ctr, ct.subarray(0, header.length), hex(v.metadata),
		ct.subarray(header.length));

	console.log(`vector ${index++} ${toHex(ct) === v.ct && pt && toHex(pt) === v.pt ? 'ok' : 'fail'}`);
}

// The frames of the Java side.
const failures = [];
let keys;
let frameIndex = 0;
const sealed = [];

for (const line of readFileSync(framesFile, 'utf8').split('\n')) {
	const fields = line.trim().split(' ');

	switch (fields[0]) {
		case 'suite':
			keys = { suite: parseInt(fields[1], 10) };
			break;
		case 'javaKey':
			keys.java = { participant: parseInt(fields[1], 10), key: hex(fields[2]) };
			break;
		case 'jsKey': {
			keys.js = { participant: parseInt(fields[1], 10), key: hex(fields[2]) };

			keys.provider = new sframe.KeyProvider({ participant: keys.js.participant, cipherSuite: keys.suite,
				onFailure: (event) => failures.push(event.failure) });
			keys.provider.setEncryptionKey(0, keys.js.key);
			keys.provider.setDecryptionKey(keys.java.participant, 0, keys.java.key);
			keys.encryptor = keys.provider.createEncryptor();
			keys.decryptor = keys.provider.createDecryptor();
			break;
		}
		case 'frame': {
			const [, mimeType, key, ssrc, plain, encrypted] = fields;
			const info = { mimeType, keyFrame: key === '1', video: mimeType.startsWith('video/'), ssrc: Number(ssrc) };

			const decrypted = await keys.decryptor.process(info, hex(encrypted));
			const ok = decrypted && toHex(decrypted) === plain;

			console.log(`decrypt ${frameIndex} ${ok ? 'ok' : 'fail ' + (failures.at(-1) || 'mismatch')}`);

			const out = await keys.encryptor.process(info, hex(plain));
			console.log(`sealed ${frameIndex} ${out ? toHex(out) : '-'}`);

			frameIndex++;
			break;
		}
		default:
			break;
	}
}

console.log('done');
