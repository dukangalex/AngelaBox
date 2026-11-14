#!/usr/bin/env python3
"""Real host SQLite closed-file snapshots through journal replay/startup gate.
No Android, Room, open WAL snapshots, native service, concurrent writes, or power-loss proof.
Pass the probe jar compiled from RestoreAccessGate, RestoreRecoveryJournal, CrashProbe and StartupProbe.
"""
import pathlib
import sqlite3
import subprocess
import sys
import tempfile

jar = pathlib.Path(sys.argv[1]).resolve()

def run(root, cls, *args):
    return subprocess.run(["java", "-cp", str(jar), "io.nekohasekai.sfa.utils." + cls,
                           *args, str(root)] if cls == "StartupProbeKt" else
                          ["java", "-cp", str(jar), "io.nekohasekai.sfa.utils." + cls,
                           args[0], str(root), *args[1:]], capture_output=True, text=True, timeout=30)

def db_write(path, value):
    db = sqlite3.connect(path)
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("INSERT OR REPLACE INTO state VALUES (1, ?)", (value,))
        db.commit()
    finally:
        db.close()  # required quiescent, closed SQLite file before journal.prepare()

def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes() for base in ("databases", "files")
            for p in (root / base).rglob("*") if p.is_file()}

for index in range(9):
    with tempfile.TemporaryDirectory(prefix="restore-sqlite-startup-") as directory:
        root = pathlib.Path(directory)
        (root / "databases").mkdir()
        (root / "files/configs").mkdir(parents=True)
        for name in ("settings.db", "profiles.db"):
            db_write(root / "databases" / name, "old:" + name)
        (root / "files/configs/a").write_text("old-config")
        old = snapshot(root)
        result = run(root, "CrashProbeKt", "prepare")
        assert result.returncode == 0, result.stderr
        for name in ("settings.db", "profiles.db"):
            db_write(root / "databases" / name, "new:" + name)
        (root / "files/configs/a").write_text("new-config")
        (root / "files/configs/new").write_text("new-file")
        result = run(root, "CrashProbeKt", "recover", str(index))
        assert result.returncode == 73, (index, result.returncode, result.stderr)
        result = run(root, "StartupProbeKt")
        assert result.returncode == 0, result.stderr
        assert result.stdout.strip() == "RECOVERY_COMPLETE_ACCESS_ADMITTED"
        assert snapshot(root) == old, index
        for name in ("settings.db", "profiles.db"):
            db = sqlite3.connect("file:" + str(root / "databases" / name) + "?mode=ro", uri=True)
            try:
                assert db.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
                assert db.execute("SELECT value FROM state WHERE id=1").fetchone() == ("old:" + name,)
            finally:
                db.close()
        result = run(root, "StartupProbeKt")
        assert result.returncode == 0, result.stderr
        print(f"PASS: target {index} hard halt; startup replay before admission; both host SQLite DBs intact")
print("PASS: 9 real host SQLite closed-snapshot cases; NOT Android/WAL concurrency/power-loss validation")
