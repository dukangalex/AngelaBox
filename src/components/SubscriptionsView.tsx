import React, { useState } from 'react';
import {
  Link,
  RefreshCw,
  Plus,
  Trash2,
  HardDrive,
  Calendar,
  AlertCircle,
  CheckCircle,
  ExternalLink,
  Layers,
  ArrowRight,
  Database,
  Sparkles,
} from 'lucide-react';
import { Subscription, ProxyNode } from '../types/proxy';
import { parseAnyContent } from '../services/parser';

interface SubscriptionsViewProps {
  subscriptions: Subscription[];
  onUpdateSubscriptions: (subs: Subscription[]) => void;
  onAggregateAllNodes: () => void;
  onOpenImport: () => void;
}

export const SubscriptionsView: React.FC<SubscriptionsViewProps> = ({
  subscriptions,
  onUpdateSubscriptions,
  onAggregateAllNodes,
  onOpenImport,
}) => {
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  // Format bytes to GB / MB
  const formatBytes = (bytes?: number) => {
    if (!bytes || isNaN(bytes)) return '--';
    const gb = bytes / (1024 * 1024 * 1024);
    if (gb >= 1) return `${gb.toFixed(2)} GB`;
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(1)} MB`;
  };

  // Format epoch expiry
  const formatExpire = (timestamp?: number) => {
    if (!timestamp) return '长期有效';
    const date = new Date(timestamp * 1000);
    return date.toLocaleDateString();
  };

  // Update single subscription
  const handleUpdateSubscription = async (sub: Subscription) => {
    setUpdatingId(sub.id);
    try {
      let rawData = '';
      let userInfo: any = null;

      try {
        const resp = await fetch('/api/fetch-subscription', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: sub.url, userAgentType: 'singbox' }),
        });
        if (resp.ok) {
          const json = await resp.json();
          rawData = json.data;
          userInfo = json.userInfo;
        } else {
          throw new Error('Backend proxy error');
        }
      } catch {
        const clientResp = await fetch(sub.url);
        rawData = await clientResp.text();
      }

      const parsedNodes = parseAnyContent(rawData, sub.name);
      if (parsedNodes.length === 0) {
        throw new Error('未解析到有效节点');
      }

      const updatedList = subscriptions.map((s) => {
        if (s.id === sub.id) {
          return {
            ...s,
            status: 'success' as const,
            nodeCount: parsedNodes.length,
            nodes: parsedNodes,
            updatedAt: new Date().toLocaleTimeString(),
            userInfo: userInfo || s.userInfo,
          };
        }
        return s;
      });

      onUpdateSubscriptions(updatedList);
    } catch (err: any) {
      alert(`更新订阅失败: ${err.message}`);
    } finally {
      setUpdatingId(null);
    }
  };

  const handleDeleteSub = (id: string) => {
    if (confirm('确认删除该订阅吗？')) {
      onUpdateSubscriptions(subscriptions.filter((s) => s.id !== id));
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-zinc-900 border border-zinc-800 rounded-2xl p-5 shadow-lg">
        <div>
          <h2 className="text-lg font-bold text-white flex items-center gap-2">
            <Database className="w-5 h-5 text-emerald-400" />
            订阅管理与节点聚合
          </h2>
          <p className="text-xs text-zinc-400 mt-1">
            支持同时载入多个 Clash / V2Ray / Sing-box 机场订阅，自动去重聚合生成统一全协议出站核心
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={onAggregateAllNodes}
            className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
          >
            <Layers className="w-4 h-4 text-cyan-400" />
            一键全聚合同步
          </button>
          <button
            onClick={onOpenImport}
            className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-lg shadow-emerald-950/40"
          >
            <Plus className="w-4 h-4" />
            添加新订阅
          </button>
        </div>
      </div>

      {/* Subscriptions List */}
      {subscriptions.length === 0 ? (
        <div className="text-center py-16 bg-zinc-900 border border-zinc-800 rounded-2xl">
          <Link className="w-12 h-12 text-zinc-600 mx-auto mb-3" />
          <p className="text-sm font-medium text-zinc-300">暂未添加订阅源</p>
          <p className="text-xs text-zinc-500 mt-1">
            您可以添加 Clash 订阅链接或 V2Ray Base64 订阅
          </p>
          <button
            onClick={onOpenImport}
            className="mt-4 px-4 py-2 bg-emerald-600 text-white rounded-xl text-xs font-semibold inline-flex items-center gap-1.5"
          >
            <Sparkles className="w-4 h-4" />
            立即导入订阅
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {subscriptions.map((sub) => {
            const isUpdating = updatingId === sub.id;
            const usedBytes = (sub.userInfo?.upload || 0) + (sub.userInfo?.download || 0);
            const totalBytes = sub.userInfo?.total || 1;
            const usagePercent = Math.min(100, Math.round((usedBytes / totalBytes) * 100));

            return (
              <div
                key={sub.id}
                className="bg-zinc-900 border border-zinc-800 hover:border-zinc-700 rounded-2xl p-5 space-y-4 transition"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="space-y-1 truncate">
                    <div className="flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
                      <h3 className="text-sm font-bold text-white truncate">{sub.name}</h3>
                    </div>
                    <div className="text-[11px] text-zinc-400 font-mono truncate max-w-sm">
                      {sub.url}
                    </div>
                  </div>

                  <div className="flex items-center gap-1.5 shrink-0">
                    <button
                      onClick={() => handleUpdateSubscription(sub)}
                      disabled={isUpdating}
                      title="刷新同步该订阅"
                      className="p-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-200 transition"
                    >
                      <RefreshCw className={`w-4 h-4 ${isUpdating ? 'animate-spin text-emerald-400' : ''}`} />
                    </button>
                    <button
                      onClick={() => handleDeleteSub(sub.id)}
                      title="删除该订阅"
                      className="p-1.5 rounded-lg bg-zinc-800 hover:bg-red-950 hover:text-red-400 text-zinc-400 transition"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>

                {/* Traffic Stats Bar if available */}
                {sub.userInfo?.total ? (
                  <div className="space-y-1.5 p-3 bg-zinc-950/80 rounded-xl border border-zinc-800/80">
                    <div className="flex justify-between text-[11px]">
                      <span className="text-zinc-400 flex items-center gap-1">
                        <HardDrive className="w-3.5 h-3.5 text-cyan-400" />
                        已用流量: {formatBytes(usedBytes)} / {formatBytes(sub.userInfo.total)}
                      </span>
                      <span className="text-emerald-400 font-mono font-semibold">
                        {usagePercent}%
                      </span>
                    </div>

                    <div className="w-full h-1.5 bg-zinc-800 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-gradient-to-r from-emerald-500 to-cyan-500 rounded-full transition-all duration-500"
                        style={{ width: `${usagePercent}%` }}
                      ></div>
                    </div>

                    <div className="flex justify-between text-[10px] text-zinc-500 pt-0.5">
                      <span className="flex items-center gap-1">
                        <Calendar className="w-3 h-3" />
                        过期时间: {formatExpire(sub.userInfo.expire)}
                      </span>
                      <span>已载入节点: {sub.nodeCount} 个</span>
                    </div>
                  </div>
                ) : (
                  <div className="flex items-center justify-between p-3 bg-zinc-950/80 rounded-xl border border-zinc-800/80 text-[11px] text-zinc-400">
                    <span>已包含节点数量</span>
                    <span className="text-emerald-400 font-mono font-bold">
                      {sub.nodeCount} 节点
                    </span>
                  </div>
                )}

                <div className="flex items-center justify-between pt-2 border-t border-zinc-800/80 text-[11px] text-zinc-500">
                  <span>最后更新: {sub.updatedAt}</span>
                  <span className="text-emerald-400 flex items-center gap-1">
                    <CheckCircle className="w-3.5 h-3.5" />
                    聚合就绪
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
