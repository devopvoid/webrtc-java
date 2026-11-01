#!/usr/bin/env bash
# Run from the repository root after packaging the Java jars and unpacking natives.
set -euo pipefail
version=${1:?Usage: check.sh PROJECT_VERSION}
output="$PWD/target/consumers"
mkdir -p "$output/classpath" "$output/modulepath"

jars=("$PWD/webrtc/target/webrtc-java-$version.jar"
      "$PWD/webrtc-media/target/webrtc-java-media-$version.jar"
      "$PWD/webrtc-javafx/target/webrtc-java-javafx-$version.jar")
# These checks run on Linux. Use the real JavaFX modules, not its empty
# platform-neutral jars (which would introduce duplicate automatic modules).
for component in base graphics; do
    matches=(webrtc-javafx/target/dependency/javafx-"$component"-*-linux.jar)
    if [[ ${#matches[@]} != 1 || ! -f ${matches[0]} ]]; then
        echo "Expected one Linux JavaFX $component jar" >&2
        exit 1
    fi
    jars+=("$PWD/${matches[0]}")
done
for jar in "${jars[@]}"; do test -f "$jar"; done
libraries=$(IFS=:; echo "${jars[*]}")
natives="$PWD/webrtc-media/target/natives"
source=.github/consumers/src/consumer/Smoke.java

javac --release 11 -cp "$libraries" -d "$output/classpath" "$source"
java -cp "$output/classpath:$libraries:$natives" consumer.Smoke

javac --release 11 --module-path "$libraries" -d "$output/modulepath" \
    .github/consumers/modulepath/module-info.java "$source"
java --module-path "$output/modulepath:$libraries" -cp "$natives" \
    --module webrtc.consumer/consumer.Smoke
