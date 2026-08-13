/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.trBilingual

/**
 * 安全审计引擎:聚合报告中所有安全探测结果,输出分级风险条目。
 * 等级: INFO / LOW / MEDIUM / HIGH / CRITICAL
 * 只输出客观事实与通用风险规则,不做主观评分。
 */
data class AuditFinding(
    val level: String,
    val category: String,
    val probe: String,
    val detail: String,
)

object SecurityAudit {
    val LEVELS = listOf("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")

    fun audit(report: MeasurementReport): List<AuditFinding> {
        val findings = ArrayList<AuditFinding>()
        val byId = report.measurements.associateBy { it.spec.id }

        // ---- 端口扫描 ----
        byId["net_portscan"]?.let { m ->
            m.attributes["detail"]?.split("\n")?.forEach { line ->
                val port = line.substringBefore("/").toIntOrNull() ?: return@forEach
                val service = PortRisk.risk(port)
                if (service != null) {
                    findings.add(AuditFinding(service.first, "port", "net_portscan",
                        "open ${line.take(80)}${service.second.let { " — $it" }}"))
                }
            }
        }
        // ---- HTTP 方法 ----
        byId["net_http_methods"]?.let { m ->
            m.attributes["risky_allowed"]?.takeIf { it != "none" }?.split(",")?.forEach { method ->
                val level = if (method == "TRACE") "HIGH" else "MEDIUM"
                findings.add(AuditFinding(level, "http", "net_http_methods", "risky method allowed: $method"))
            }
        }
        // ---- 安全头 ----
        byId["net_http_security"]?.let { m ->
            m.attributes["missing_headers"]?.takeIf { it.isNotEmpty() && it != "none" }?.split(",")?.forEach { h ->
                findings.add(AuditFinding("MEDIUM", "http", "net_http_security", "missing security header: $h"))
            }
        }
        // ---- TLS 版本 ----
        byId["net_tls_versions"]?.let { m ->
            if (m.attributes["tls_TLSv1"] == "true") findings.add(AuditFinding("HIGH", "tls", "net_tls_versions", "TLSv1.0 supported (deprecated, weak)"))
            if (m.attributes["tls_TLSv1.1"] == "true") findings.add(AuditFinding("MEDIUM", "tls", "net_tls_versions", "TLSv1.1 supported (deprecated)"))
            if (m.attributes["tls_TLSv1.2"] == "true") findings.add(AuditFinding("INFO", "tls", "net_tls_versions", "TLSv1.2 supported"))
            if (m.attributes["tls_TLSv1.3"] == "true") findings.add(AuditFinding("INFO", "tls", "net_tls_versions", "TLSv1.3 supported"))
        }
        // ---- 证书 ----
        byId["net_http_fingerprint"]?.let { m ->
            m.attributes.entries.filter { it.key.contains("cert") }.forEach { (k, v) ->
                when {
                    v.contains("EXPIRED") -> findings.add(AuditFinding("HIGH", "tls", "net_http_fingerprint", "expired certificate ($k): ${v.take(90)}"))
                    v.contains("WEAK-SIG") -> findings.add(AuditFinding("MEDIUM", "tls", "net_http_fingerprint", "weak signature (SHA1/MD5) ($k): ${v.take(90)}"))
                    v.contains("SELF-SIGNED") -> findings.add(AuditFinding("MEDIUM", "tls", "net_http_fingerprint", "self-signed certificate ($k): ${v.take(90)}"))
                }
            }
        }
        // ---- MQTT ----
        byId["net_mqtt"]?.let { m ->
            if (m.attributes["broker"]?.contains("accepted") == true) {
                findings.add(AuditFinding("HIGH", "iot", "net_mqtt", "MQTT broker accepts anonymous CONNECT"))
            }
        }
        // ---- SMB ----
        byId["net_smb"]?.let { m ->
            val detail = m.attributes["detail"] ?: ""
            if (detail.contains("no-signing")) findings.add(AuditFinding("HIGH", "smb", "net_smb", "SMB signing not required"))
            if (detail.contains("dialect=SMB 2.0.2") || detail.contains("dialect=SMB 2.1")) {
                findings.add(AuditFinding("MEDIUM", "smb", "net_smb", "legacy SMB dialect offered: ${detail.substringAfter("dialect=", "").substringBefore(" |")}"))
            }
        }
        // ---- SSH ----
        byId["net_ssh_ver"]?.let { m ->
            val v = m.attributes["version"] ?: ""
            val vendor = m.attributes["vendor"] ?: ""
            findings.add(AuditFinding("INFO", "ssh", "net_ssh_ver", "$vendor $v exposed"))
        }
        // ---- WiFi 开放网络 ----
        byId["wifi_scan"]?.let { m ->
            m.attributes["open_networks"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
                findings.add(AuditFinding("MEDIUM", "wifi", "wifi_scan", "$it open (unencrypted) network(s) visible"))
            }
        }
        // ---- 网关 ping ----
        byId["net_ping"]?.let { m ->
            m.attributes["rtt_avg_ms"]?.let {
                findings.add(AuditFinding("INFO", "network", "net_ping", "gateway reachable, avg RTT $it ms"))
            }
        }
        return findings.sortedBy { LEVELS.indexOf(it.level) }
    }

    fun markdown(report: MeasurementReport, findings: List<AuditFinding>, lang: String): String {
        val zh = lang.startsWith("zh")
        val tb = { s: String -> trBilingual(s, lang) }
        val sb = StringBuilder()
        sb.append("# VicinityProbe Security Audit\n\n")
        sb.append("> target: `${report.context.device}` · ${report.plan.durationMs / 1000}s · ${report.plan.operator}\n\n")
        val counts = findings.groupBy { it.level }
        sb.append("| Level | Count |\n|---|---|\n")
        LEVELS.forEach { l -> sb.append("| $l | ${counts[l]?.size ?: 0} |\n") }
        sb.append("\n")
        findings.forEach { f ->
            val icon = when (f.level) {
                "CRITICAL" -> "🔴"; "HIGH" -> "🟠"; "MEDIUM" -> "🟡"; "LOW" -> "🔵"; else -> "⚪"
            }
            sb.append("$icon **${f.level}** · ${f.category} · ${f.probe}\n")
            sb.append("  ${f.detail}\n\n")
        }
        if (findings.isEmpty()) sb.append("No findings.\n\n")
        sb.append("---\n")
        sb.append("*Findings are objective facts from active probes. Use only on networks you are authorized to inspect. "
            .let { if (zh) "审计条目为主动探测得到的客观事实,仅限用于你有权检查的网络。" else it.trim() })
        return sb.toString()
    }
}

private object PortRisk {
    fun risk(port: Int): Pair<String, String>? = when (port) {
        22 -> "MEDIUM" to "SSH exposed"
        23 -> "HIGH" to "Telnet (cleartext)"
        21 -> "MEDIUM" to "FTP (cleartext)"
        25 -> "MEDIUM" to "SMTP exposed"
        80, 8080, 8000, 8888, 3000 -> "INFO" to "HTTP service"
        443, 8443 -> "INFO" to "HTTPS service"
        135, 139, 445 -> "HIGH" to "SMB/NetBIOS exposed"
        1433, 1521, 3306, 5432, 6379, 9200, 27017 -> "HIGH" to "database service exposed"
        3389 -> "MEDIUM" to "RDP exposed"
        5900 -> "MEDIUM" to "VNC exposed"
        2375 -> "CRITICAL" to "unencrypted Docker API"
        5601 -> "MEDIUM" to "Kibana exposed"
        11211 -> "HIGH" to "memcached exposed"
        1883 -> "HIGH" to "MQTT exposed"
        49152 -> "MEDIUM" to "DCOM/RPC exposed"
        else -> null
    }
}
