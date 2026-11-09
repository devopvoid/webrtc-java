// Fails when the API listings of the Client API Design page and the client API
// have drifted apart, since the page's signatures are prose that no compiler sees.
//
// For each type the page lists in a ```java block, it compares the member names
// with the public members of the type's source: methods, including those of nested
// builders, and enum constants. It also fails for a public type of the client API
// that the page does not name. Parameters and return types are not compared.
//
// Runs before every docs build (npm "prebuild"), or by itself with
// `npm run check:design`.

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const docs = join(dirname(fileURLToPath(import.meta.url)), '..')
const page = join(docs, 'guide', 'client-api-design.md')
const sources = join(docs, '..', 'webrtc', 'src', 'main', 'java', 'dev', 'onvoid', 'webrtc', 'client')
const packages = [sources, join(sources, 'transfer')]

const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'return', 'super', 'this', 'catch', 'synchronized', 'new'])
const IGNORED = new Set(['equals', 'hashCode', 'toString'])

if (!existsSync(sources)) {
	fail([`The sources of the client API are not at ${sources}.`])
}

const read = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
const stripComments = (code) => code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')

// A member declaration: optional modifiers and type parameters, a return type, then the name and "(".
const METHOD = /^\s*(?:(?:public|protected|default|static|final|abstract|synchronized)\s+)*(?:<[^>]+>\s+)?[\w<>?,[\]. ]+?\s(\w+)\s*\(/gm

function names(regex, text) {
	return new Set([...text.matchAll(regex)].map((match) => match[1]))
}

function documentedTypes(markdown) {
	const code = [...markdown.matchAll(/```java\n([\s\S]*?)```/g)].map((match) => match[1]).join('\n')
	const types = new Map()

	for (const match of code.matchAll(/^public (?:final |abstract )*(class|interface|enum) (\w+)[^\n]*\{\n([\s\S]*?)^\}/gm)) {
		const [, kind, name, body] = match
		const entry = types.get(name) ?? { kind, body: '' }
		entry.body += body
		types.set(name, entry)
	}

	return types
}

function sourceOf(type) {
	for (const directory of packages) {
		const file = join(directory, `${type}.java`)

		if (existsSync(file)) {
			return stripComments(read(file))
		}
	}

	return null
}

function publicMembers(type, source) {
	if (new RegExp(`\\benum ${type}\\b`).test(source)) {
		return names(/^\t([A-Z][A-Z0-9_]*)\b/gm, source)
	}
	if (new RegExp(`\\binterface ${type}\\b`).test(source)) {
		// Members of an interface are public without saying so; they sit one tab in.
		return names(/^\t(?:(?:default|static)\s+)?(?:<[^>]+>\s+)?[\w<>?,[\]. ]+?\s(\w+)\s*\([^)]*\)[^;{]*[;{]/gm, source)
	}

	return names(/^\s*public\s+(?:(?:static|final|synchronized)\s+)*(?:<[^>]+>\s+)?[\w<>?,[\]. ]+?\s(\w+)\s*\(/gm, source)
}

function documentedMembers(kind, body) {
	const code = stripComments(body)

	if (kind === 'enum') {
		return names(/\b([A-Z][A-Z0-9_]{2,})\b/g, code)
	}

	const members = names(METHOD, code)
	KEYWORDS.forEach((keyword) => members.delete(keyword))
	return members
}

function publicTypes() {
	const types = []

	for (const directory of packages) {
		for (const file of readdirSync(directory).filter((name) => name.endsWith('.java'))) {
			const type = file.slice(0, -'.java'.length)

			if (new RegExp(`^public [^\\n]*\\b(class|interface|enum) ${type}\\b`, 'm').test(read(join(directory, file)))) {
				types.push(type)
			}
		}
	}

	return types
}

function fail(problems) {
	console.error('The Client API Design page does not match the client API:\n')
	problems.forEach((problem) => console.error(`  - ${problem}`))
	console.error('\nUpdate the listings of docs/guide/client-api-design.md to the code.')
	process.exit(1)
}

const markdown = read(page)
const problems = []

for (const [type, { kind, body }] of documentedTypes(markdown)) {
	const source = sourceOf(type)

	if (source === null) {
		problems.push(`${type} is listed, but there is no such type in the client API.`)
		continue
	}

	const real = publicMembers(type, source)
	const listed = documentedMembers(kind, body)
	const onlyListed = [...listed].filter((name) => !real.has(name))
	const missing = [...real].filter((name) => !listed.has(name) && !IGNORED.has(name) && name !== type)

	if (onlyListed.length > 0) {
		problems.push(`${type} lists what the code does not have: ${onlyListed.sort().join(', ')}`)
	}
	if (missing.length > 0) {
		problems.push(`${type} does not list: ${missing.sort().join(', ')}`)
	}
}

for (const type of publicTypes()) {
	if (!new RegExp(`\\b${type}\\b`).test(markdown)) {
		problems.push(`${type} is a public type of the client API that the page does not name.`)
	}
}

if (problems.length > 0) {
	fail(problems)
}

console.log(`The Client API Design page matches the client API (${documentedTypes(markdown).size} types).`)
