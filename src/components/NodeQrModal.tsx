import React, { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { X, Copy, Check, Download, ShieldCheck, ExternalLink } from 'lucide-react';
import { ProxyNode } from '../types/proxy';
import { nodeToUri, nodeToSingboxOutbound } from '../services/generator';

interface NodeQrModalProps {
  node: ProxyNode | null;
  onClose: () => void;
}

export const NodeQrModal: React.FC<NodeQrModalProps> = ({ node, onClose }) => {
  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [copiedUri, setCopiedUri] = useState(false);
  const [copiedJson, setCopiedJson] = useState(false);
  const [viewTab, setViewTab] = useState<'uri' | 'json'>('uri');

  useEffect(() => {
    if (!node) return;
    const uri = nodeToUri(node);
    QRCode.toDataURL(uri, {
      width: 260,
      margin: 2,
      color: {
        dark: '#000000',
        light: '#ffffff',
      },
    })
      .then((url) => setQrDataUrl(url))
      .catch((err) => console.error('QR code generation failed', err));
  }, [node]);

  if (!node) return null;

  const uri = nodeToUri(node);
  const singboxJson = JSON.stringify(nodeToSingboxOutbound(node), null, 2);

  const copyToClipboard = (text: string, type: 'uri' | 'json') => {
    navigator.clipboard.writeText(text);
    if (type === 'uri') {
      setCopiedUri(true);
      setTimeout(() => setCopiedUri(false), 2000);
    } else {
      setCopiedJson(true);
      setTimeout(() => setCopiedJson(false), 2000);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-lg bg-zinc-900 border border-zinc-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-800">
          <div className="flex items-center gap-2">
            <span className="text-xl">{node.flag}</span>
            <div>
              <h3 className="text-sm font-bold text-white truncate max-w-xs">{node.tag}</h3>
              <p className="text-[11px] text-zinc-400 uppercase font-mono">
                {node.type} 协议 · {node.server}:{node.server_port}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-zinc-400 hover:text-white hover:bg-zinc-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-5">
          {/* QR Code Container */}
          <div className="flex flex-col items-center justify-center">
            <div className="p-3 bg-white rounded-2xl shadow-md border border-zinc-200">
              {qrDataUrl ? (
                <img src={qrDataUrl} alt="Node QR" className="w-52 h-52 rounded-lg" />
              ) : (
                <div className="w-52 h-52 flex items-center justify-center text-xs text-zinc-400 font-mono">
                  生成二维码中...
                </div>
              )}
            </div>
            <span className="text-[11px] text-zinc-400 mt-2 flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              支持手机 sing-box、NekoBox、Clash Meta 扫码直连
            </span>
          </div>

          {/* Tab Selector */}
          <div className="flex border-b border-zinc-800 gap-4">
            <button
              onClick={() => setViewTab('uri')}
              className={`pb-2 text-xs font-semibold transition border-b-2 ${
                viewTab === 'uri'
                  ? 'border-emerald-500 text-white'
                  : 'border-transparent text-zinc-400 hover:text-zinc-200'
              }`}
            >
              通用分享链接 (URI)
            </button>
            <button
              onClick={() => setViewTab('json')}
              className={`pb-2 text-xs font-semibold transition border-b-2 ${
                viewTab === 'json'
                  ? 'border-emerald-500 text-white'
                  : 'border-transparent text-zinc-400 hover:text-zinc-200'
              }`}
            >
              sing-box 出站原生 JSON
            </button>
          </div>

          {viewTab === 'uri' ? (
            <div className="space-y-2">
              <div className="p-3 bg-zinc-950 rounded-xl border border-zinc-800 max-h-24 overflow-y-auto text-xs font-mono text-zinc-300 break-all select-all">
                {uri}
              </div>
              <button
                onClick={() => copyToClipboard(uri, 'uri')}
                className="w-full py-2 px-3 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition"
              >
                {copiedUri ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                {copiedUri ? '已复制分享链接！' : '复制分享链接'}
              </button>
            </div>
          ) : (
            <div className="space-y-2">
              <pre className="p-3 bg-zinc-950 rounded-xl border border-zinc-800 max-h-40 overflow-y-auto text-[11px] font-mono text-emerald-400 leading-tight">
                {singboxJson}
              </pre>
              <button
                onClick={() => copyToClipboard(singboxJson, 'json')}
                className="w-full py-2 px-3 bg-zinc-800 hover:bg-zinc-700 text-white rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition"
              >
                {copiedJson ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                {copiedJson ? '已复制 Outbound JSON！' : '复制 sing-box 出站代码'}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
