import React, { useState } from 'react';
import { X, Upload, Link as LinkIcon, FileText, CheckCircle2, AlertTriangle, ArrowRight, Loader2 } from 'lucide-react';
import { parseAnyContent } from '../services/parser';
import { ProxyNode } from '../types/proxy';

interface ImportModalProps {
  isOpen: boolean;
  onClose: () => void;
  onImportNodes: (nodes: ProxyNode[], sourceName: string, subUrl?: string) => void;
}

export const ImportModal: React.FC<ImportModalProps> = ({ isOpen, onClose, onImportNodes }) => {
  const [tab, setTab] = useState<'link' | 'file' | 'text'>('link');
  const [url, setUrl] = useState('');
  const [subName, setSubName] = useState('');
  const [textContent, setTextContent] = useState('');
  const [fileName, setFileName] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [parsedPreview, setParsedPreview] = useState<ProxyNode[]>([]);

  if (!isOpen) return null;

  const handleFetchUrl = async () => {
    if (!url.trim()) {
      setError('请输入有效的订阅链接');
      return;
    }
    setError(null);
    setLoading(true);

    try {
      // Try backend proxy first to avoid CORS
      let rawData = '';
      try {
        const resp = await fetch('/api/fetch-subscription', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: url.trim(), userAgentType: 'singbox' }),
        });
        if (resp.ok) {
          const json = await resp.json();
          rawData = json.data;
        } else {
          throw new Error('Backend proxy returned ' + resp.status);
        }
      } catch (beErr) {
        // Fallback to direct client fetch
        const clientResp = await fetch(url.trim());
        if (!clientResp.ok) throw new Error('网络请求错误: ' + clientResp.statusText);
        rawData = await clientResp.text();
      }

      const nodes = parseAnyContent(rawData, subName.trim() || '订阅导入');
      if (nodes.length === 0) {
        throw new Error('未在响应中解析到有效节点，请确认链接类型');
      }

      setParsedPreview(nodes);
    } catch (err: any) {
      setError(err.message || '订阅拉取或解析失败');
    } finally {
      setLoading(false);
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFileName(file.name);
    setError(null);

    const reader = new FileReader();
    reader.onload = (event) => {
      const content = event.target?.result as string;
      if (content) {
        const nodes = parseAnyContent(content, file.name);
        if (nodes.length === 0) {
          setError('未能解析到节点，请确认是合法的 Clash YAML / Sing-box JSON / V2Ray 配置');
        } else {
          setParsedPreview(nodes);
        }
      }
    };
    reader.onerror = () => setError('读取文件失败');
    reader.readAsText(file);
  };

  const handleTextPreview = () => {
    if (!textContent.trim()) {
      setError('请输入节点内容');
      return;
    }
    setError(null);
    const nodes = parseAnyContent(textContent, '剪贴板/混合节点');
    if (nodes.length === 0) {
      setError('未能识别到有效节点，请检查链接或配置格式');
    } else {
      setParsedPreview(nodes);
    }
  };

  const handleConfirmImport = () => {
    if (parsedPreview.length === 0) return;
    const finalName =
      tab === 'link'
        ? subName.trim() || '订阅节点集'
        : tab === 'file'
        ? fileName || '本地文件'
        : '批量粘贴节点';

    onImportNodes(parsedPreview, finalName, tab === 'link' ? url.trim() : undefined);
    // Reset state
    setParsedPreview([]);
    setUrl('');
    setTextContent('');
    setFileName('');
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-2xl bg-zinc-900 border border-zinc-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-800 bg-zinc-900/80">
          <div>
            <h2 className="text-lg font-bold text-white flex items-center gap-2">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></span>
              导入代理配置与节点
            </h2>
            <p className="text-xs text-zinc-400 mt-0.5">
              原生支持 Mihomo (Clash)、V2Ray、Sing-box 订阅链接、全协议单/混合节点与完整配置文件
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg text-zinc-400 hover:text-white hover:bg-zinc-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-zinc-800 bg-zinc-950/60 px-6 pt-3 gap-2">
          <button
            onClick={() => {
              setTab('link');
              setError(null);
              setParsedPreview([]);
            }}
            className={`flex items-center gap-2 px-4 py-2.5 text-xs font-semibold rounded-t-lg transition border-b-2 ${
              tab === 'link'
                ? 'border-emerald-500 text-white bg-zinc-800/60'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <LinkIcon className="w-4 h-4 text-emerald-400" />
            订阅链接 (URL)
          </button>
          <button
            onClick={() => {
              setTab('file');
              setError(null);
              setParsedPreview([]);
            }}
            className={`flex items-center gap-2 px-4 py-2.5 text-xs font-semibold rounded-t-lg transition border-b-2 ${
              tab === 'file'
                ? 'border-emerald-500 text-white bg-zinc-800/60'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <Upload className="w-4 h-4 text-cyan-400" />
            本地配置文件 (YAML / JSON)
          </button>
          <button
            onClick={() => {
              setTab('text');
              setError(null);
              setParsedPreview([]);
            }}
            className={`flex items-center gap-2 px-4 py-2.5 text-xs font-semibold rounded-t-lg transition border-b-2 ${
              tab === 'text'
                ? 'border-emerald-500 text-white bg-zinc-800/60'
                : 'border-transparent text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <FileText className="w-4 h-4 text-violet-400" />
            节点链接 / 混合粘贴
          </button>
        </div>

        {/* Content Body */}
        <div className="p-6 overflow-y-auto space-y-4 flex-1">
          {tab === 'link' && (
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                  订阅链接 (支持 Clash / V2Ray / Sing-box / Base64)
                </label>
                <input
                  type="text"
                  placeholder="https://example.com/api/v1/client/subscribe?token=..."
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  className="w-full px-3.5 py-2.5 bg-zinc-950 border border-zinc-700/80 rounded-xl text-sm text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                  订阅名称 (选填，留空自动提取)
                </label>
                <input
                  type="text"
                  placeholder="如: 我的机场主线 / 香港专线"
                  value={subName}
                  onChange={(e) => setSubName(e.target.value)}
                  className="w-full px-3.5 py-2.5 bg-zinc-950 border border-zinc-700/80 rounded-xl text-sm text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition"
                />
              </div>

              <button
                onClick={handleFetchUrl}
                disabled={loading || !url.trim()}
                className="w-full py-2.5 px-4 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white rounded-xl text-sm font-semibold flex items-center justify-center gap-2 transition shadow-lg shadow-emerald-950/40"
              >
                {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <LinkIcon className="w-4 h-4" />}
                {loading ? '正在安全拉取并解析协议...' : '开始拉取订阅节点'}
              </button>
            </div>
          )}

          {tab === 'file' && (
            <div className="space-y-4">
              <div className="border-2 border-dashed border-zinc-700 hover:border-zinc-500 rounded-2xl p-8 text-center bg-zinc-950/40 transition">
                <Upload className="w-10 h-10 text-cyan-400 mx-auto mb-3" />
                <p className="text-sm font-medium text-zinc-200">
                  点击选择或拖拽本地配置文件至此处
                </p>
                <p className="text-xs text-zinc-500 mt-1">
                  支持 Mihomo/Clash (.yaml, .yml)、Sing-box (.json)、V2Ray (.json) 或节点文本 (.txt)
                </p>
                <label className="mt-4 inline-block px-4 py-2 bg-zinc-800 hover:bg-zinc-700 text-zinc-200 rounded-xl text-xs font-semibold cursor-pointer transition">
                  浏览电脑文件
                  <input
                    type="file"
                    accept=".yaml,.yml,.json,.txt"
                    onChange={handleFileUpload}
                    className="hidden"
                  />
                </label>
                {fileName && (
                  <p className="text-xs text-emerald-400 mt-3 font-mono">
                    已加载: {fileName}
                  </p>
                )}
              </div>
            </div>
          )}

          {tab === 'text' && (
            <div className="space-y-4">
              <div>
                <div className="flex justify-between items-center mb-1.5">
                  <label className="text-xs font-medium text-zinc-300">
                    直接粘贴节点链接或配置片段
                  </label>
                  <span className="text-[11px] text-zinc-500">
                    支持单节点、多节点、混合协议多行粘贴
                  </span>
                </div>
                <textarea
                  rows={6}
                  placeholder={`vless://uuid@host:443?security=reality&sni=example.com#HK-01\nhysteria2://pass@host:8443?sni=example.com#JP-02\ntuic://uuid:pass@host:443#SG-03\nss://base64...#US-04\nvmess://base64...#TW-05`}
                  value={textContent}
                  onChange={(e) => setTextContent(e.target.value)}
                  className="w-full px-3.5 py-2.5 bg-zinc-950 border border-zinc-700/80 rounded-xl text-xs font-mono text-zinc-200 placeholder-zinc-600 focus:outline-none focus:border-emerald-500 transition leading-relaxed"
                />
              </div>

              <button
                onClick={handleTextPreview}
                disabled={!textContent.trim()}
                className="w-full py-2.5 px-4 bg-violet-600 hover:bg-violet-500 disabled:opacity-50 text-white rounded-xl text-sm font-semibold flex items-center justify-center gap-2 transition"
              >
                <FileText className="w-4 h-4" />
                解析所贴文本中的节点
              </button>
            </div>
          )}

          {error && (
            <div className="p-3 bg-red-950/40 border border-red-800/80 rounded-xl flex items-start gap-2 text-xs text-red-300">
              <AlertTriangle className="w-4 h-4 text-red-400 shrink-0 mt-0.5" />
              <div>{error}</div>
            </div>
          )}

          {/* Parsed Preview Section */}
          {parsedPreview.length > 0 && (
            <div className="mt-4 p-4 bg-zinc-950/80 border border-zinc-800 rounded-xl">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-semibold text-emerald-400 flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                  已成功识别 {parsedPreview.length} 个节点
                </span>
                <span className="text-[11px] text-zinc-400">
                  可直接并入 AngelaBox 节点聚合池
                </span>
              </div>

              <div className="max-h-40 overflow-y-auto space-y-1.5 pr-1">
                {parsedPreview.slice(0, 10).map((n) => (
                  <div
                    key={n.id}
                    className="flex items-center justify-between px-2.5 py-1.5 bg-zinc-900 rounded-lg text-xs"
                  >
                    <div className="flex items-center gap-2 truncate">
                      <span>{n.flag}</span>
                      <span className="text-zinc-200 truncate font-medium">{n.tag}</span>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-zinc-800 text-zinc-300 uppercase">
                        {n.type}
                      </span>
                      <span className="text-[11px] text-zinc-400 font-mono">
                        {n.server}:{n.server_port}
                      </span>
                    </div>
                  </div>
                ))}
                {parsedPreview.length > 10 && (
                  <div className="text-center text-[11px] text-zinc-500 pt-1">
                    ... 及其余 {parsedPreview.length - 10} 个节点
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-6 py-4 border-t border-zinc-800 bg-zinc-900/90">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-zinc-400 hover:text-white transition"
          >
            取消
          </button>
          <button
            onClick={handleConfirmImport}
            disabled={parsedPreview.length === 0}
            className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-2 transition shadow-lg shadow-emerald-950/40"
          >
            确认导入 {parsedPreview.length > 0 ? `(${parsedPreview.length} 节点)` : ''}
            <ArrowRight className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );
};
