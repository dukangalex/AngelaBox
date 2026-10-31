from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
old="\t\tEnableDatagrams:                true,\n"
new=old+"\t\tMaxDatagramFrameSize:           1200,\n"
assert s.count(old)==1
p.write_text(s.replace(old,new))
