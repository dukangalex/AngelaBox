package org.wabi.vpnprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
public final class ProbeActivity extends Activity {
 public void onCreate(Bundle b) {
  super.onCreate(b); final TextView v = new TextView(this); v.setText("WABI_PROBE_PENDING"); setContentView(v);
  final String address=getIntent().getStringExtra("url");
  new Thread(new Runnable() { public void run() { String out; HttpURLConnection c=null;
   try { c=(HttpURLConnection)new URL(address).openConnection(); c.setConnectTimeout(6000); c.setReadTimeout(6000); int status=c.getResponseCode(); InputStream in=c.getInputStream(); ByteArrayOutputStream buf=new ByteArrayOutputStream(); byte[] chunk=new byte[256]; for(int n;(n=in.read(chunk))!=-1;) buf.write(chunk,0,n); in.close(); out="WABI_PROBE_HTTP_"+status+"_"+new String(buf.toByteArray(),StandardCharsets.UTF_8).trim(); }
   catch(Exception e) { out="WABI_PROBE_FAILED_"+e.getClass().getSimpleName(); }
   finally {if(c!=null)c.disconnect();} final String result=out; runOnUiThread(new Runnable() { public void run() { v.setText(result); } });
  } }).start();
 }
}
