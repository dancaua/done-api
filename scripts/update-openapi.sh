#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
classpath_file="$(mktemp)"
trap 'rm -f "$classpath_file"' EXIT
./mvnw -B -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile="$classpath_file"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "target/classes:target/test-classes:$(cat "$classpath_file")" org.adancau.doneapi.OpenApiGenerator
