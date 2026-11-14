# Restore journal core — NOT integrated into application restore

This branch continues `29734d2b` with a durable preimage/replay component. It does
not replace `BackupManager`'s current restore path. The candidate APK previously
built from `421532a1` does not contain this component or the staging fix.

## Contract and scope

`RestoreRecoveryJournal` has fixed app-private targets: both Room database files,
their WAL/SHM/rollback journal sidecars, and the entire private `files/configs`
directory. No path from a backup is used to choose the target set. A preparing
session creates checksummed immutable preimages and publishes a synced PREPARED
marker before allowing mutations. An interrupted, uncommitted operation is
rolled back on recovery; valid COMMITTED keeps the new state. All preimages are
validated before the first target is changed. Recovery can be repeated after an
interruption, and retirement is synced before deleting preimages. Malformed
markers/manifest, missing or corrupt preimages, symlinks and another journal
owner fail closed. A reopened session cannot commit interrupted writes.

The file lock coordinates **only journal owners**. Before calling prepare and
until commit/rollback/cleanup is finished, the caller has to stop all existing
DB/config users and exclude new ones, including service start, UI import/edit,
workers, receivers and native callbacks. Snapshot sources must be SQLite-valid,
quiescent files; copying a live DB and WAL is not a snapshot operation. Recovery
must finish before any provider/application/service opens these files. A failure
must keep access blocked, not catch-and-continue into normal application startup.
Commit failure has an unknown outcome: retain the journal and let recovery choose
from its durable marker rather than retrying restore. Cleanup failure after a
successful commit is committed-with-cleanup-pending, not restore failure.

`AndroidRestoreDurability` uses public API 21 `Os` primitives and compiles against
Android API 24. It is **not executed on Android**. Directory fsync support and
failure behavior on actual device filesystems remain to be tested. Kotlin/JDK
compilation with android.jar is not a complete app build or Android verification.

## Host checks

Use JDK 17+ and Kotlin compiler 2.x; the probe has no external dependencies. From
the repository root, with `KOTLINC` pointing to a compiler binary:

```sh
"$KOTLINC" app/src/main/java/io/nekohasekai/sfa/utils/RestoreRecoveryJournal.kt \
  scripts/restore-journal-lab/CrashProbe.kt -include-runtime -d /tmp/restore-probe.jar
python3 scripts/restore-journal-lab/process_crash_test.py /tmp/restore-probe.jar
```

`RestoreRecoveryJournalTest` adds 16 JUnit4 host tests, including all-target replay,
missing/corrupt preimages, invalid markers, lock contention, symlink rejection,
cleanup fsync failure, root-directory fsync ordering and reopened-commit refusal.
The standalone probe performs a hard JVM halt (no finally/close) after each of nine
rollback targets, then opens another JVM and verifies full and repeated recovery.
It uses synthetic target bytes, **not SQLite databases**. It tests interruption
after each completed target, not every byte-copy boundary. Neither these checks
nor the prior host SQLite WAL experiment test Android lifecycle, concurrent
writers, disk exhaustion, physical power loss or durability of a device.

## Integration still blocked

- `BackupManager` sends `BoxService.stop()`, sleeps 350 ms and closes the two Room
  wrappers; the delay is not a stop acknowledgement or access barrier.
- `BoxService.stopService()` launches asynchronous shutdown; `closeService()` can
  catch a close error and log it while shutdown proceeds. A superficial observer
  of `Status.Stopped` alone must not be treated as proof every native access ended.
- `Settings` and `ProfileManager` reopen databases lazily and their existing
  operations do not participate in a single restore admission gate. Configuration
  paths are also read/written from import UI, editor/import handlers and workers.
- Startup initialization launches readers asynchronously. Recovery must be placed
  before those readers, including any earlier ContentProvider accesses, and errors
  must have a safe recovery UI/path. That has not been implemented.
- Portable/compatibility restore and retained sensitive settings need the same
  transaction boundary. No Android changes to these paths have been made here.

The next integration step is an app-wide restore admission/drain protocol and an
error-propagating acknowledged native service stop; only then close/checkpoint,
prepare, replace, validate, commit, reopen, and run failure/restart tests on the
exact Android build. No threshold has been relaxed. Full audit, Android
acceptance, safe side-by-side/upgrade install and Alex's version-specific release
approval remain outstanding. No release or install was performed by this work.

## Admission/startup continuation (2026-11-14)

The `wabi/restore-admission-startup-20261114` branch adds synchronous, reentrant
operation admission and fail-closed initialization before providers (`Application.attachBaseContext`).
Settings and ProfileManager DAO facades enter admission for each synchronous database operation;
cached facades do not bypass it. `exclusive` is intentionally **not** used by BackupManager yet.
This is an experimental partial integration, not a restore transaction or a safe trial candidate.

Host evidence: RestoreAccessGateTest (10), RestoreStartupRecoveryTest (4), and the existing
RestoreRecoveryJournalTest (16) pass together. The SQLite startup harness
`sqlite_startup_test.py` verifies two real but **closed** host SQLite snapshots and configs
through nine hard-halt/replay points, including SQLite integrity checks. It is not evidence
for Android Room, concurrent WAL writers, native service shutdown, or power loss.
Build a probe jar from RestoreAccessGate.kt, RestoreRecoveryJournal.kt, CrashProbe.kt and
StartupProbe.kt, then pass it to `python3 scripts/restore-journal-lab/sqlite_startup_test.py JAR`.

Before enabling prepare/commit on the live restore path:
- Admit/drain all direct config-file IO, not just DAO operations; audit the remaining direct paths.
- Establish acknowledged native/service quiescence, including in-flight and abandoned starts;
  Stopped UI status and fixed delays are not a shutdown acknowledgment.
- Close/checkpoint SQLite under exclusive ownership, establish a consistent source snapshot,
  and reopen only after verified commit or recovery. Do not copy live DB/WAL pairs.
- Keep the exclusive lease through all database/config replacement and rollback; a failure
  must poison ordinary admission until recovery, not reopen mixed state.
- Validate cold startup/provider ordering, locked-device handling, Android filesystem durability,
  real Room/WAL behavior, errors and process kills on Android before trial delivery.

The current startup integration has not passed an Android build/device test. Do not merge or
ship this branch solely on the host tests. Safe ARM same-package acceptance, full audit, and
installation without replacing the existing beta data remain separate gates.

## Service drain / direct profile IO experiment (2026-11-14)

The follow-on `wabi/restore-service-drain-20261114` branch tracks start/reload
admission before dispatch, native start threads (including timed-out abandoned
workers), native close workers, and asynchronous service shutdown. Close errors
are retained, not inferred away from UI status. `BoxService.quiesceForRestore`
closes start/reload admission and waits for tracked work and native resource
owners; it is intentionally **not called by BackupManager**. Host gate tests
exercise counter/drain/failure semantics only, not actual Android/native shutdown.

Known profile content reads/writes in editor/provider/chain/import/dashboard,
sharing/portable export, background updates/warmup and BoxService now use
`ProfileFiles` data admission. Atomic writers use unique sibling scratch files
so concurrent admitted writers cannot rename or delete each other's scratch.
This does not yet protect complete logical operations that span DAO/path lookup,
network suspension, file IO and DAO mutation. `BackupManager` direct directory
operations are also not migrated. Do NOT enable exclusive replacement solely
because individual IO methods are guarded.

Host evidence: 46 tests in ServiceOperationGateTest (13), ProfileFilesAdmissionTest
(3), RestoreAccessGateTest (10), RestoreRecoveryJournalTest (16) and
RestoreStartupRecoveryTest (4) pass together; they cover concurrent whole-profile
writes and close/timeout/abandoned-worker bookkeeping. The whitelist guard covers
67 test classes in both CI/release test lists; changing test selection does not
execute or authorize a release. The previous source 521b6992 passed nonpublish
build 43558117738. That is not compilation or runtime evidence for these changes.

Before wiring BackupManager: lease high-level operations without carrying a
thread-bound lease across suspension; audit untracked direct IO and kernel-owned
paths; establish actual native close semantics and callback draining; hold data
exclusion through Room close/checkpoint, journal prepare/commit/recovery. Android
cold/locked start, Room/WAL, failure injection, exact ARM APK acceptance and safe
parallel installation remain mandatory. No experimental APK is approved for trial
or release by the above host results.

## Coroutine admission experiment (2026-11-14)

`wabi/restore-coroutine-admission-20261114` adds a thread-independent root
admission lease carried by a coroutine context element. Already admitted work
can complete synchronous accesses while restore drains; a newly independent
operation is rejected. Structured children finish before release, and detached
children inheriting an expired context cannot access application data. Exception
and cancellation paths release the root. This does not serialize ordinary edits
against each other and is not a cross-process lock.

The root now spans the selected import/create entry points, background remote
profile update and ProfileManager create/update/delete methods. These leases
cover dispatcher changes and suspensions; the individual synchronous file/DAO
leases remain. This is NOT complete application operation coverage: UI editors,
chain/provider operations, cached profile handles, kernel callbacks/native file
ownership and backup direct IO need a whole-operation audit before exclusion is
safe. No BackupManager transaction was enabled by this change.

Nine new coroutine tests join 46 existing host gate/journal/file tests; all 55
passed together on the host. Reproduce with `run_admission_host_tests.sh`, setting
KOTLIN_HOME, JUNIT_JAR, HAMCREST_JAR and an output jar path. These host tests do not
compile the Android application or validate Room/WAL, Android service closure,
provider/locked startup, actual APK behavior, power loss or safe installation.
No trial/release acceptance follows from them. The prior e9d6dad build remains a
separate source version and is not evidence for this branch.

## Editor snapshot admission experiment (2026-11-14)

Adds an in-process opaque data-image token. Successful admission drain rotates it
before exclusive replacement; a failed drain does not rotate it. Failures during
exclusive replacement keep admission blocked. Cached tokens are not operation
leases. Editors, provider/chain UI operations, selected dashboard update/sharing
paths now carry root leases across their data-dependent work and reject stale
loaded images. Profile and RemoteServer DAO materialization stamps process-local
provenance (excluded from Room/parceling); mutation of a stale or unstamped loaded
entity fails rather than silently changing a replacement image.

Host suite: 62 tests, including seven snapshot/drain/foreign-token/startup/failure
cases. The entity annotations, screens, Room integration and native ownership need
Android compilation/runtime tests; host tests do not cover those. An older APK
from 6cbccf7 is not runtime or compilation evidence for these source changes.

Still NOT enabled: BackupManager journal transaction. This change does not prove
all data access is covered, does not serialize concurrent normal edits, does not
validate Android service callback/native quiescence or WAL/checkpoint behavior.
Audit retained callbacks, kernel-owned paths, direct backup IO and DB replacement
before enabling restore transactions. Android cold/locked startup, process kill,
exact ARM package acceptance, safe non-overwriting installation and full security
audit remain required. No trial or release approval is implied.

## Native callback / chain admission continuation (2026-11-14)

Service startup/reload now retains a coroutine data-image lease; the native start
worker and VPN openTun callback independently acquire synchronous data access on
their executing thread. Actual callback bodies are counted until return; a late
callback after the service pause is sealed is rejected and poisons service
admission. ConfigChainReapply retains a root operation across suspensions, and
ChainBindings keeps one synchronous data lease across each complete settings
read/modify/write (including legacy migration). Delayed external exports validate
the editor's image before opening/truncating their destination.

Host validation: 65 gate/journal/file/coroutine tests pass. In addition, 20 tests
compile the real ChainBindings and gates with a **fake Settings**, org.json
20240303 and existing codec regressions. The three new tests cover round-trip
mutation, all eight entry points rejecting during exclusive ownership without
settings mutation, and legacy read migration. Reproduce with
`run_chain_admission_tests.sh`; provide KOTLIN_HOME, JUNIT_JAR, HAMCREST_JAR and
JSON_JAR. These do not exercise Room, Android or actual native callbacks. A native
callback rejected during drain may abort startup; the shutdown/rollback paths
must be tested on Android before enabling restore. No BackupManager transaction
is enabled. Successful compilation/build, if obtained, remains separate from
runtime acceptance and source audit.

## Host-only gate/journal composition test (2026-11-14)

`RestoreTransactionIntegrationTest` composes `RestoreAccessGate` and
`RestoreRecoveryJournal` in two synthetic end-to-end cases: a committed
replacement remains closed to another thread until the journal completes, and a
failed replacement poisons the current gate until a new startup gate replays the
PREPARED journal. It is included in `run_admission_host_tests.sh`.

These tests deliberately do not wire `BackupManager` or claim coverage of Room,
SQLite/WAL consistency, native/service quiescence, Android storage durability,
process-kill behavior of the app, or Android/APK acceptance. Those dependencies
remain required before enabling live restore transactions.
