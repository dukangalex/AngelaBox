from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
if p.name == 'server_client.go':
 old='''sendErr := c.CC.SendMessage(msgBuf.Bytes())
\t\t\t\tif sendErr != nil {'''
 new='''sendErr := c.CC.SendMessage(msgBuf.Bytes())
                if sendErr != nil { println("DIAG_SERVER_FIRST_ERROR", n, len(msgBuf.Bytes()), sendErr.Error()) } else { println("DIAG_SERVER_FIRST_OK", n, len(msgBuf.Bytes())) }
\t\t\t\tif sendErr != nil {'''
 assert s.count(old)==1; s=s.replace(old,new)
 old='''_ = c.CC.SendMessage(msgBuf.Bytes())'''
 new='''fragErr := c.CC.SendMessage(msgBuf.Bytes())
                            if fragErr != nil { println("DIAG_SERVER_FRAGMENT_ERROR", n, len(msgBuf.Bytes()), fragErr.Error()) } else { println("DIAG_SERVER_FRAGMENT_OK", n, len(msgBuf.Bytes())) }'''
 assert s.count(old)==1; s=s.replace(old,new)
else:
 old='if debug.Enabled {'; new='if debug.Enabled || options.Image == "tobyxdd/hysteria:v1.3.5" {'
 assert s.count(old)==1; s=s.replace(old,new)
p.write_text(s)
