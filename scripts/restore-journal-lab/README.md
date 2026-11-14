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
