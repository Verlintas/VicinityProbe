/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vicinityprobe.MainActivity
import com.vicinityprobe.R
import com.vicinityprobe.analysis.AnalysisEngine
import com.vicinityprobe.probe.SessionController
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.JsonReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 局域网 Web 控制台:轻量 HTTP 服务器(无第三方依赖)。
 * - 浏览器仪表盘:历史报告列表、质量总览、抓包统计
 * - API:报告 JSON / 原始样本 CSV / pcap 下载
 * - 远程触发扫描(POST /api/scan)
 * 仅用于可信局域网,界面有合规提示。
 */
class WebServerService : Service() {
    companion object {
        const val ACTION_START = "com.vicinityprobe.action.WEB_START"
        const val ACTION_STOP = "com.vicinityprobe.action.WEB_STOP"
        private const val CHANNEL_ID = "webconsole"
        private const val NOTIFICATION_ID = 4
        const val PORT = 8080

        private var running = false
        private var serverSocket: ServerSocket? = null
        private var serverThread: Thread? = null

        fun isRunning(): Boolean = running
        fun port(): Int = PORT

        /** 局域网 IPv4 地址(供 UI 显示 URL) */
        fun localIp(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            } catch (_: Throwable) { null }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            else -> startServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (running) return
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("starting…"))
        running = true
        serverThread = Thread { serve() }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopServer() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        serverThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun serve() {
        try {
            val socket = ServerSocket(PORT)
            serverSocket = socket
            updateNotification("http://${localIp() ?: "?"}:$PORT")
            while (running) {
                val client = try { socket.accept() } catch (_: Throwable) { null } ?: continue
                client.soTimeout = 8000
                Thread { handle(client) }.apply { isDaemon = true; start() }
            }
        } catch (_: Throwable) {
            // server closed
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = c.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val rawPath = parts[1]
                val out = c.getOutputStream()
                when {
                    method == "GET" && (rawPath == "/" || rawPath.startsWith("/index")) ->
                        respond(out, "text/html; charset=utf-8", dashboardHtml().toByteArray(StandardCharsets.UTF_8))
                    method == "GET" && rawPath == "/api/reports" ->
                        respond(out, "application/json", apiReports())
                    method == "GET" && rawPath.startsWith("/api/report/") ->
                        respond(out, "application/json", apiReport(rawPath.removePrefix("/api/report/")))
                    method == "GET" && rawPath.startsWith("/api/capture") ->
                        respond(out, "application/json", apiCapture())
                    method == "POST" && rawPath == "/api/scan" ->
                        handleScan(out, reader)
                    method == "GET" && rawPath.startsWith("/download/report/") -> {
                        val id = rawPath.removePrefix("/download/report/")
                        val f = File(filesDir, "reports/$id/report.json")
                        if (f.exists()) respondFile(out, f, "application/json") else respond404(out)
                    }
                    method == "GET" && rawPath.startsWith("/download/samples/") -> {
                        // /download/samples/<id>/<relative path>
                        val rest = rawPath.removePrefix("/download/samples/")
                        val idx = rest.indexOf('/')
                        if (idx > 0) {
                            val id = rest.substring(0, idx)
                            val rel = URLDecoder.decode(rest.substring(idx + 1), "UTF-8")
                            val f = File(File(filesDir, "reports/$id"), rel)
                            if (f.exists() && f.isFile && f.path.startsWith(File(filesDir, "reports").path)) {
                                respondFile(out, f, "text/csv")
                            } else respond404(out)
                        } else respond404(out)
                    }
                    method == "GET" && rawPath.startsWith("/download/pcap") -> {
                        val f = CaptureController.pcapPath()
                        if (f != null && f.exists()) respondFile(out, f, "application/vnd.tcpdump.pcap") else respond404(out)
                    }
                    method == "GET" && rawPath == "/api/capabilities" ->
                        respond(out, "application/json", apiCapabilities())
                    else -> respond404(out)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleScan(out: java.io.OutputStream, reader: java.io.BufferedReader) {
        // 解析请求头,提取 Content-Length
        var contentLength = 0
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            if (line.startsWith("Content-Length:", true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            line = reader.readLine()
        }
        val body = if (contentLength > 0 && contentLength < 65536) {
            CharArray(contentLength).let { reader.read(it); String(it) }
        } else ""
        val params = body.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val ids = params["ids"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (ids.isEmpty()) {
            respond(out, "application/json", """{"ok":false,"error":"no probe ids"}""".toByteArray())
            return
        }
        val duration = (params["duration"]?.toLongOrNull() ?: 10_000L).coerceIn(5_000L, 120_000L)
        val caps = com.vicinityprobe.probe.CapabilityProbe.enumerate(this)
        val supportedIds = caps.filter { it.status == com.vicinityprobe.probe.CapabilityStatus.SUPPORTED }.map { it.probeId }.toSet()
        val validIds = ids.filter { it in supportedIds }.toSet()
        scope.launch {
            val controller = SessionController(this@WebServerService, validIds, duration, "REMOTE")
            val report = controller.run(File(filesDir, "reports"))
            val analyzed = report.copy(analysis = AnalysisEngine.analyze(report))
            HistoryManager(this@WebServerService).save(analyzed)
        }
        respond(out, "application/json",
            """{"ok":true,"triggered":true,"probes":${validIds.size},"durationMs":$duration}""".toByteArray())
    }

    private fun apiReports(): ByteArray {
        val metas = HistoryManager(this).list()
        return JsonReportList.encode(metas).toByteArray(StandardCharsets.UTF_8)
    }

    private fun apiReport(id: String): ByteArray {
        val r = HistoryManager(this).load(id) ?: return """{"error":"not found"}""".toByteArray()
        return JsonReport.encode(r).toByteArray(StandardCharsets.UTF_8)
    }

    private fun apiCapabilities(): ByteArray {
        val caps = com.vicinityprobe.probe.CapabilityProbe.enumerate(this)
        val sb = StringBuilder("[")
        caps.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(c.probeId)
                .append("\",\"status\":\"").append(c.status.name)
                .append("\",\"risk\":").append(c.spec.complianceRisk)
                .append('}')
        }
        sb.append(']')
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun apiCapture(): ByteArray {
        val s = CaptureController.stats.value
        val flows = s.flows.joinToString(",", prefix = "[", postfix = "]") { f ->
            """{"proto":"${f.proto}","client":"${f.clientIp}:${f.clientPort}","server":"${f.serverIp}:${f.serverPort}","sent":${f.sentBytes},"recv":${f.recvBytes},"state":"${f.state}"}"""
        }
        val domains = s.topDomains.joinToString(",", prefix = "[", postfix = "]") { (d, c) -> """{"domain":"$d","count":$c}""" }
        val http = s.httpRequests.joinToString(",", prefix = "[", postfix = "]") { "\"" + it.replace("\"", "'") + "\"" }
        return """{"running":${s.running},"packets":${s.totalPackets},"bytes":${s.totalBytes},"tcp":${s.tcpPackets},"udp":${s.udpPackets},"icmp":${s.icmpPackets},"flows":$flows,"domains":$domains,"http":$http}"""
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun respond(out: java.io.OutputStream, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun respondFile(out: java.io.OutputStream, f: File, mime: String) {
        val body = f.readBytes()
        respond(out, mime, body)
    }

    private fun respond404(out: java.io.OutputStream) {
        respond(out, "text/plain", "404 not found".toByteArray())
    }

    private fun dashboardHtml(): String = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VicinityProbe Console</title>
<style>
:root{color-scheme:dark}
body{font-family:ui-monospace,Menlo,monospace;background:#0b1420;color:#d8e2ea;margin:0;padding:24px}
h1{font-size:20px;color:#4dd0e1}
h2{font-size:14px;color:#8fb7c7;margin-top:28px;border-bottom:1px solid #1e3045;padding-bottom:6px}
.card{background:#12202f;border:1px solid #1e3045;border-radius:8px;padding:14px;margin:10px 0}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:10px}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{padding:6px 8px;text-align:left;border-bottom:1px solid #16283a}
a{color:#4dd0e1;text-decoration:none}
.badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px}
.exc{background:#14532d;color:#86efac}.good{background:#1e3a8a;color:#93c5fd}
.deg{background:#7c2d12;color:#fdba74}.fail{background:#7f1d1d;color:#fca5a5}
.risk{color:#fdba74;font-size:12px}
button{background:#0e7490;color:#fff;border:none;border-radius:6px;padding:8px 14px;cursor:pointer;font-size:13px}
button:disabled{opacity:.4}
input,select{padding:6px;background:#0b1420;color:#d8e2ea;border:1px solid #1e3045;border-radius:6px}
</style>
</head>
<body>
<h1>VicinityProbe Console</h1>
<p style="color:#64748b">LAN console · reports / raw data / capture / remote scan — use only on networks you are authorized to inspect.</p>
<h2>Remote scan</h2>
<div class="card">
<select id="mode"><option value="FULL">Full scan (all supported)</option><option value="SELECTED">Selected probes</option></select>
<select id="duration"><option value="5000">5s</option><option value="10000" selected>10s</option><option value="30000">30s</option><option value="60000">60s</option></select>
<button onclick="runScan()">Trigger scan</button>
<span id="scanMsg" style="color:#86efac;font-size:12px"></span>
</div>
<h2>Reports</h2>
<div class="grid" id="reports"></div>
<h2>Live capture</h2>
<div class="card" id="capture">not running</div>
<h2>Probe capabilities</h2>
<div class="card" id="caps" style="font-size:12px;max-height:240px;overflow:auto"></div>
<script>
async function j(u){const r=await fetch(u);return r.json()}
function esc(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;')}
async function loadReports(){
  const list=await j('/api/reports');
  document.getElementById('reports').innerHTML=list.map(m=>{
    const b=(n,c)=>'<span class="badge '+c+'">'+n+'</span>';
    return '<div class="card"><b>'+esc(m.name)+'</b><br><span style="color:#64748b">'+new Date(m.createdAt).toLocaleString()+' · '+m.probeCount+' probes</span><br>'+
      b('E '+m.excellentCount,'exc')+' '+b('D '+m.degradedCount,'deg')+' '+b('F '+m.failedCount,'fail')+
      '<br><a href="/api/report/'+m.id+'" target="_blank">JSON</a> · <a href="/download/report/'+m.id+'">file</a> · <a href="/download/samples/'+m.id+'/">samples</a></div>'
  }).join('');
}
async function loadCapture(){
  const s=await j('/api/capture');
  const el=document.getElementById('capture');
  if(!s.running){el.innerHTML='not running';return}
  el.innerHTML='<b style="color:#86efac">RUNNING</b> · packets '+s.packets+' · bytes '+s.bytes+' · TCP '+s.tcp+' · UDP '+s.udp+' · ICMP '+s.icmp+'<br><a href="/download/pcap">download pcap</a>'+
    '<table><tr><th>flow</th><th>sent</th><th>recv</th><th>state</th></tr>'+
    s.flows.map(f=>'<tr><td>'+esc(f.client+' → '+f.server)+'</td><td>'+f.sent+'</td><td>'+f.recv+'</td><td>'+f.state+'</td></tr>').join('')+
    '</table>';
}
async function loadCaps(){
  const c=await j('/api/capabilities');
  document.getElementById('caps').innerHTML=c.filter(x=>x.status==='SUPPORTED').map(x=>esc(x.id)+(x.risk?' ⚠️':'')).join(' · ');
}
async function runScan(){
  const mode=document.getElementById('mode').value;
  const duration=document.getElementById('duration').value;
  let ids='';
  if(mode==='SELECTED'){
    const c=await j('/api/capabilities');
    ids=c.filter(x=>x.status==='SUPPORTED'&&x.risk===false).map(x=>x.id).join(',');
  }
  const body=new URLSearchParams({ids:ids,duration:duration}).toString();
  const r=await fetch('/api/scan',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body});
  const res=await r.json();
  document.getElementById('scanMsg').textContent=res.ok?'scan triggered ('+res.probes+' probes)':'error';
  setTimeout(loadReports,15000);
}
loadReports();loadCapture();loadCaps();
setInterval(()=>{loadCapture()},3000);
setInterval(()=>{loadReports()},10000);
</script>
</body>
</html>
""".trimIndent()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Web console", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, WebServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VicinityProbe · Web console")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}

/** 轻量 JSON 编码(避免序列化依赖) */
object JsonReportList {
    fun encode(metas: List<com.vicinityprobe.report.ReportMeta>): String {
        val sb = StringBuilder("[")
        metas.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(m.id)
                .append("\",\"name\":\"").append(m.name.replace("\"", "\\\""))
                .append("\",\"createdAt\":").append(m.createdAt)
                .append(",\"probeCount\":").append(m.probeCount)
                .append(",\"excellentCount\":").append(m.excellentCount)
                .append(",\"degradedCount\":").append(m.degradedCount)
                .append(",\"failedCount\":").append(m.failedCount)
                .append(",\"samplesKept\":").append(m.samplesKept)
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }
}
