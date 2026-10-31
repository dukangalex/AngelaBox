from pathlib import Path
import sys
p=Path(sys.argv[1])/"hysteria/packet.go"
s=p.read_text()
a="func (c *udpPacketConn) inputPacket(message *udpMessage) {"
b=a+"\n println(\"DIAG_FRAGMENT session\", message.sessionID, \"packet\", message.packetID, \"index\", message.fragmentID, \"total\", message.fragmentTotal, \"size\", message.data.Len())"
assert s.count(a)==1
s=s.replace(a,b)
a="newMessage := d.defragger.feed(message)"
b=a+"\n if newMessage != nil { println(\"DIAG_REASSEMBLED\", newMessage.packetID, newMessage.data.Len()) }"
assert s.count(a)==1
s=s.replace(a,b)
p.write_text(s)
p=Path(sys.argv[1])/"hysteria/client_packet.go"
s=p.read_text(); a="func (c *Client) handleMessage(conn *clientQUICConnection, data []byte) error {"
assert s.count(a)==1
s=s.replace(a,a+"\n println(\"DIAG_QUIC_RECEIVE_BYTES\", len(data))")
p.write_text(s)
