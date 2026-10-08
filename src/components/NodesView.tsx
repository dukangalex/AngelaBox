import React, { useState, useMemo } from 'react';
import {
  Search,
  Filter,
  Plus,
  QrCode,
  Edit2,
  Trash2,
  Zap,
  Download,
  Copy,
  Check,
  Code,
  ArrowUpDown,
  RefreshCw,
  Sparkles,
  Layers,
} from 'lucide-react';
import { ProxyNode, ProxyType } from '../types/proxy';
import { NodeQrModal } from './NodeQrModal';
import { NodeEditorModal } from './NodeEditorModal';
import { nodeToSingboxOutbound, nodeToUri } from '../services/generator';

interface NodesViewProps {
  nodes: ProxyNode[];
  onUpdateNodes: (nodes: ProxyNode[]) => void;
  onOpenImport: () => void;
  onTestNodePing: (id: string) => void;
  onTestAllPings: () => void;
  isPingingAll: boolean;
}

export const NodesView: React.FC<NodesViewProps> = ({
  nodes,
  onUpdateNodes,
  onOpenImport,
  onTestNodePing,
  onTestAllPings,
  isPingingAll,
}) => {
  const [search, setSearch] = useState('');
  const [selectedProtocol, setSelectedProtocol] = useState<string>('all');
  const [selectedRegion, setSelectedRegion] = useState<string>('all');
  const [sortBy, setSortBy] = useState<'latency' | 'tag' | 'type'>('latency');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // Modal states
  const [qrNode, setQrNode] = useState<ProxyNode | null>(null);
  const [editingNode, setEditingNode] = useState<ProxyNode | null>(null);
  const [isEditorOpen, setIsEditorOpen] = useState(false);

  // Filter protocols
  const protocols: { label: string; value: string; color: string }[] = [
    { label: '全部协议', value: 'all', color: 'bg-zinc-800 text-zinc-200' },
    { label: 'VLESS (Reality)', value: 'vless', color: 'bg-purple-900/40 text-purple-300' },
    { label: 'Hysteria 2', value: 'hysteria2', color: 'bg-rose-900/40 text-rose-300' },
    { label: 'TUIC v5', value: 'tuic', color: 'bg-blue-900/40 text-blue-300' },
    { label: 'Shadowsocks', value: 'shadowsocks', color: 'bg-emerald-900/40 text-emerald-300' },
    { label: 'VMess', value: 'vmess', color: 'bg-amber-900/40 text-amber-300' },
    { label: 'Trojan', value: 'trojan', color: 'bg-indigo-900/40 text-indigo-300' },
    { label: 'WireGuard', value: 'wireguard', color: 'bg-cyan-900/40 text-cyan-300' },
  ];

  // Regions
  const regions = [
    { label: '全部地区', value: 'all' },
    { label: '🇭🇰 香港', value: '🇭🇰' },
    { label: '🇯🇵 日本', value: '🇯🇵' },
    { label: '🇸🇬 新加坡', value: '🇸🇬' },
    { label: '🇺🇸 美国', value: '🇺🇸' },
    { label: '🇹🇼 台湾', value: '🇹🇼' },
    { label: '🇰🇷 韩国', value: '🇰🇷' },
  ];

  // Filtered & Sorted list
  const filteredNodes = useMemo(() => {
    return nodes
      .filter((n) => {
        const matchesSearch =
          n.tag.toLowerCase().includes(search.toLowerCase()) ||
          n.server.toLowerCase().includes(search.toLowerCase()) ||
          n.type.toLowerCase().includes(search.toLowerCase());
        const matchesProto = selectedProtocol === 'all' || n.type === selectedProtocol;
        const matchesRegion = selectedRegion === 'all' || n.flag === selectedRegion;
        return matchesSearch && matchesProto && matchesRegion;
      })
      .sort((a, b) => {
        if (sortBy === 'latency') {
          const latA = a.latency ?? 9999;
          const latB = b.latency ?? 9999;
          return latA - latB;
        }
        if (sortBy === 'type') return a.type.localeCompare(b.type);
        return a.tag.localeCompare(b.tag);
      });
  }, [nodes, search, selectedProtocol, selectedRegion, sortBy]);

  // Batch Selection
  const toggleSelect = (id: string) => {
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelectedIds(next);
  };

  const handleSelectAll = () => {
    if (selectedIds.size === filteredNodes.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(filteredNodes.map((n) => n.id)));
    }
  };

  const handleDeleteSelected = () => {
    if (selectedIds.size === 0) return;
    if (confirm(`确定要删除选中的 ${selectedIds.size} 个节点吗？`)) {
      onUpdateNodes(nodes.filter((n) => !selectedIds.has(n.id)));
      setSelectedIds(new Set());
    }
  };

  const handleDeleteSingle = (id: string) => {
    onUpdateNodes(nodes.filter((n) => n.id !== id));
    if (selectedIds.has(id)) {
      const next = new Set(selectedIds);
      next.delete(id);
      setSelectedIds(next);
    }
  };

  const handleSaveNode = (savedNode: ProxyNode) => {
    const exists = nodes.some((n) => n.id === savedNode.id);
    if (exists) {
      onUpdateNodes(nodes.map((n) => (n.id === savedNode.id ? savedNode : n)));
    } else {
      onUpdateNodes([savedNode, ...nodes]);
    }
  };

  const handleExportSelectedUris = () => {
    const targetNodes =
      selectedIds.size > 0 ? nodes.filter((n) => selectedIds.has(n.id)) : filteredNodes;
    const uris = targetNodes.map(nodeToUri).join('\n');
    navigator.clipboard.writeText(uris);
    alert(`已将 ${targetNodes.length} 个节点的分享链接复制到剪贴板！`);
  };

  const handleExportSelectedSingbox = () => {
    const targetNodes =
      selectedIds.size > 0 ? nodes.filter((n) => selectedIds.has(n.id)) : filteredNodes;
    const outbounds = targetNodes.map(nodeToSingboxOutbound);
    const jsonStr = JSON.stringify(outbounds, null, 2);
    const blob = new Blob([jsonStr], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `singbox-outbounds-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-6">
      {/* Top Action Bar */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-zinc-900 border border-zinc-800 rounded-2xl p-5 shadow-lg">
        <div>
          <h2 className="text-lg font-bold text-white flex items-center gap-2">
            <Layers className="w-5 h-5 text-emerald-400" />
            节点聚合中心
            <span className="text-xs px-2 py-0.5 rounded-full font-mono bg-zinc-800 text-zinc-300">
              共 {nodes.length} 个节点
            </span>
          </h2>
          <p className="text-xs text-zinc-400 mt-1">
            支持单节点/多节点协议转换、实时并发延迟测速、二维码生成及 sing-box 原生出站导出
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <button
            onClick={onTestAllPings}
            disabled={isPingingAll || nodes.length === 0}
            className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 disabled:opacity-50 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
          >
            <Zap className={`w-4 h-4 text-amber-400 ${isPingingAll ? 'animate-bounce' : ''}`} />
            {isPingingAll ? '全节点测速中...' : '并发测速全部'}
          </button>

          <button
            onClick={() => {
              setEditingNode(null);
              setIsEditorOpen(true);
            }}
            className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
          >
            <Plus className="w-4 h-4 text-cyan-400" />
            新建节点
          </button>

          <button
            onClick={onOpenImport}
            className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-lg shadow-emerald-950/40"
          >
            <Sparkles className="w-4 h-4" />
            导入配置 / 订阅
          </button>
        </div>
      </div>

      {/* Filter and Search Controls */}
      <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-4 space-y-4">
        <div className="flex flex-col sm:flex-row items-center gap-3">
          {/* Search bar */}
          <div className="relative flex-1 w-full">
            <Search className="w-4 h-4 text-zinc-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              placeholder="搜索节点名称、服务器域名、IP 或协议类型..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition"
            />
          </div>

          {/* Region dropdown */}
          <select
            value={selectedRegion}
            onChange={(e) => setSelectedRegion(e.target.value)}
            className="w-full sm:w-auto px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-zinc-200 focus:outline-none"
          >
            {regions.map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </select>

          {/* Sort order */}
          <div className="flex items-center gap-1.5 w-full sm:w-auto">
            <ArrowUpDown className="w-4 h-4 text-zinc-400 shrink-0" />
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as any)}
              className="w-full sm:w-auto px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-zinc-200 focus:outline-none"
            >
              <option value="latency">按延迟排序 (低到高)</option>
              <option value="tag">按节点名称字母</option>
              <option value="type">按协议类型归类</option>
            </select>
          </div>
        </div>

        {/* Protocol Pills */}
        <div className="flex flex-wrap items-center gap-1.5 pt-1">
          {protocols.map((p) => (
            <button
              key={p.value}
              onClick={() => setSelectedProtocol(p.value)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition ${
                selectedProtocol === p.value
                  ? 'bg-emerald-600 text-white font-semibold'
                  : 'bg-zinc-950 border border-zinc-800 text-zinc-400 hover:text-zinc-200 hover:border-zinc-700'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>

        {/* Batch Operation Bar if any selected */}
        <div className="flex items-center justify-between pt-2 border-t border-zinc-800/80 text-xs text-zinc-400">
          <div className="flex items-center gap-3">
            <button
              onClick={handleSelectAll}
              className="hover:text-white transition font-medium underline"
            >
              {selectedIds.size === filteredNodes.length ? '取消全选' : '全选当前'}
            </button>
            <span>
              已选择 <strong className="text-white">{selectedIds.size}</strong> / {filteredNodes.length} 个
            </span>
          </div>

          {selectedIds.size > 0 && (
            <div className="flex items-center gap-2">
              <button
                onClick={handleExportSelectedUris}
                className="px-2.5 py-1 bg-zinc-800 hover:bg-zinc-700 text-zinc-200 rounded-lg text-xs flex items-center gap-1 transition"
              >
                <Copy className="w-3.5 h-3.5" />
                复制链接
              </button>
              <button
                onClick={handleExportSelectedSingbox}
                className="px-2.5 py-1 bg-zinc-800 hover:bg-zinc-700 text-zinc-200 rounded-lg text-xs flex items-center gap-1 transition"
              >
                <Download className="w-3.5 h-3.5" />
                导出 JSON
              </button>
              <button
                onClick={handleDeleteSelected}
                className="px-2.5 py-1 bg-red-950/60 hover:bg-red-900 border border-red-800 text-red-300 rounded-lg text-xs flex items-center gap-1 transition"
              >
                <Trash2 className="w-3.5 h-3.5" />
                批量删除
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Nodes Grid */}
      {filteredNodes.length === 0 ? (
        <div className="text-center py-16 bg-zinc-900 border border-zinc-800 rounded-2xl">
          <Layers className="w-12 h-12 text-zinc-600 mx-auto mb-3" />
          <p className="text-sm font-medium text-zinc-300">未找到符合条件的节点</p>
          <p className="text-xs text-zinc-500 mt-1">可导入新的订阅链接或粘贴节点文本</p>
          <button
            onClick={onOpenImport}
            className="mt-4 px-4 py-2 bg-emerald-600 text-white rounded-xl text-xs font-semibold inline-flex items-center gap-1.5"
          >
            <Sparkles className="w-4 h-4" />
            立即导入节点
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3.5">
          {filteredNodes.map((node) => {
            const isSelected = selectedIds.has(node.id);
            return (
              <div
                key={node.id}
                className={`group relative bg-zinc-900 border rounded-2xl p-4 transition-all duration-200 flex flex-col justify-between ${
                  isSelected
                    ? 'border-emerald-500/80 bg-zinc-900/90 shadow-md shadow-emerald-950/30'
                    : 'border-zinc-800 hover:border-zinc-700 hover:bg-zinc-850'
                }`}
              >
                {/* Node Top Row */}
                <div>
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <div className="flex items-center gap-2 truncate">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => toggleSelect(node.id)}
                        className="rounded border-zinc-700 text-emerald-600 focus:ring-0 cursor-pointer"
                      />
                      <span className="text-xl shrink-0">{node.flag}</span>
                      <h3 className="text-xs font-bold text-white truncate group-hover:text-emerald-400 transition">
                        {node.tag}
                      </h3>
                    </div>

                    {/* Latency Pill */}
                    <button
                      onClick={() => onTestNodePing(node.id)}
                      title="点击重新测速"
                      className={`shrink-0 px-2 py-0.5 rounded text-[11px] font-mono font-bold flex items-center gap-1 transition ${
                        (node.latency || 0) < 60
                          ? 'bg-emerald-950/80 text-emerald-400 border border-emerald-800/60'
                          : (node.latency || 0) < 180
                          ? 'bg-yellow-950/80 text-yellow-400 border border-yellow-800/60'
                          : node.latency
                          ? 'bg-red-950/80 text-red-400 border border-red-800/60'
                          : 'bg-zinc-800 text-zinc-400 hover:text-white'
                      }`}
                    >
                      <Zap className="w-3 h-3" />
                      {node.latency ? `${node.latency} ms` : '测速'}
                    </button>
                  </div>

                  {/* Server Info */}
                  <div className="text-[11px] text-zinc-400 font-mono flex items-center justify-between mb-2">
                    <span className="truncate">
                      {node.server}:{node.server_port}
                    </span>
                    <span className="uppercase font-semibold px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-300 text-[10px]">
                      {node.type}
                    </span>
                  </div>

                  {/* Protocol Features Badges */}
                  <div className="flex flex-wrap gap-1 mb-3">
                    {node.tls?.reality?.enabled && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-purple-950/60 text-purple-300 border border-purple-800/40">
                        Reality
                      </span>
                    )}
                    {node.flow && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-cyan-950/60 text-cyan-300 border border-cyan-800/40 font-mono">
                        {node.flow}
                      </span>
                    )}
                    {node.congestion_control && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-blue-950/60 text-blue-300 border border-blue-800/40 uppercase">
                        {node.congestion_control}
                      </span>
                    )}
                    {node.transport?.type && node.transport.type !== 'tcp' && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-amber-950/60 text-amber-300 border border-amber-800/40 uppercase">
                        {node.transport.type}
                      </span>
                    )}
                    {node.obfs?.type && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-rose-950/60 text-rose-300 border border-rose-800/40">
                        Obfs
                      </span>
                    )}
                  </div>
                </div>

                {/* Node Bottom Action Toolbar */}
                <div className="flex items-center justify-between pt-2.5 border-t border-zinc-800/80 text-zinc-400">
                  <span className="text-[10px] text-zinc-500 truncate max-w-[120px]">
                    {node.source || '手动添加'}
                  </span>

                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => setQrNode(node)}
                      title="查看二维码 & 分享"
                      className="p-1.5 rounded-lg hover:text-white hover:bg-zinc-800 transition"
                    >
                      <QrCode className="w-3.5 h-3.5 text-cyan-400" />
                    </button>
                    <button
                      onClick={() => {
                        setEditingNode(node);
                        setIsEditorOpen(true);
                      }}
                      title="编辑节点参数"
                      className="p-1.5 rounded-lg hover:text-white hover:bg-zinc-800 transition"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                    <button
                      onClick={() => handleDeleteSingle(node.id)}
                      title="删除节点"
                      className="p-1.5 rounded-lg hover:text-red-400 hover:bg-zinc-800 transition"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* QR Code & Share Modal */}
      <NodeQrModal node={qrNode} onClose={() => setQrNode(null)} />

      {/* Node Editor Modal */}
      <NodeEditorModal
        node={editingNode}
        isOpen={isEditorOpen}
        onClose={() => setIsEditorOpen(false)}
        onSave={handleSaveNode}
      />
    </div>
  );
};
