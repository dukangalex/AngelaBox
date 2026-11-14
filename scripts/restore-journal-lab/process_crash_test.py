#!/usr/bin/env python3
"""Host-only hard-stop/replay test. Not SQLite, Android, disk-full, or power-loss validation.
Build CrashProbe.kt with RestoreRecoveryJournal.kt using kotlinc -include-runtime, then pass its jar.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

jar = pathlib.Path(sys.argv[1]).resolve()
java = os.environ.get("JAVA", "java")
targets = ["databases/" + name + suffix for name in ("settings.db", "profiles.db")
           for suffix in ("", "-wal", "-shm", "-journal")] + ["files/configs/a", "files/configs/b"]

def run(root, *args):
    return subprocess.run([java, "-cp", str(jar), "io.nekohasekai.sfa.utils.CrashProbeKt",
                           args[0], str(root), *args[1:]], capture_output=True, text=True, timeout=30)

def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes() for base in ("databases", "files")
            for p in (root/base).rglob("*") if p.is_file()}

for index in range(9):
    with tempfile.TemporaryDirectory(prefix="restore-kill-") as directory:
        root = pathlib.Path(directory)
        for name in targets:
            p = root/name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("old:" + name)
        old = snapshot(root)
        result = run(root, "prepare")
        assert result.returncode == 0, result.stderr
        for name in targets:
            (root/name).write_text("new:" + name)
        (root/"files/configs/new").write_text("newly added")
        result = run(root, "recover", str(index))
        assert result.returncode == 73, (index, result.returncode, result.stderr)
        assert (root/".restore-recovery/PREPARED").is_file()
        result = run(root, "recover")
        assert result.returncode == 0, result.stderr
        assert snapshot(root) == old, index
        assert not (root/".restore-recovery").exists()
        result = run(root, "recover")
        assert result.returncode == 0 and snapshot(root) == old
        print(f"PASS: hard JVM halt after rollback target {index}; full replay and repeated recovery")
print("PASS: 9 process-termination cases; host synthetic files only")
