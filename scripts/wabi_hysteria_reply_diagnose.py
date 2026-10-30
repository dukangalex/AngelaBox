from pathlib import Path
p = Path('clash_test.go')
s = p.read_text()
start = s.index('func testLargeDataWithPacketConnSize(')
end = s.index('\nfunc testPacketConnTimeout(', start)
head, body, tail = s[:start], s[start:end], s[end:]
old = '''if _, err := pc.WriteTo(buf, addr); err != nil {
\t\t\t\tt.Log(err.Error())
\t\t\t}'''
new = '''n, writeErr := pc.WriteTo(buf, addr)
            t.Logf("DIAG_WRITE index=%d size=%d bytes=%d local=%v target=%v err=%v", i, chunkSize, n, pc.LocalAddr(), addr, writeErr)
            if writeErr != nil { return nil, writeErr }'''
assert body.count(old)==1
body = body.replace(old, new)
old = '_, rAddr, readErr = l.ReadFrom(buf)'
new = '''n, addr, readErr := l.ReadFrom(buf)
            rAddr = addr
            if readErr == nil { t.Logf("DIAG_TARGET_READ index=%d size=%d bytes=%d src=%v id=%d", i, chunkSize, n, addr, buf[0]) }'''
assert body.count(old)==1
body=body.replace(old,new)
old='_, _, err := pc.ReadFrom(buf)'
new='''n, addr, err := pc.ReadFrom(buf)
            if err == nil { t.Logf("DIAG_CLIENT_READ index=%d size=%d bytes=%d src=%v id=%d", i, chunkSize, n, addr, buf[0]) }'''
assert body.count(old)==1
body=body.replace(old,new)
s = head + body + tail
s=s.replace('time.After(10 * time.Second)', 'time.After(60 * time.Second)')
p.write_text(s)
