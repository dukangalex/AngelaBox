import React, { useState } from 'react';
import {
  ArrowRightLeft,
  Copy,
  Download,
  Check,
  Code,
  FileText,
  Sparkles,
  Layers,
  ArrowRight,
} from 'lucide-react';
import { parseAnyContent } from '../services/parser';
import { generateSingboxConfig, generateClashYaml, nodeToUri, defaultOptions } from '../services/generator';
import { ProxyNode } from '../types/proxy';

interface ConverterViewProps {
  onAppendNodesToPool: (nodes: ProxyNode[]) => void;
}

export const ConverterView: React.FC<ConverterViewProps> = ({ onAppendNodesToPool }) => {
  const [inputText, setInputText] = useState('');
  const [outputType, setOutputType] = useState<'singbox' | 'clash' | 'uris' | 'base64'>('singbox');
  const [copied, setCopied] = useState(false);
  const [parsedNodes, setParsedNodes] = useState<ProxyNode[]>([]);

  // Parse input
  const handleParse = () => {
    if (!inputText.trim()) {
      setParsedNodes([]);
      return;
    }
    const nodes = parseAnyContent(inputText, '转换器导入');
    setParsedNodes(nodes);
  };

  // Generate output text
  const outputText = React.useMemo(() => {
    if (parsedNodes.length === 0) return '';
    switch (outputType) {
      case 'singbox':
        return JSON.stringify(generateSingboxConfig(parsedNodes, defaultOptions), null, 2);
      case 'clash':
        return generateClashYaml(parsedNodes, defaultOptions);
      case 'uris':
        return parsedNodes.map(nodeToUri).join('\n');
      case 'base64': {
        const uris = parsedNodes.map(nodeToUri).join('\n');
        return btoa(unescape(encodeURIComponent(uris)));
      }
    }
  }, [parsedNodes, outputType]);

  const handleCopy = () => {
    if (!outputText) return;
    navigator.clipboard.writeText(outputText);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    if (!outputText) return;
    const extensions = {
      singbox: 'config.json',
      clash: 'config.yaml',
      uris: 'nodes.txt',
      base64: 'subscription.txt',
    };
    const blob = new Blob([outputText], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = extensions[outputType];
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleAddPool = () => {
    if (parsedNodes.length === 0) return;
    onAppendNodesToPool(parsedNodes);
    alert(`成功将 ${parsedNodes.length} 个节点并入主节点池！`);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-5 shadow-lg">
        <h2 className="text-lg font-bold text-white flex items-center gap-2">
          <ArrowRightLeft className="w-5 h-5 text-emerald-400" />
          全协议全格式万能转换器
        </h2>
        <p className="text-xs text-zinc-400 mt-1">
          任意输入 Mihomo (Clash) YAML、V2Ray 链接/Base64、Sing-box JSON，瞬间双向互转与重构
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Input Panel */}
        <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-5 flex flex-col justify-between space-y-4">
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-white flex items-center gap-1.5">
                <FileText className="w-4 h-4 text-cyan-400" />
                输入源 (Clash YAML / 节点链接 / JSON / Base64)
              </span>
              <button
                onClick={() => {
                  setInputText(`vless://a4d8c720-3e21-4f1a-b620-1e5b87c4a101@hk01.example.com:443?security=reality&sni=gateway.icloud.com&pbk=j9A0kL3N8mPv2Q1xZ5wY6tU7sR4qB2vC8eF1aD3gH5s=&sid=b84a20f1&type=tcp#🇭🇰 香港 Reality 专线\nhysteria2://my_password@jp.example.com:8443?sni=jp.example.com#🇯🇵 日本 Hysteria 2\ntuic://uuid:secret@sg.example.com:443?congestion_control=bbr#🇸🇬 新加坡 TUIC v5`);
                }}
                className="text-[11px] text-emerald-400 hover:underline"
              >
                填入混合协议示例
              </button>
            </div>

            <textarea
              rows={16}
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              placeholder="在此粘贴任何格式的节点配置内容..."
              className="w-full p-3.5 bg-zinc-950 border border-zinc-800 rounded-xl text-xs font-mono text-zinc-200 placeholder-zinc-600 focus:outline-none focus:border-emerald-500 leading-relaxed"
            />
          </div>

          <div className="flex items-center justify-between gap-3 pt-2">
            <span className="text-xs text-zinc-400 font-mono">
              {parsedNodes.length > 0 ? `已识别 ${parsedNodes.length} 个节点` : '等待解析'}
            </span>
            <button
              onClick={handleParse}
              disabled={!inputText.trim()}
              className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-lg shadow-emerald-950/40"
            >
              <Sparkles className="w-4 h-4" />
              立即解析转换
            </button>
          </div>
        </div>

        {/* Output Panel */}
        <div className="bg-zinc-900 border border-zinc-800 rounded-2xl p-5 flex flex-col justify-between space-y-4">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-xs font-bold text-white flex items-center gap-1.5">
                <Code className="w-4 h-4 text-emerald-400" />
                转换输出目标格式
              </span>

              <div className="flex bg-zinc-950 p-1 rounded-xl border border-zinc-800 text-xs">
                <button
                  onClick={() => setOutputType('singbox')}
                  className={`px-2.5 py-1 rounded-lg font-semibold transition ${
                    outputType === 'singbox'
                      ? 'bg-emerald-600 text-white'
                      : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  sing-box JSON
                </button>
                <button
                  onClick={() => setOutputType('clash')}
                  className={`px-2.5 py-1 rounded-lg font-semibold transition ${
                    outputType === 'clash'
                      ? 'bg-emerald-600 text-white'
                      : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  Clash YAML
                </button>
                <button
                  onClick={() => setOutputType('uris')}
                  className={`px-2.5 py-1 rounded-lg font-semibold transition ${
                    outputType === 'uris'
                      ? 'bg-emerald-600 text-white'
                      : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  链接清单
                </button>
                <button
                  onClick={() => setOutputType('base64')}
                  className={`px-2.5 py-1 rounded-lg font-semibold transition ${
                    outputType === 'base64'
                      ? 'bg-emerald-600 text-white'
                      : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  Base64 订阅
                </button>
              </div>
            </div>

            <textarea
              readOnly
              rows={16}
              value={outputText}
              placeholder="解析后的配置结果将在此显示..."
              className="w-full p-3.5 bg-zinc-950 border border-zinc-800 rounded-xl text-xs font-mono text-emerald-400 placeholder-zinc-700 focus:outline-none leading-relaxed select-all"
            />
          </div>

          <div className="flex items-center justify-between gap-2 pt-2">
            <button
              onClick={handleAddPool}
              disabled={parsedNodes.length === 0}
              className="px-3.5 py-2 bg-zinc-800 hover:bg-zinc-700 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
            >
              <Layers className="w-4 h-4 text-cyan-400" />
              并入主节点池
            </button>

            <div className="flex items-center gap-2">
              <button
                onClick={handleCopy}
                disabled={!outputText}
                className="px-3.5 py-2 bg-zinc-800 hover:bg-zinc-700 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition border border-zinc-700"
              >
                {copied ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                {copied ? '已复制' : '复制结果'}
              </button>
              <button
                onClick={handleDownload}
                disabled={!outputText}
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition shadow-lg shadow-emerald-950/40"
              >
                <Download className="w-4 h-4" />
                下载导出
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
