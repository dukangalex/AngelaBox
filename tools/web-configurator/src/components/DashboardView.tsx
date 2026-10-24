import React, { useState, useEffect } from 'react';
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  CheckCircle,
  Cpu,
  Globe,
  Radio,
  RefreshCw,
  Shield,
  Zap,
  Sliders,
  Server,
  Layers,
  ChevronRight,
} from 'lucide-react';
import { ConfigOptions, ProxyNode } from '../types/proxy';

interface DashboardViewProps {
  nodes: ProxyNode[];
  activeNodeId: string;
  onSelectActiveNode: (id: string) => void;
  configOptions: ConfigOptions;
  onChangeConfigOptions: (opts: Partial<ConfigOptions>) => void;
  onNavigateTab: (tab: string) => void;
  onTestNodePing: (id: string) => void;
}

export const DashboardView: React.FC<DashboardViewProps> = ({
  nodes,
  activeNodeId,
  onSelectActiveNode,
  configOptions,
  onChangeConfigOptions,
  onNavigateTab,
  onTestNodePing,
}) => {
  const [isRunning, setIsRunning] = useState(true);
  const [downSpeed, setDownSpeed] = useState('1.4 MB/s');
  const [upSpeed, setUpSpeed] = useState('180 KB/s');
  const [totalDownloaded, setTotalDownloaded] = useState('4.82 GB');
  const [totalUploaded, setTotalUploaded] = useState('732 MB');

  // Traffic wave animation simulation
  useEffect(() => {
    const timer = setInterval(() => {
      if (isRunning) {
        const randDown = (Math.random() * 2.8 + 0.3).toFixed(2);
        const randUp = Math.floor(Math.random() * 350 + 50);
        setDownSpeed(`${randDown} MB/s`);
        setUpSpeed(`${randUp} KB/s`);
      }
    }, 2500);
    return () => clearInterval(timer);
  }, [isRunning]);

  const activeNode = nodes.find((n) => n.id === activeNodeId) || nodes[0];

  // Group count by type
  const typeCounts = nodes.reduce((acc, curr) => {
    acc[curr.type] = (acc[curr.type] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);

  return (
    <div className="space-y-6">
      {/* Top Banner / Engine Status */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-zinc-900 via-zinc-900/95 to-zinc-950 border border-zinc-800 p-6 shadow-xl">
        <div className="absolute top-0 right-0 w-96 h-96 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none -mr-20 -mt-20"></div>

        <div className="flex flex-col md:flex-row md:items-center justify-between gap-6 relative z-10">
          <div className="space-y-2">
            <div className="flex items-center gap-3">
              <span className="flex h-3 w-3 relative">
                <span className={`animate-ping absolute inline-flex h-full w-full rounded-full ${isRunning ? 'bg-emerald-400 opacity-75' : 'bg-zinc-600'}`}></span>
                <span className={`relative inline-flex rounded-full h-3 w-3 ${isRunning ? 'bg-emerald-500' : 'bg-zinc-500'}`}></span>
              </span>
              <h1 className="text-xl md:text-2xl font-black tracking-tight text-white flex items-center gap-2">
                AngelaBox
                <span className="text-xs px-2 py-0.5 rounded-full font-mono bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-medium">
                  sing-box v1.11.0 内核
                </span>
              </h1>
            </div>
            <p className="text-xs md:text-sm text-zinc-400 max-w-xl">
              开箱即用的现代化全协议代理中枢。支持原生态导入 Mihomo (Clash)、V2Ray 订阅与本地配置，支持单/混合节点转换、配置聚合与生产级脚本安全加固。
            </p>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsRunning(!isRunning)}
              className={`px-5 py-2.5 rounded-xl text-xs font-bold transition flex items-center gap-2 shadow-lg ${
                isRunning
                  ? 'bg-zinc-800 hover:bg-zinc-700 text-zinc-200 border border-zinc-700'
                  : 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-emerald-950/40'
              }`}
            >
              <Radio className="w-4 h-4 text-emerald-400" />
              {isRunning ? '暂停内核' : '启动代理内核'}
            </button>
            <button
              onClick={() => onNavigateTab('nodes')}
              className="px-5 py-2.5 rounded-xl text-xs font-bold bg-emerald-600 hover:bg-emerald-500 text-white flex items-center gap-2 shadow-lg shadow-emerald-950/40 transition"
            >
              <Server className="w-4 h-4" />
              管理节点池 ({nodes.length})
            </button>
          </div>
        </div>

        {/* Live Mode Controls Row */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-6 pt-6 border-t border-zinc-800/80">
          {/* TUN Mode */}
          <div className="flex items-center justify-between p-3.5 bg-zinc-950/60 rounded-xl border border-zinc-800/80">
            <div className="flex items-center gap-2.5">
              <Shield className={`w-4 h-4 ${configOptions.tunEnabled ? 'text-emerald-400' : 'text-zinc-500'}`} />
              <div>
                <div className="text-xs font-bold text-white">TUN 虚拟网卡接管</div>
                <div className="text-[10px] text-zinc-400 font-mono">
                  {configOptions.tunInterface} ({configOptions.tunStack})
                </div>
              </div>
            </div>
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={configOptions.tunEnabled}
                onChange={(e) => onChangeConfigOptions({ tunEnabled: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-500"></div>
            </label>
          </div>

          {/* System Proxy */}
          <div className="flex items-center justify-between p-3.5 bg-zinc-950/60 rounded-xl border border-zinc-800/80">
            <div className="flex items-center gap-2.5">
              <Globe className="w-4 h-4 text-cyan-400" />
              <div>
                <div className="text-xs font-bold text-white">系统代理环境</div>
                <div className="text-[10px] text-zinc-400 font-mono">
                  127.0.0.1:{configOptions.mixedPort}
                </div>
              </div>
            </div>
            <span className="text-[11px] font-semibold text-cyan-400 bg-cyan-950/40 px-2 py-0.5 rounded border border-cyan-800/50">
              混合监听
            </span>
          </div>

          {/* Routing Mode */}
          <div className="flex items-center justify-between p-3.5 bg-zinc-950/60 rounded-xl border border-zinc-800/80">
            <div className="flex items-center gap-2.5">
              <Sliders className="w-4 h-4 text-violet-400" />
              <div>
                <div className="text-xs font-bold text-white">分流路由模式</div>
                <div className="text-[10px] text-zinc-400 font-mono">
                  {configOptions.routingMode === 'rule'
                    ? '规则分流 (Bypass CN)'
                    : configOptions.routingMode === 'global'
                    ? '全局代理 (Global)'
                    : '全局直连 (Direct)'}
                </div>
              </div>
            </div>
            <select
              value={configOptions.routingMode}
              onChange={(e) => onChangeConfigOptions({ routingMode: e.target.value as any })}
              className="text-xs bg-zinc-900 border border-zinc-700 text-zinc-200 rounded-lg px-2 py-1 focus:outline-none"
            >
              <option value="rule">规则分流</option>
              <option value="global">全局代理</option>
              <option value="direct">全局直连</option>
            </select>
          </div>
        </div>
      </div>

      {/* Grid: Traffic & Active Node */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Active Node Card (2 cols) */}
        <div className="lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-2xl p-6 space-y-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Zap className="w-4 h-4 text-amber-400" />
              <h2 className="text-sm font-bold text-white">当前活动出站节点 (Selector Detour)</h2>
            </div>
            {activeNode && (
              <button
                onClick={() => onTestNodePing(activeNode.id)}
                className="text-xs text-zinc-400 hover:text-white flex items-center gap-1 transition"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                重新测试当前延迟
              </button>
            )}
          </div>

          {activeNode ? (
            <div className="p-4 bg-zinc-950 rounded-xl border border-zinc-800 space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <span className="text-3xl">{activeNode.flag}</span>
                  <div>
                    <h3 className="text-base font-bold text-white flex items-center gap-2">
                      {activeNode.tag}
                    </h3>
                    <div className="text-xs text-zinc-400 font-mono flex items-center gap-2 mt-0.5">
                      <span className="uppercase px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-200 font-semibold text-[10px]">
                        {activeNode.type}
                      </span>
                      <span>
                        {activeNode.server}:{activeNode.server_port}
                      </span>
                      {activeNode.tls?.reality?.enabled && (
                        <span className="text-emerald-400 text-[10px] font-semibold">
                          [Reality 伪装: {activeNode.tls.server_name}]
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-3 shrink-0">
                  <div className="text-right">
                    <div className="text-[10px] text-zinc-400">网络握手延迟</div>
                    <div
                      className={`text-sm font-mono font-bold ${
                        (activeNode.latency || 0) < 60
                          ? 'text-emerald-400'
                          : (activeNode.latency || 0) < 150
                          ? 'text-yellow-400'
                          : 'text-zinc-400'
                      }`}
                    >
                      {activeNode.latency ? `${activeNode.latency} ms` : '未测速'}
                    </div>
                  </div>
                  <span className="w-3 h-3 rounded-full bg-emerald-500 animate-pulse"></span>
                </div>
              </div>

              {/* Protocol Spec Features */}
              <div className="flex flex-wrap gap-2 pt-2 border-t border-zinc-900 text-[11px] text-zinc-400">
                <span className="px-2 py-0.5 rounded bg-zinc-900 border border-zinc-800">
                  来源: {activeNode.source || '内置聚合'}
                </span>
                {activeNode.flow && (
                  <span className="px-2 py-0.5 rounded bg-zinc-900 border border-zinc-800 text-cyan-400">
                    Flow: {activeNode.flow}
                  </span>
                )}
                {activeNode.congestion_control && (
                  <span className="px-2 py-0.5 rounded bg-zinc-900 border border-zinc-800 text-violet-400">
                    BBR 拥塞控制
                  </span>
                )}
                {activeNode.transport?.type && (
                  <span className="px-2 py-0.5 rounded bg-zinc-900 border border-zinc-800 text-amber-400">
                    传输层: {activeNode.transport.type.toUpperCase()}
                  </span>
                )}
                {activeNode.up_mbps && (
                  <span className="px-2 py-0.5 rounded bg-zinc-900 border border-zinc-800 text-rose-400">
                    带宽: ↑{activeNode.up_mbps}M / ↓{activeNode.down_mbps}M
                  </span>
                )}
              </div>
            </div>
          ) : (
            <div className="text-center py-8 text-zinc-500 text-xs">暂无选定节点</div>
          )}

          {/* Quick Select Carousel / Grid */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-semibold text-zinc-300">快速切换出站节点</span>
              <button
                onClick={() => onNavigateTab('nodes')}
                className="text-xs text-emerald-400 hover:text-emerald-300 flex items-center gap-1 transition"
              >
                查看全部 ({nodes.length}) <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {nodes.slice(0, 6).map((node) => (
                <button
                  key={node.id}
                  onClick={() => onSelectActiveNode(node.id)}
                  className={`flex items-center justify-between p-2.5 rounded-xl border text-left transition ${
                    node.id === activeNodeId
                      ? 'bg-emerald-950/30 border-emerald-500/80 text-white'
                      : 'bg-zinc-950/70 border-zinc-800/80 text-zinc-300 hover:bg-zinc-800/50'
                  }`}
                >
                  <div className="flex items-center gap-2 truncate">
                    <span>{node.flag}</span>
                    <span className="text-xs font-medium truncate">{node.tag}</span>
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <span className="text-[10px] font-mono px-1 py-0.5 rounded bg-zinc-800 text-zinc-400 uppercase">
                      {node.type}
                    </span>
                    <span className="text-[10px] font-mono text-zinc-400">
                      {node.latency ? `${node.latency}ms` : '--'}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Traffic Monitor Card (1 col) */}
        <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 space-y-5 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Activity className="w-4 h-4 text-emerald-400" />
                <h2 className="text-sm font-bold text-white">实时内核流量监视</h2>
              </div>
              <span className="text-[10px] font-mono text-emerald-400 bg-emerald-950/50 px-2 py-0.5 rounded border border-emerald-800/40">
                LIVE
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3 mb-5">
              <div className="p-3.5 bg-zinc-950 rounded-xl border border-zinc-800">
                <div className="flex items-center gap-1.5 text-xs text-zinc-400 mb-1">
                  <ArrowDownRight className="w-4 h-4 text-emerald-400" />
                  下载速率
                </div>
                <div className="text-lg font-bold text-white font-mono">{downSpeed}</div>
                <div className="text-[10px] text-zinc-500 mt-1">总计: {totalDownloaded}</div>
              </div>

              <div className="p-3.5 bg-zinc-950 rounded-xl border border-zinc-800">
                <div className="flex items-center gap-1.5 text-xs text-zinc-400 mb-1">
                  <ArrowUpRight className="w-4 h-4 text-cyan-400" />
                  上传速率
                </div>
                <div className="text-lg font-bold text-white font-mono">{upSpeed}</div>
                <div className="text-[10px] text-zinc-500 mt-1">总计: {totalUploaded}</div>
              </div>
            </div>

            {/* Protocol Distribution Summary */}
            <div className="space-y-2">
              <div className="text-xs font-semibold text-zinc-300 flex items-center justify-between">
                <span>协议库覆盖分布</span>
                <span className="text-[11px] text-zinc-500 font-mono">{nodes.length} 节点在载</span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {Object.entries(typeCounts).map(([type, cnt]) => (
                  <span
                    key={type}
                    className="text-[10px] font-mono px-2 py-1 rounded-lg bg-zinc-950 border border-zinc-800 text-zinc-300 flex items-center gap-1"
                  >
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                    <span className="uppercase font-semibold">{type}</span>: {cnt}
                  </span>
                ))}
              </div>
            </div>
          </div>

          <div className="p-3.5 bg-zinc-950/80 rounded-xl border border-zinc-800 text-[11px] text-zinc-400 space-y-1">
            <div className="flex justify-between">
              <span>本地 DNS 服务</span>
              <span className="text-emerald-400 font-mono">FakeIP (198.18.0.0/15)</span>
            </div>
            <div className="flex justify-between">
              <span>Clash API 控制器</span>
              <span className="text-zinc-300 font-mono">127.0.0.1:{configOptions.clashApiPort}</span>
            </div>
            <div className="flex justify-between">
              <span>防 DNS 泄露</span>
              <span className="text-emerald-400 font-mono">双轨隔离已激活</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
