from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
old="func (s *connection) SendMessage(p []byte) error {\n"
new=old+"\tif len(p) > 1100 { println(\"DIAG_CAP_TOO_LARGE\", len(p)); return ErrMessageTooLarge(1100) }\n"
assert s.count(old)==1
p.write_text(s.replace(old,new))
