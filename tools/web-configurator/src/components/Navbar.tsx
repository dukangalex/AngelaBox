import React from 'react';
import {
  Menu,
  Zap,
  Sparkles,
  Download,
  Github,
  CheckCircle2,
  Radio,
  Shield,
} from 'lucide-react';
import { ConfigOptions, ProxyNode } from '../types/proxy';

interface NavbarProps {
  onToggleMobileSidebar: () => void;
  onOpenImport: () => void;
  onTestAllPings: () => void;
  isPingingAll: boolean;
  onExportConfig: () => void;
  configOptions: ConfigOptions;
  activeNodeName?: string;
}

export const Navbar: React.FC<NavbarProps> = ({
  onToggleMobileSidebar,
  onOpenImport,
  onTestAllPings,
  isPingingAll,
  onExportConfig,
  configOptions,
  activeNodeName,
}) => {
  return (
    <header className="h-16 border-b border-zinc-800 bg-zinc-950/80 backdrop-blur-md px-4 md:px-6 flex items-center justify-between z-30 shrink-0">
      {/* Left items */}
      <div className="flex items-center gap-3">
        <button
          onClick={onToggleMobileSidebar}
          className="md:hidden p-2 text-zinc-400 hover:text-white rounded-lg hover:bg-zinc-800"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Status Indicators */}
        <div className="hidden sm:flex items-center gap-2 text-xs">
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-950/60 border border-emerald-800/50 text-emerald-300 font-medium">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
            内核运行中 (sing-box)
          </div>

          {configOptions.tunEnabled && (
            <div className="flex items-center gap-1 px-2 py-1 rounded-full bg-cyan-950/40 border border-cyan-800/40 text-cyan-300 text-[11px] font-mono">
              <Shield className="w-3 h-3" />
              TUN: {configOptions.tunInterface}
            </div>
          )}

          {activeNodeName && (
            <div className="hidden lg:flex items-center gap-1 px-2.5 py-1 rounded-full bg-zinc-900 border border-zinc-800 text-zinc-300 text-[11px] truncate max-w-xs">
              <span className="text-zinc-500">出站:</span>
              <span className="text-white font-medium truncate">{activeNodeName}</span>
            </div>
          )}
        </div>
      </div>

      {/* Right Action buttons */}
      <div className="flex items-center gap-2.5">
        <button
          onClick={onTestAllPings}
          disabled={isPingingAll}
          className="px-3 py-1.5 bg-zinc-900 hover:bg-zinc-800 disabled:opacity-50 text-zinc-200 border border-zinc-800 rounded-xl text-xs font-semibold flex items-center gap-1.5 transition"
        >
          <Zap className={`w-3.5 h-3.5 text-amber-400 ${isPingingAll ? 'animate-bounce' : ''}`} />
          <span className="hidden sm:inline">{isPingingAll ? '测速中...' : '全局测速'}</span>
        </button>

        <button
          onClick={onOpenImport}
          className="px-3.5 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-md shadow-emerald-950/40"
        >
          <Sparkles className="w-3.5 h-3.5" />
          <span>导入配置</span>
        </button>

        <button
          onClick={onExportConfig}
          className="hidden sm:flex px-3.5 py-1.5 bg-zinc-900 hover:bg-zinc-800 text-zinc-200 border border-zinc-800 rounded-xl text-xs font-semibold items-center gap-1.5 transition"
        >
          <Download className="w-3.5 h-3.5 text-cyan-400" />
          <span>导出配置</span>
        </button>

        <a
          href="https://github.com/dukangalex/AngelaBox"
          target="_blank"
          rel="noopener noreferrer"
          title="GitHub dukangalex/AngelaBox"
          className="p-2 text-zinc-400 hover:text-white rounded-xl hover:bg-zinc-900 transition"
        >
          <Github className="w-4 h-4" />
        </a>
      </div>
    </header>
  );
};
