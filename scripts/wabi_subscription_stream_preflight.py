"""Require the disposable HTTPS relay to stream before claiming a partial-file test.

A separate endpoint uses exactly the mid-file payload/hold behavior without
changing the mid-file request counter. Failure here is a harness failure, not
an Android product failure. The device test still requires a nonzero on-device
fragment, stops the real VPN, and asserts failure plus fragment cleanup.
"""
import json
import os
from pathlib import Path
import time
import urllib.request

out = Path('wabi-evidence')
out.mkdir(exist_ok=True)
started = time.monotonic()
result = {'endpoint': 'stream-preflight', 'hold_seconds': 45}
try:
    with urllib.request.urlopen(os.environ['WABI_HTTPS_BASE'] + '/stream-preflight', timeout=10) as response:
        result['headers'] = dict(response.headers)
        result['headers_seconds'] = time.monotonic() - started
        prefix = response.read(64 * 1024)
        result['prefix_bytes'] = len(prefix)
        result['prefix_seconds'] = time.monotonic() - started
        assert len(prefix) == 64 * 1024, result
        assert result['prefix_seconds'] < 10, result
        assert int(response.headers['Content-Length']) > len(prefix), result
    result['passed'] = True
except Exception as error:
    result['passed'] = False
    result['error'] = repr(error)
    raise
finally:
    result['elapsed_seconds'] = time.monotonic() - started
    (out / 'https-stream-preflight.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
