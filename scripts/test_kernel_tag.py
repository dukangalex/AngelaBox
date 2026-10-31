#!/usr/bin/env python3
"""Exercise the published tag / pinned commit gate against a real local remote."""
from pathlib import Path
import os
import subprocess
import tempfile

SCRIPT = Path(__file__).resolve().parent / 'verify_kernel_tag.sh'
TAG = 'v1.0.0' + '-chain.' + '987'


def run(*cmd, cwd=None, check=True):
    return subprocess.run(cmd, cwd=cwd, check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        remote, src, checkout = root/'remote.git', root/'src', root/'checkout'
        run('git', 'init', '--bare', str(remote))
        run('git', 'init', str(src))
        run('git', 'config', 'user.name', 'Kernel tag test', cwd=src)
        run('git', 'config', 'user.email', 'test@example.invalid', cwd=src)
        (src/'content').write_text('one\n')
        run('git', 'add', 'content', cwd=src)
        run('git', 'commit', '-m', 'first', cwd=src)
        first = run('git', 'rev-parse', 'HEAD', cwd=src).stdout.strip()
        run('git', 'tag', TAG, cwd=src)
        run('git', 'remote', 'add', 'origin', str(remote), cwd=src)
        run('git', 'push', 'origin', 'HEAD:refs/heads/main', 'refs/tags/'+TAG, cwd=src)
        run('git', 'clone', '--no-tags', '--branch', 'main', str(remote), str(checkout))
        props = root/'version.properties'
        def verify(tag, commit):
            props.write_text(f'KERNEL_TAG={tag}\nKERNEL_COMMIT={commit}\n')
            def shell_path(path):
                if os.name == 'nt':
                    drive = path.drive.rstrip(':').lower()
                    return '/' + drive + path.as_posix()[2:]
                return str(path)
            return run(os.environ.get('WABI_GIT_BASH', 'bash'), shell_path(SCRIPT), shell_path(props), shell_path(checkout), check=False)
        matching = verify(TAG, first)
        assert matching.returncode == 0, f'matching published tag must pass: {matching.stdout} {matching.stderr}'
        (src/'content').write_text('two\n')
        run('git', 'commit', '-am', 'second', cwd=src)
        second = run('git', 'rev-parse', 'HEAD', cwd=src).stdout.strip()
        run('git', 'push', 'origin', 'HEAD:refs/heads/main', cwd=src)
        run('git', 'fetch', 'origin', 'main', cwd=checkout)
        run('git', 'checkout', '--detach', second, cwd=checkout)
        assert verify(TAG, second).returncode != 0, 'mismatched published tag must fail'
        assert verify(TAG + '0', second).returncode != 0, 'missing published tag must fail'
        run('git', 'tag', '-f', TAG, second, cwd=checkout)
        assert verify(TAG, second).returncode != 0, 'forged local tag must fail'
        print('all kernel tag verification cases passed')


if __name__ == '__main__':
    main()
