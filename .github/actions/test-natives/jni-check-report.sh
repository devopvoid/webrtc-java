#!/usr/bin/env bash
#
# Evaluates the output of a test run under -Xcheck:jni. The JVM writes its
# diagnostics straight to native stdout, one block each: the message, then the
# Java stack of the thread (or its native frames on a thread without Java
# frames). Fails on any fatal error, and on more warnings than allowed.
#
# Usage: jni-check-report.sh <log> [max-warnings]
#
# A summary groups the warnings by the JNI function whose exception was left
# unchecked and by the native method the warning was raised in, which is where
# to look for the missing check. On failure, the first diagnostics with their
# stacks go to jni-check-excerpt.txt, since the whole log can grow to hundreds
# of megabytes.

set -euo pipefail

log="$1"
max_warnings="${2:-0}"

fatal=$(grep -c 'FATAL ERROR in native method' "$log" || true)
warnings=$(grep -cE 'WARNING in native method|WARNING: JNI local refs' "$log" || true)

summary=$(awk '
	# The JVM on Windows ends some lines with CRLF.
	{ sub(/\r$/, "") }
	function flush() {
		if (kind != "") {
			key = kind " | " (frame != "" ? frame : (native ? "<thread without Java frames>" : "<no stack>"))
			count[key]++
		}
		kind = ""
	}
	/WARNING in native method|WARNING: JNI local refs/ {
		flush()
		kind = $0
		sub(/.*when required to from /, "", kind)
		sub(/.*WARNING in native method: /, "", kind)
		sub(/.*WARNING: /, "", kind)
		gsub(/[0-9]+/, "N", kind)
		frame = ""
		native = 0
		next
	}
	kind != "" && /^\tat / {
		if (frame == "" && index($0, "Native Method)") > 0) {
			frame = $0
			sub(/^\tat /, "", frame)
			sub(/\(.*$/, "", frame)
		}
		next
	}
	kind != "" && /^\t- / { next }
	kind != "" && (/^Native frames/ || /^[VCvjJ]  /) { native = 1; next }
	kind != "" { flush() }
	END {
		flush()
		for (key in count) printf "%8d  %s\n", count[key], key
	}
' "$log" | sort -rn | head -n 25)

echo "-Xcheck:jni: $fatal fatal errors, $warnings warnings (at most $max_warnings allowed)"

if [ -n "$summary" ]; then
	echo "Warnings by unchecked JNI function and native method:"
	echo "$summary"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
	{
		echo "### -Xcheck:jni"
		echo
		echo "$fatal fatal errors, $warnings warnings (at most $max_warnings allowed)."
		if [ -n "$summary" ]; then
			echo
			echo '```'
			echo "$summary"
			echo '```'
		fi
	} >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$fatal" -gt 0 ] || [ "$warnings" -gt "$max_warnings" ]; then
	grep -n -A 25 -m 100 -E 'FATAL ERROR in native method|WARNING in native method|WARNING: JNI local refs' "$log" \
		> jni-check-excerpt.txt || true
	exit 1
fi
