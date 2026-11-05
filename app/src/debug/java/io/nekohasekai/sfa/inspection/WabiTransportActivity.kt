package io.nekohasekai.sfa.inspection
import android.app.Activity
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import io.nekohasekai.sfa.utils.TunnelGate
import java.io.File
import org.json.JSONObject
class WabiTransportActivity : Activity() {
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  if (intent.getStringExtra("mode") == "controlled-real-dns") {
   Settings.dnsProtect = false
   File(noBackupFilesDir,"wabi-real-dns-setting.txt").writeText("dnsProtect=false")
   finish(); return
  }
  if (intent.getStringExtra("mode") == "network-state") {
   val cm = getSystemService(ConnectivityManager::class.java)
   val network = cm.activeNetwork
   val capabilities = network?.let(cm::getNetworkCapabilities)
   val state = JSONObject().put("observed_epoch_ms", System.currentTimeMillis())
    .put("tunnel_up", TunnelGate.up)
    .put("active_network_present", network != null)
    .put("capabilities_known", network == null || capabilities != null)
    .put("active_vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
   File(noBackupFilesDir,"wabi-network-state.json").writeText(state.toString())
   finish(); return
  }
  val base = intent.getStringExtra("endpoint").orEmpty()
  require(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").matches(base))
  val path = intent.getStringExtra("case") ?: "ok"
  require(path in listOf("ok","redirect","failure","blocked","stopped","mid-failure","mid-body","mid-file"))
  val cm=getSystemService(ConnectivityManager::class.java)
  val vpn=cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
  val up=TunnelGate.up
  Thread {
   val result=JSONObject().put("case",path).put("tunnel_up",up).put("active_vpn",vpn)
   try {
    val dest=File(noBackupFilesDir,"mid-subscription-download.txt")
    if(path=="mid-file") dest.delete()
    val body=HTTPClient().use {
     if(path=="mid-file") {
      it.downloadToFile("$base/$path",RemoteUrlGuard.Kind.SUBSCRIPTION,dest)
      dest.readText()
     } else it.getString("$base/$path",RemoteUrlGuard.Kind.SUBSCRIPTION)
    }
    result.put("success",true).put("body",body)
   } catch (e:Exception) {
    result.put("success",false).put("error_type",e.javaClass.simpleName).put("message",e.message)
   }
   result.put("request_finished_epoch_ms",System.currentTimeMillis())
   result.put("tunnel_up_after",TunnelGate.up)
   result.put("active_vpn_after",cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
   result.put("partial_file_exists",File(noBackupFilesDir,"mid-subscription-download.txt").exists())
   File(noBackupFilesDir,"subscription-result.json").writeText(result.toString())
   runOnUiThread { finish() }
  }.start()
 }
}
