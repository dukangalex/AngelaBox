/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect } from 'react';
import { Sidebar } from './components/Sidebar';
import { Navbar } from './components/Navbar';
import { DashboardView } from './components/DashboardView';
import { NodesView } from './components/NodesView';
import { SubscriptionsView } from './components/SubscriptionsView';
import { ConfigGeneratorView } from './components/ConfigGeneratorView';
import { ConverterView } from './components/ConverterView';
import { ScriptAuditorView } from './components/ScriptAuditorView';
import { ImportModal } from './components/ImportModal';
import { ProxyNode, Subscription, ConfigOptions } from './types/proxy';
import { SAMPLE_NODES, INITIAL_SUBSCRIPTIONS } from './data/presets';
import { defaultOptions, generateSingboxConfig } from './services/generator';

export default function App() {
  // Load state from localStorage or defaults
  const [nodes, setNodes] = useState<ProxyNode[]>(() => {
    try {
      const saved = localStorage.getItem('angelabox_nodes');
      return saved ? JSON.parse(saved) : SAMPLE_NODES;
    } catch {
      return SAMPLE_NODES;
    }
  });

  const [subscriptions, setSubscriptions] = useState<Subscription[]>(() => {
    try {
      const saved = localStorage.getItem('angelabox_subs');
      return saved ? JSON.parse(saved) : INITIAL_SUBSCRIPTIONS;
    } catch {
      return INITIAL_SUBSCRIPTIONS;
    }
  });

  const [configOptions, setConfigOptions] = useState<ConfigOptions>(() => {
    try {
      const saved = localStorage.getItem('angelabox_config_opts');
      return saved ? JSON.parse(saved) : defaultOptions;
    } catch {
      return defaultOptions;
    }
  });

  const [activeNodeId, setActiveNodeId] = useState<string>(() => {
    return nodes[0]?.id || 'preset_hk_01';
  });

  const [currentTab, setCurrentTab] = useState<string>('dashboard');
  const [isImportOpen, setIsImportOpen] = useState(false);
  const [isPingingAll, setIsPingingAll] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);

  // Sync to localStorage
  useEffect(() => {
    try {
      localStorage.setItem('angelabox_nodes', JSON.stringify(nodes));
    } catch (e) {
      console.error(e);
    }
  }, [nodes]);

  useEffect(() => {
    try {
      localStorage.setItem('angelabox_subs', JSON.stringify(subscriptions));
    } catch (e) {
      console.error(e);
    }
  }, [subscriptions]);

  useEffect(() => {
    try {
      localStorage.setItem('angelabox_config_opts', JSON.stringify(configOptions));
    } catch (e) {
      console.error(e);
    }
  }, [configOptions]);

  // Update Config options
  const handleUpdateConfigOptions = (partial: Partial<ConfigOptions>) => {
    setConfigOptions((prev) => ({ ...prev, ...partial }));
  };

  // Import handler
  const handleImportNodes = (newNodes: ProxyNode[], sourceName: string, subUrl?: string) => {
    // Deduplicate against existing by server + port + tag
    const existingKeys = new Set(nodes.map((n) => `${n.server}:${n.server_port}:${n.tag}`));
    const deduplicated = newNodes.filter(
      (n) => !existingKeys.has(`${n.server}:${n.server_port}:${n.tag}`)
    );

    const mergedNodes = [...deduplicated, ...nodes];
    setNodes(mergedNodes);

    // If imported from URL, record in subscriptions list
    if (subUrl) {
      const newSub: Subscription = {
        id: 'sub_' + Date.now(),
        name: sourceName,
        url: subUrl,
        type: 'auto',
        nodeCount: newNodes.length,
        updatedAt: '刚刚',
        status: 'success',
        nodes: newNodes,
      };
      setSubscriptions([newSub, ...subscriptions]);
    }
  };

  // Append nodes directly from converter
  const handleAppendNodes = (newNodes: ProxyNode[]) => {
    setNodes((prev) => [...newNodes, ...prev]);
  };

  // Test single node latency simulation
  const handleTestNodePing = (nodeId: string) => {
    const target = nodes.find((n) => n.id === nodeId);
    if (!target) return;

    // Geographic baseline latency calculation
    let base = 60;
    if (target.flag === '🇭🇰') base = 25;
    else if (target.flag === '🇹🇼') base = 35;
    else if (target.flag === '🇯🇵') base = 48;
    else if (target.flag === '🇸🇬') base = 45;
    else if (target.flag === '🇰🇷') base = 60;
    else if (target.flag === '🇺🇸') base = 145;
    else if (target.flag === '🇬🇧' || target.flag === '🇩🇪') base = 185;

    // Protocol modifier
    if (target.type === 'hysteria2' || target.type === 'tuic') base -= 8;
    const jitter = Math.floor(Math.random() * 18) - 9;
    const finalLatency = Math.max(12, base + jitter);

    setNodes((prev) =>
      prev.map((n) => (n.id === nodeId ? { ...n, latency: finalLatency } : n))
    );
  };

  // Test all pings in concurrent wave
  const handleTestAllPings = () => {
    setIsPingingAll(true);
    let index = 0;
    const interval = setInterval(() => {
      if (index >= nodes.length) {
        clearInterval(interval);
        setIsPingingAll(false);
        return;
      }
      const target = nodes[index];
      if (target) {
        handleTestNodePing(target.id);
      }
      index++;
    }, 120);
  };

  // Aggregate all subscriptions into master pool
  const handleAggregateAllNodes = () => {
    const allSubNodes: ProxyNode[] = [];
    subscriptions.forEach((sub) => {
      if (sub.nodes && sub.nodes.length > 0) {
        allSubNodes.push(...sub.nodes);
      }
    });

    if (allSubNodes.length === 0) {
      alert('当前没有可聚合的订阅节点');
      return;
    }

    // Deduplicate
    const seen = new Set<string>();
    const unique: ProxyNode[] = [];
    allSubNodes.forEach((n) => {
      const key = `${n.type}://${n.server}:${n.server_port}/${n.uuid || n.password || ''}`;
      if (!seen.has(key)) {
        seen.add(key);
        unique.push(n);
      }
    });

    setNodes(unique);
    alert(`聚合完成！共合并去重生成 ${unique.length} 个优质出站节点。`);
  };

  // Global export config.json
  const handleExportConfig = () => {
    const config = generateSingboxConfig(nodes, configOptions);
    const blob = new Blob([JSON.stringify(config, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'config.json';
    a.click();
    URL.revokeObjectURL(url);
  };

  const activeNode = nodes.find((n) => n.id === activeNodeId) || nodes[0];

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-zinc-950 text-zinc-100 font-sans">
      {/* Desktop Sidebar */}
      <div className="hidden md:flex shrink-0">
        <Sidebar
          currentTab={currentTab}
          onSelectTab={setCurrentTab}
          nodeCount={nodes.length}
          subCount={subscriptions.length}
        />
      </div>

      {/* Mobile Drawer */}
      {mobileSidebarOpen && (
        <div className="fixed inset-0 z-50 flex md:hidden">
          <div
            className="fixed inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => setMobileSidebarOpen(false)}
          ></div>
          <div className="relative flex flex-col w-64 bg-zinc-950 border-r border-zinc-800 z-10">
            <Sidebar
              currentTab={currentTab}
              onSelectTab={(tab) => {
                setCurrentTab(tab);
                setMobileSidebarOpen(false);
              }}
              nodeCount={nodes.length}
              subCount={subscriptions.length}
            />
          </div>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
        <Navbar
          onToggleMobileSidebar={() => setMobileSidebarOpen(!mobileSidebarOpen)}
          onOpenImport={() => setIsImportOpen(true)}
          onTestAllPings={handleTestAllPings}
          isPingingAll={isPingingAll}
          onExportConfig={handleExportConfig}
          configOptions={configOptions}
          activeNodeName={activeNode ? `${activeNode.flag} ${activeNode.tag}` : undefined}
        />

        <main className="flex-1 overflow-y-auto p-4 md:p-8 bg-zinc-950">
          <div className="max-w-7xl mx-auto space-y-6">
            {currentTab === 'dashboard' && (
              <DashboardView
                nodes={nodes}
                activeNodeId={activeNodeId}
                onSelectActiveNode={setActiveNodeId}
                configOptions={configOptions}
                onChangeConfigOptions={handleUpdateConfigOptions}
                onNavigateTab={setCurrentTab}
                onTestNodePing={handleTestNodePing}
              />
            )}

            {currentTab === 'nodes' && (
              <NodesView
                nodes={nodes}
                onUpdateNodes={setNodes}
                onOpenImport={() => setIsImportOpen(true)}
                onTestNodePing={handleTestNodePing}
                onTestAllPings={handleTestAllPings}
                isPingingAll={isPingingAll}
              />
            )}

            {currentTab === 'subscriptions' && (
              <SubscriptionsView
                subscriptions={subscriptions}
                onUpdateSubscriptions={setSubscriptions}
                onAggregateAllNodes={handleAggregateAllNodes}
                onOpenImport={() => setIsImportOpen(true)}
              />
            )}

            {currentTab === 'generator' && (
              <ConfigGeneratorView
                nodes={nodes}
                options={configOptions}
                onChangeOptions={handleUpdateConfigOptions}
              />
            )}

            {currentTab === 'converter' && (
              <ConverterView onAppendNodesToPool={handleAppendNodes} />
            )}

            {currentTab === 'auditor' && <ScriptAuditorView />}
          </div>
        </main>
      </div>

      {/* Global Import Modal */}
      <ImportModal
        isOpen={isImportOpen}
        onClose={() => setIsImportOpen(false)}
        onImportNodes={handleImportNodes}
      />
    </div>
  );
}
