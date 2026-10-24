import React from 'react';
import {
  Activity,
  Layers,
  Database,
  Sliders,
  ArrowRightLeft,
  ShieldCheck,
  Zap,
  Globe,
  Radio,
  ExternalLink,
} from 'lucide-react';

interface SidebarProps {
  currentTab: string;
  onSelectTab: (tab: string) => void;
  nodeCount: number;
  subCount: number;
}

export const Sidebar: React.FC<SidebarProps> = ({
  currentTab,
  onSelectTab,
  nodeCount,
  subCount,
}) => {
  const menuItems = [
    {
      id: 'dashboard',
      label: '控制面板',
      icon: Activity,
      badge: null,
    },
    {
      id: 'nodes',
      label: '节点聚合中心',
      icon: Layers,
      badge: `${nodeCount}`,
    },
    {
      id: 'subscriptions',
      label: '订阅与配置池',
      icon: Database,
      badge: `${subCount}`,
    },
    {
      id: 'generator',
      label: '配置工坊 (编排)',
      icon: Sliders,
      badge: null,
    },
    {
      id: 'converter',
      label: '全协议转换器',
      icon: ArrowRightLeft,
      badge: null,
    },
    {
      id: 'auditor',
      label: '脚本安全审计',
      icon: ShieldCheck,
      badge: 'PRO',
      badgeColor: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30',
    },
  ];

  return (
    <aside className="w-64 bg-zinc-950 border-r border-zinc-800/80 flex flex-col justify-between shrink-0 select-none">
      <div className="p-4 space-y-6">
        {/* Brand Header */}
        <div className="flex items-center gap-3 px-2 py-1">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 flex items-center justify-center shadow-lg shadow-emerald-950/50">
            <Radio className="w-5 h-5 text-zinc-950 font-bold" />
          </div>
          <div>
            <div className="font-black text-sm tracking-wide text-white flex items-center gap-1.5">
              AngelaBox
              <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
            </div>
            <div className="text-[10px] text-zinc-400 font-mono">
              sing-box Proxy Client
            </div>
          </div>
        </div>

        {/* Navigation Items */}
        <nav className="space-y-1">
          {menuItems.map((item) => {
            const Icon = item.icon;
            const isActive = currentTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => onSelectTab(item.id)}
                className={`w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-xs font-semibold transition ${
                  isActive
                    ? 'bg-emerald-600 text-white shadow-md shadow-emerald-950/40'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-900/80'
                }`}
              >
                <div className="flex items-center gap-3">
                  <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-zinc-400'}`} />
                  <span>{item.label}</span>
                </div>
                {item.badge && (
                  <span
                    className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-full ${
                      item.badgeColor ||
                      (isActive
                        ? 'bg-emerald-700/80 text-white'
                        : 'bg-zinc-900 text-zinc-400 border border-zinc-800')
                    }`}
                  >
                    {item.badge}
                  </span>
                )}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Footer Info Box */}
      <div className="p-4 border-t border-zinc-800/80 text-[11px] text-zinc-500 space-y-2">
        <div className="flex items-center justify-between">
          <span>Sing-box Core</span>
          <span className="text-emerald-400 font-mono font-semibold">v1.11.0</span>
        </div>
        <div className="flex items-center justify-between">
          <span>Mihomo/Clash 兼容</span>
          <span className="text-cyan-400 font-mono">Meta v1.18+</span>
        </div>
        <div className="pt-2 text-[10px] text-zinc-600 border-t border-zinc-900 flex justify-between items-center">
          <span>dukangalex/AngelaBox</span>
          <span className="text-zinc-500">2026 Ready</span>
        </div>
      </div>
    </aside>
  );
};
