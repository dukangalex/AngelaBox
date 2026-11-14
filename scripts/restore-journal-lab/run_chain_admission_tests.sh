#!/usr/bin/env bash
# Host-only: real ChainBindings/gates, fake Settings. No Android or Room evidence.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${KOTLIN_HOME:?}" "${JUNIT_JAR:?}" "${HAMCREST_JAR:?}" "${JSON_JAR:?}"
OUT=${1:?Output jar path required}
CP="$JUNIT_JAR:$HAMCREST_JAR:$JSON_JAR:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
cd "$ROOT"
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 17 -cp "$CP" \
  app/src/main/java/io/nekohasekai/sfa/utils/RestoreAccessGate.kt \
  app/src/main/java/io/nekohasekai/sfa/utils/RestoreCoroutineOperation.kt \
  app/src/main/java/io/nekohasekai/sfa/chain/ChainBindings.kt \
  scripts/restore-journal-lab/chain-admission/*.kt \
  app/src/test/java/io/nekohasekai/sfa/chain/ChainBinding*Test.kt \
  -include-runtime -d "$OUT"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
 io.nekohasekai.sfa.chain.ChainAdmissionTest \
 io.nekohasekai.sfa.chain.ChainBindingStoreTest \
 io.nekohasekai.sfa.chain.ChainBindingReferencingTest \
 io.nekohasekai.sfa.chain.ChainBindingRobustnessTest
