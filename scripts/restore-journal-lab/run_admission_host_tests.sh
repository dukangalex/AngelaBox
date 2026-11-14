#!/usr/bin/env bash
# Host-only gate/journal tests. Not Android/Room/native service acceptance.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${KOTLIN_HOME:?Set KOTLIN_HOME to a Kotlin installation containing coroutines}"
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to Hamcrest core 1.3}"
OUT=${1:?Output jar path required}
CP="$JUNIT_JAR:$HAMCREST_JAR:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
cd "$ROOT"
SOURCES=(); CLASSES=()
for name in RestoreAccessGate RestoreCoroutineOperation RestoreRecoveryJournal ServiceOperationGate ProfileFiles; do
  SOURCES+=("app/src/main/java/io/nekohasekai/sfa/utils/$name.kt")
done
for name in RestoreAccessGateTest RestoreSnapshotTest RestoreCoroutineOperationTest RestoreRecoveryJournalTest RestoreStartupRecoveryTest ServiceOperationGateTest ProfileFilesAdmissionTest; do
  SOURCES+=("app/src/test/java/io/nekohasekai/sfa/utils/$name.kt")
  CLASSES+=("io.nekohasekai.sfa.utils.$name")
done
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 17 -cp "$CP" "${SOURCES[@]}" -include-runtime -d "$OUT"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
