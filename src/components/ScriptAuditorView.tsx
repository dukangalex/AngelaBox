import React, { useState } from 'react';
import {
  ShieldAlert,
  ShieldCheck,
  CheckCircle2,
  AlertTriangle,
  FileCode,
  Terminal,
  Download,
  Copy,
  Check,
  Cpu,
  Sparkles,
  Lock,
  ArrowRight,
  RefreshCw,
} from 'lucide-react';
import { HARDENED_SCRIPTS, auditScript } from '../services/scriptsAudit';
import { ScriptAuditReport } from '../types/proxy';

export const ScriptAuditorView: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'systemd' | 'linuxBash' | 'windowsPowerShell' | 'autoUpdater' | 'custom'>('systemd');
  const [customCode, setCustomCode] = useState<string>(`#!/bin/bash
# 待审计示例脚本
curl -k https://sub.example.com/api -o /etc/angelabox/config.json
chmod 777 /etc/angelabox/config.json
sing-box run -c /etc/angelabox/config.json
`);
  const [customType, setCustomType] = useState<'bash' | 'powershell' | 'systemd' | 'config'>('bash');
  const [copied, setCopied] = useState(false);

  // Current active script content
  const currentContent = activeTab === 'custom' ? customCode : HARDENED_SCRIPTS[activeTab];

  // Audit report
  const auditReport: ScriptAuditReport = React.useMemo(() => {
    if (activeTab === 'custom') {
      return auditScript(customCode, customType);
    }
    const mapType = {
      systemd: 'systemd' as const,
      linuxBash: 'bash' as const,
      windowsPowerShell: 'powershell' as const,
      autoUpdater: 'bash' as const,
    };
    return auditScript(HARDENED_SCRIPTS[activeTab], mapType[activeTab]);
  }, [activeTab, customCode, customType]);

  const handleCopy = () => {
    navigator.clipboard.writeText(currentContent);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    const filenames: Record<string, string> = {
      systemd: 'sing-box.service',
      linuxBash: 'angelabox-run.sh',
      windowsPowerShell: 'angelabox-run.ps1',
      autoUpdater: 'angelabox-update.sh',
      custom: 'custom-script.sh',
    };
    const blob = new Blob([currentContent], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filenames[activeTab];
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleFixCustomScript = () => {
    if (customType === 'bash') {
      setCustomCode(HARDENED_SCRIPTS.linuxBash);
    } else if (customType === 'systemd') {
      setCustomCode(HARDENED_SCRIPTS.systemd);
    } else if (customType === 'powershell') {
      setCustomCode(HARDENED_SCRIPTS.windowsPowerShell);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-5 shadow-lg">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-white flex items-center gap-2">
              <ShieldCheck className="w-5 h-5 text-emerald-400" />
              脚本与配置深度安全审计与加固中心
            </h2>
            <p className="text-xs text-zinc-400 mt-1">
              对 AngelaBox 默认部署、运行保活、TUN 网卡提权、自动更新脚本及自定义配置进行严格安全审计与加固优化
            </p>
          </div>

          {/* Audit Score Pill */}
          <div className="flex items-center gap-3">
            <div className="px-4 py-2 rounded-xl bg-zinc-950 border border-zinc-800 flex items-center gap-3">
              <div className="text-right">
                <div className="text-[10px] text-zinc-500 uppercase font-semibold">安全加固健康指数</div>
                <div
                  className={`text-lg font-mono font-black ${
                    auditReport.score >= 90
                      ? 'text-emerald-400'
                      : auditReport.score >= 70
                      ? 'text-yellow-400'
                      : 'text-red-400'
                  }`}
                >
                  {auditReport.score} / 100
                </div>
              </div>
              <div
                className={`w-3.5 h-3.5 rounded-full ${
                  auditReport.score >= 90
                    ? 'bg-emerald-500'
                    : auditReport.score >= 70
                    ? 'bg-yellow-500'
                    : 'bg-red-500'
                } animate-pulse`}
              ></div>
            </div>
          </div>
        </div>

        {/* Script Selection Tabs */}
        <div className="flex flex-wrap gap-2 mt-5 pt-4 border-t border-zinc-800">
          <button
            onClick={() => setActiveTab('systemd')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'systemd'
                ? 'bg-emerald-600 text-white'
                : 'bg-zinc-950 text-zinc-400 hover:text-white border border-zinc-800'
            }`}
          >
            <Lock className="w-3.5 h-3.5" />
            1. Linux Systemd 守护单元 (sing-box.service)
          </button>
          <button
            onClick={() => setActiveTab('linuxBash')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'linuxBash'
                ? 'bg-emerald-600 text-white'
                : 'bg-zinc-950 text-zinc-400 hover:text-white border border-zinc-800'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            2. Linux 启动保活脚本 (angelabox-run.sh)
          </button>
          <button
            onClick={() => setActiveTab('windowsPowerShell')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'windowsPowerShell'
                ? 'bg-emerald-600 text-white'
                : 'bg-zinc-950 text-zinc-400 hover:text-white border border-zinc-800'
            }`}
          >
            <Cpu className="w-3.5 h-3.5" />
            3. Windows PowerShell 控制器 (.ps1)
          </button>
          <button
            onClick={() => setActiveTab('autoUpdater')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'autoUpdater'
                ? 'bg-emerald-600 text-white'
                : 'bg-zinc-950 text-zinc-400 hover:text-white border border-zinc-800'
            }`}
          >
            <RefreshCw className="w-3.5 h-3.5" />
            4. 原子化订阅安全更新 (angelabox-update.sh)
          </button>
          <button
            onClick={() => setActiveTab('custom')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'custom'
                ? 'bg-violet-600 text-white'
                : 'bg-zinc-950 text-zinc-400 hover:text-white border border-zinc-800'
            }`}
          >
            <Sparkles className="w-3.5 h-3.5 text-violet-400" />
            5. 自定义脚本/配置在线审计
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Audit Report List (5 cols) */}
        <div className="lg:col-span-5 space-y-4">
          <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-5 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold text-white flex items-center gap-2">
                <FileCode className="w-4 h-4 text-emerald-400" />
                审计诊断项与加固指引 ({auditReport.findings.length})
              </h3>
              <span className="text-[11px] text-zinc-500 font-mono">
                {auditReport.auditedLines} 行代码
              </span>
            </div>

            <div className="space-y-3 max-h-[500px] overflow-y-auto pr-1">
              {auditReport.findings.map((f, i) => (
                <div
                  key={i}
                  className={`p-3.5 rounded-xl border text-xs space-y-1.5 ${
                    f.level === 'critical'
                      ? 'bg-red-950/40 border-red-800/80 text-red-200'
                      : f.level === 'warning'
                      ? 'bg-yellow-950/40 border-yellow-800/80 text-yellow-200'
                      : f.level === 'pass'
                      ? 'bg-emerald-950/40 border-emerald-800/80 text-emerald-200'
                      : 'bg-zinc-950 border-zinc-800 text-zinc-300'
                  }`}
                >
                  <div className="flex items-center justify-between font-bold">
                    <span className="flex items-center gap-1.5">
                      {f.level === 'critical' && <ShieldAlert className="w-4 h-4 text-red-400" />}
                      {f.level === 'warning' && <AlertTriangle className="w-4 h-4 text-yellow-400" />}
                      {f.level === 'pass' && <CheckCircle2 className="w-4 h-4 text-emerald-400" />}
                      {f.title}
                    </span>
                    {f.line && <span className="text-[10px] font-mono opacity-70">L{f.line}</span>}
                  </div>
                  <p className="text-[11px] opacity-90 leading-relaxed">{f.description}</p>
                  <div className="pt-1 text-[11px] font-medium border-t border-white/10 opacity-95">
                    💡 <strong>建议:</strong> {f.recommendation}
                  </div>
                </div>
              ))}
            </div>

            {activeTab === 'custom' && (
              <button
                onClick={handleFixCustomScript}
                className="w-full py-2.5 px-3 bg-violet-600 hover:bg-violet-500 text-white rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition"
              >
                <Sparkles className="w-4 h-4" />
                一键套用官方生产级安全模板优化
              </button>
            )}
          </div>
        </div>

        {/* Script Viewer / Editor (7 cols) */}
        <div className="lg:col-span-7 bg-zinc-900 border border-zinc-800 rounded-2xl p-5 flex flex-col justify-between space-y-4">
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-white font-mono">
                {activeTab === 'systemd' && '/etc/systemd/system/sing-box.service'}
                {activeTab === 'linuxBash' && 'angelabox-run.sh (Linux/macOS)'}
                {activeTab === 'windowsPowerShell' && 'angelabox-run.ps1 (Windows)'}
                {activeTab === 'autoUpdater' && 'angelabox-update.sh (Cron Job)'}
                {activeTab === 'custom' && '自定义待检脚本 / 配置文件'}
              </span>

              {activeTab === 'custom' && (
                <select
                  value={customType}
                  onChange={(e) => setCustomType(e.target.value as any)}
                  className="px-2.5 py-1 bg-zinc-950 border border-zinc-800 rounded-lg text-xs text-zinc-300"
                >
                  <option value="bash">Bash 脚本</option>
                  <option value="systemd">Systemd 服务文件</option>
                  <option value="powershell">PowerShell 脚本</option>
                  <option value="config">Sing-box JSON 配置</option>
                </select>
              )}
            </div>

            {activeTab === 'custom' ? (
              <textarea
                rows={18}
                value={customCode}
                onChange={(e) => setCustomCode(e.target.value)}
                placeholder="在此粘贴任意脚本或配置，即时完成安全性审计..."
                className="w-full p-3.5 bg-zinc-950 border border-zinc-800 rounded-xl text-xs font-mono text-zinc-200 placeholder-zinc-600 focus:outline-none focus:border-violet-500 leading-relaxed"
              />
            ) : (
              <pre className="p-4 bg-zinc-950 border border-zinc-800 rounded-xl text-[11px] font-mono text-emerald-400 overflow-x-auto max-h-[500px] overflow-y-auto leading-relaxed select-all">
                {currentContent}
              </pre>
            )}
          </div>

          <div className="flex items-center justify-between pt-2 border-t border-zinc-800 text-xs">
            <span className="text-zinc-500">
              {activeTab !== 'custom'
                ? '已默认通过 CAP_NET_ADMIN 权限收敛与沙箱隔离测试'
                : '在线静态审计器实时检测'}
            </span>

            <div className="flex items-center gap-2">
              <button
                onClick={handleCopy}
                className="px-3.5 py-2 bg-zinc-800 hover:bg-zinc-700 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
              >
                {copied ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                {copied ? '已复制' : '复制脚本'}
              </button>
              <button
                onClick={handleDownload}
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-lg shadow-emerald-950/40"
              >
                <Download className="w-4 h-4" />
                下载保存
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
