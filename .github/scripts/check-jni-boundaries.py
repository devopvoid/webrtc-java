#!/usr/bin/env python3
"""Require a whole-body exception boundary on every core JNI entry point.

Comments and literals are masked before matching braces. Inner try blocks do not
protect statements before/after them. Media has its own independent JNI helpers.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKEN = re.compile(r'//[^\n]*|/\*.*?\*/|R"([^ ()\\\t\r\n]{0,16})\(.*?\)\1"|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
ENTRY = re.compile(r'JNIEXPORT\s+(\w+)\s+JNICALL\s+(Java_\w+)\s*\([^)]*\)\s*\{')


def mask(source):
    return TOKEN.sub(lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), source)


def close_brace(source, start):
    depth = 1
    for pos in range(start + 1, len(source)):
        if source[pos] == '{':
            depth += 1
        elif source[pos] == '}':
            depth -= 1
            if depth == 0:
                return pos
    raise ValueError('Unbalanced JNI function body')


def entries(source):
    clean = mask(source)
    for match in ENTRY.finditer(clean):
        start = match.end() - 1
        end = close_brace(clean, start)
        yield match, start, end, clean[start + 1:end]


def protected(body):
    # A complete CallFromJava invocation, with no work outside the boundary.
    if re.fullmatch(r'\s*(?:return\s+)?jni::CallFromJava\s*\(env,\s*\[&\]\(\)\s*(?:->\s*\w+\s*)?\{.*\}\);\s*', body, re.S):
        opening = body.index('{')
        return body[close_brace(body, opening) + 1:].strip() == ');'
    # A top-level try followed by catch (...), translating the exception.
    if not re.match(r'\s*try\s*\{', body):
        return False
    pos = close_brace(body, body.index('{')) + 1
    catch_all = False
    while body[pos:].strip():
        match = re.match(r'\s*catch\s*\(([^)]*)\)\s*\{', body[pos:])
        if not match:
            return False
        opening = pos + match.end() - 1
        end = close_brace(body, opening)
        if match[1].strip() == '...':
            catch_all = bool(re.search(r'\bThrowCxxJavaException\(env\)', body[opening:end]))
        pos = end + 1
    return catch_all


def main():
    errors = []
    count = 0
    for path in sorted((ROOT / 'webrtc-jni/src/main/cpp').rglob('*.cpp')):
        for match, start, end, body in entries(path.read_text()):
            count += 1
            if not protected(body):
                line = path.read_text()[:start].count('\n') + 1
                errors.append(f'{path.relative_to(ROOT)}:{line}: {match[2]} lacks a whole-body exception boundary')
    print('\n'.join(errors) if errors else f'Checked {count} JNI entry points: all have exception boundaries.')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
