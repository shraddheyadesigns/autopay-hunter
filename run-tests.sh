#!/bin/sh
# Compiles the engine with plain kotlinc (no Gradle needed) and runs every check:
#   1. testdata/corpus.txt   the main test inbox: every SMS + every subscription + user marks
#   2. testdata/holdout.txt  wordings the rules weren't tuned on
#   3. noise                 the main inbox buried in 5,000 junk SMS must give the same result
# Needs a JDK and the Kotlin compiler (kotlinc). Override with KOTLINC=... and KOTLIN_STDLIB=... if yours live elsewhere.
cd "$(dirname "$0")"
KOTLINC="${KOTLINC:-kotlinc}"
if [ -z "$KOTLIN_STDLIB" ]; then
  KOTLIN_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")"
  KOTLIN_STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
fi
STDLIB="$KOTLIN_STDLIB"
CP="build/classes:$STDLIB"
JAVA="java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp $CP"

rm -rf build/classes && mkdir -p build/classes
$KOTLINC engine/src/main/kotlin engine/src/test/kotlin engine/src/cli/kotlin -nowarn -d build/classes 2>&1 | grep -v -e JAVA_TOOL_OPTIONS -e "^warning"

fail=0
echo "1. Main test inbox"
$JAVA app.autopayhunter.engine.CorpusCheck testdata/corpus.txt --quiet 2>/dev/null || fail=1
echo "2. Holdout inbox"
$JAVA app.autopayhunter.engine.CorpusCheck testdata/holdout.txt --quiet 2>/dev/null || fail=1

echo "3. Buried in noise"
[ -f testdata/noise.txt ] || (cd testdata && python3 make_noise.py >/dev/null)
$JAVA app.autopayhunter.cli.CliKt testdata/corpus.txt --today 2026-11-15 2>/dev/null | grep -E '^(\*|==|\?)' > build/clean.txt
$JAVA app.autopayhunter.cli.CliKt testdata/corpus.txt testdata/noise.txt --today 2026-11-15 2>/dev/null | grep -E '^(\*|==|\?)' > build/noisy.txt
if diff build/clean.txt build/noisy.txt >/dev/null; then echo "ALL CHECKS PASSED (5,000 noise SMS added nothing)"
else echo "FAILED: noise changed the result"; diff build/clean.txt build/noisy.txt; fail=1; fi

exit $fail
