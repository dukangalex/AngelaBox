import socket
import sys

# Test both address families without sending application data. Any successful connect fails closed.
probes = [
    (socket.AF_INET, ("1.1.1.1", 443)),
    (socket.AF_INET6, ("2606:4700:4700::1111", 443, 0, 0)),
]
for family, address in probes:
    sock = socket.socket(family, socket.SOCK_STREAM)
    sock.settimeout(3)
    try:
        sock.connect(address)
    except OSError as exc:
        print(f"EGRESS_BLOCKED family={family} error={type(exc).__name__}:{exc}")
    else:
        print(f"EGRESS_NOT_BLOCKED family={family}", file=sys.stderr)
        sys.exit(1)
    finally:
        sock.close()
