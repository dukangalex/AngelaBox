#!/usr/bin/env python3
"""Ephemeral loopback-only SOCKS5 test exit; records CONNECT metadata, never content."""
import copy
import ipaddress
import json
import select
import socket
import socketserver
import struct
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
generation = 0

def now():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')

def new_phase_state():
    return {
        'phase': generation,
        'phase_started_at': now(),
        'connect_attempts': 0,
        'connect_successes': 0,
        'active_sessions': 0,
        'targets': [],
        'events': [],
    }

counts = new_phase_state()

def snapshot():
    with lock:
        return copy.deepcopy(counts)

def reset_phase():
    """Start a clean measured phase only when no SOCKS client remains active."""
    global generation, counts
    with lock:
        if counts['active_sessions']:
            return False
        generation += 1
        counts = new_phase_state()
        return True

def record_connect_start(host, port):
    event = {
        'phase': generation,
        'started_at': now(),
        'started_monotonic_ns': time.monotonic_ns(),
        'host': host,
        'port': port,
        'status': 'connecting',
    }
    with lock:
        counts['connect_attempts'] += 1
        counts['events'].append(event)
    return event

def record_connect_end(event, status):
    with lock:
        event['finished_at'] = now()
        event['finished_monotonic_ns'] = time.monotonic_ns()
        event['status'] = status
        if status == 'connected':
            counts['connect_successes'] += 1
            counts['targets'].append({'host': event['host'], 'port': event['port']})


def exact(s, n):
    data = b''
    while len(data) < n:
        x = s.recv(n-len(data))
        if not x:
            raise ConnectionError('eof')
        data += x
    return data

class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        with lock:
            counts['active_sessions'] += 1
        try:
            self.handle_socks()
        finally:
            with lock:
                counts['active_sessions'] -= 1

    def handle_socks(self):
        client = self.request
        client.settimeout(15)
        event = None
        try:
            version, n = exact(client, 2)
            methods = exact(client, n)
            if version != 5 or 0 not in methods:
                return
            client.sendall(b'\x05\x00')
            version, cmd, _, kind = exact(client, 4)
            if version != 5 or cmd != 1:
                return
            if kind == 1:
                host = socket.inet_ntop(socket.AF_INET, exact(client, 4))
            elif kind == 4:
                host = socket.inet_ntop(socket.AF_INET6, exact(client, 16))
            elif kind == 3:
                host = exact(client, exact(client, 1)[0]).decode('ascii')
            else:
                return
            port = struct.unpack('!H', exact(client, 2))[0]
            addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
            if port != 443 or not addresses or not all(ipaddress.ip_address(a[4][0]).is_global for a in addresses):
                client.sendall(b'\x05\x02\x00\x01'+bytes(6))
                return
            event = record_connect_start(host, port)
            try:
                remote = socket.create_connection((host, port), timeout=15)
            except OSError:
                record_connect_end(event, 'connect_failed')
                event = None
                raise
            record_connect_end(event, 'connected')
            event = None
            client.sendall(b'\x05\x00\x00\x01'+bytes(6))
            with remote:
                while True:
                    readable, _, _ = select.select([client, remote], [], [], 20)
                    if not readable:
                        return
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        (remote if source is client else client).sendall(data)
        except (OSError, ConnectionError, ValueError):
            if event is not None:
                record_connect_end(event, 'connect_failed')
            return

class Stats(BaseHTTPRequestHandler):
    def _write_json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != '/stats':
            self._write_json(404, {'error': 'not_found'})
            return
        self._write_json(200, snapshot())

    def do_POST(self):
        if self.path != '/reset':
            self._write_json(404, {'error': 'not_found'})
            return
        if not reset_phase():
            self._write_json(409, snapshot())
            return
        self._write_json(200, snapshot())

    def log_message(self, *args):
        pass

if __name__ == '__main__':
    server = socketserver.ThreadingTCPServer(('127.0.0.1', 18971), Socks)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1', 18972), Stats).serve_forever()
