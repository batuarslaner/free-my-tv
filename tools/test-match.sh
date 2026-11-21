#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/match-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/match-tests \
  app/src/io/freemytv/WatchMatch.java tests/WatchMatchTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dfile.encoding=UTF-8 -cp out/match-tests io.freemytv.WatchMatchTest
