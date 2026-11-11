#!/usr/bin/env python3
"""Loopback-only CLI reproduction; Python supplies DNS/echo, sing-box routes.

No TUN, external servers, system DNS changes, active profiles, or third-party
Python packages. Each case starts/stops only its own sing-box child process.
"""
import argparse
import contextlib
import json
from pathlib import Path
import socket
import socketserver
import struct
import subprocess
import threading
import time

NAME = "mapping.example"
IP = "127.0.0.1"


class DNSHandler(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        pos = 12
        while packet[pos]:
            pos += packet[pos] + 1
        end = pos + 5
        self.server.count += 1
        ttl = self.server.first_ttl if self.server.count == 1 else 300
        reply = (packet[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0)
                 + packet[12:end] + b"\xc0\x0c"
                 + struct.pack("!HHIH", 1, 1, ttl, 4) + socket.inet_aton(IP))
        sock.sendto(reply, self.client_address)


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(3)
        with contextlib.suppress(OSError):
            data = self.request.recv(64)
            self.request.sendall(data)


class TCPServer(socketserver.ThreadingTCPServer):
    daemon_threads = True


def start_server(server):
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def vacant_port(kind):
    with socket.socket(socket.AF_INET, kind) as sock:
        sock.bind((IP, 0))
        return sock.getsockname()[1]


def read_exact(sock, count):
    result = b""
    while len(result) < count:
        part = sock.recv(count - len(result))
        if not part:
            raise EOFError("connection closed")
        result += part
    return result


def dns_query(port):
    labels = b"".join(bytes([len(x)]) + x.encode() for x in NAME.split(".")) + b"\0"
    packet = struct.pack("!HHHHHH", 100, 0x100, 1, 0, 0, 0) + labels + struct.pack("!HH", 1, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(3)
        sock.sendto(packet, (IP, port))
        reply, _ = sock.recvfrom(4096)
    assert struct.unpack_from("!H", reply, 6)[0] == 1, reply.hex()
    # One A record with four bytes of RDATA; TTL is ten bytes from the end.
    ttl = struct.unpack_from("!I", reply, len(reply) - 10)[0]
    assert socket.inet_ntoa(reply[-4:]) == IP
    return ttl


def connect_by_ip(port, destination_port):
    try:
        with socket.create_connection((IP, port), timeout=3) as sock:
            sock.sendall(b"\x05\x01\x00")
            assert read_exact(sock, 2) == b"\x05\x00"
            sock.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(IP)
                         + struct.pack("!H", destination_port))
            head = read_exact(sock, 4)
            if head[1] != 0:
                return False
            size = {1: 4, 4: 16}.get(head[3])
            if size is None:
                size = read_exact(sock, 1)[0]
            read_exact(sock, size + 2)
            sock.sendall(b"opaque-payload")
            return read_exact(sock, 14) == b"opaque-payload"
    except (OSError, EOFError):
        return False


def run_case(binary, output, optimistic):
    name = "optimistic" if optimistic else "ordinary"
    directory = output / name
    directory.mkdir(parents=True, exist_ok=True)
    short = socketserver.UDPServer((IP, 0), DNSHandler)
    short.first_ttl, short.count = 1, 0
    long = socketserver.UDPServer((IP, 0), DNSHandler)
    long.first_ttl, long.count = 300, 0
    echo = TCPServer((IP, 0), EchoHandler)
    servers = [start_server(s) for s in (short, long, echo)]
    short_port, long_port = vacant_port(socket.SOCK_DGRAM), vacant_port(socket.SOCK_DGRAM)
    mixed_port = vacant_port(socket.SOCK_STREAM)
    config = {
        "log": {"level": "debug", "timestamp": True},
        "dns": {
            "servers": [
                {"type": "udp", "tag": "short", "server": IP, "server_port": short.server_address[1]},
                {"type": "udp", "tag": "long", "server": IP, "server_port": long.server_address[1]}
            ],
            "rules": [
                {"inbound": "dns-short", "server": "short"},
                {"inbound": "dns-long", "server": "long"}
            ],
            "reverse_mapping": True,
            "optimistic": optimistic
        },
        "inbounds": [
            {"type": "direct", "tag": "dns-short", "listen": IP, "listen_port": short_port, "network": "udp"},
            {"type": "direct", "tag": "dns-long", "listen": IP, "listen_port": long_port, "network": "udp"},
            {"type": "mixed", "tag": "client", "listen": IP, "listen_port": mixed_port}
        ],
        "outbounds": [{"type": "direct", "tag": "allowed-by-domain"}],
        "route": {"default_domain_resolver": "long", "rules": [
            {"inbound": ["dns-short", "dns-long"], "action": "hijack-dns"},
            {"domain": NAME, "outbound": "allowed-by-domain"},
            {"action": "reject"}
        ]}
    }
    config_path = directory / "config.json"
    config_path.write_text(json.dumps(config, indent=2) + "\n")
    process = None
    try:
        check = subprocess.run([binary, "check", "-c", str(config_path)], capture_output=True, text=True)
        if check.returncode:
            raise RuntimeError(check.stdout + check.stderr)
        with (directory / "sing-box.log").open("w") as logfile:
            process = subprocess.Popen([binary, "run", "-c", str(config_path)], stdout=logfile, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 5
            while True:
                if process.poll() is not None:
                    raise RuntimeError((directory / "sing-box.log").read_text())
                try:
                    with socket.create_connection((IP, mixed_port), timeout=0.05):
                        break
                except OSError:
                    if time.monotonic() > deadline:
                        raise TimeoutError("sing-box startup")
                    time.sleep(0.02)
            first_ttl = dns_query(short_port)
            assert first_ttl == 1
            long_received = time.monotonic()
            long_ttl = dns_query(long_port)
            assert long_ttl == 300
            baseline = connect_by_ip(mixed_port, echo.server_address[1])
            assert baseline, "domain mapping must work before the experiment"
            time.sleep(1.2)
            second_ttl = dns_query(short_port)
            before_expiry = connect_by_ip(mixed_port, echo.server_address[1])
            time.sleep(1.25)
            after_expiry = connect_by_ip(mixed_port, echo.server_address[1])
            long_remaining = long_ttl - (time.monotonic() - long_received)
            requery_ttl = dns_query(long_port)
            after_requery = connect_by_ip(mixed_port, echo.server_address[1])
            assert before_expiry and after_requery
            assert long_remaining > 290
            result = {
                "case": name, "first_reply_ttl": first_ttl, "second_reply_ttl": second_ttl,
                "baseline_domain_route": baseline, "domain_route_within_second_reply_ttl": before_expiry,
                "domain_route_1_25s_later": after_expiry,
                "earlier_long_reply_still_valid_seconds": round(long_remaining, 3),
                "requery_ttl": requery_ttl, "domain_route_after_requery": after_requery,
                "short_upstream_queries": short.count, "long_upstream_queries": long.count
            }
            (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result), flush=True)
            return result
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for server in servers:
            server.shutdown()
            server.server_close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=lambda x: str(Path(x).resolve()))
    parser.add_argument("output", type=lambda x: Path(x).resolve())
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "version.txt").write_text(subprocess.check_output([args.binary, "version"], text=True))
    results = [run_case(args.binary, args.output, optimistic) for optimistic in (False, True)]
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
