import React, { useState, useEffect } from 'react';
import { X, Save, Server } from 'lucide-react';
import { ProxyNode, ProxyType } from '../types/proxy';
import { detectFlag } from '../services/parser';

interface NodeEditorModalProps {
  node: ProxyNode | null;
  isOpen: boolean;
  onClose: () => void;
  onSave: (node: ProxyNode) => void;
}

export const NodeEditorModal: React.FC<NodeEditorModalProps> = ({ node, isOpen, onClose, onSave }) => {
  const [formData, setFormData] = useState<Partial<ProxyNode>>({
    tag: '',
    type: 'vless',
    server: '',
    server_port: 443,
    uuid: '',
    password: '',
    method: '2022-blake3-aes-128-gcm',
    flow: '',
    tls: { enabled: true, server_name: '', insecure: false },
    transport: { type: 'tcp', path: '' },
  });

  useEffect(() => {
    if (node) {
      setFormData({ ...node });
    } else {
      setFormData({
        id: 'node_' + Date.now(),
        tag: '新建节点',
        type: 'vless',
        server: '',
        server_port: 443,
        uuid: 'a0b1c2d3-e4f5-6789-0123-456789abcdef',
        tls: { enabled: true, server_name: '', insecure: false },
        transport: { type: 'tcp' },
        flag: '🌐',
      });
    }
  }, [node, isOpen]);

  if (!isOpen) return null;

  const handleSave = () => {
    if (!formData.tag || !formData.server || !formData.server_port) return;

    const updatedNode: ProxyNode = {
      ...(formData as ProxyNode),
      id: formData.id || 'node_' + Date.now(),
      flag: detectFlag(formData.tag || ''),
    };

    onSave(updatedNode);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-xl bg-zinc-900 border border-zinc-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-800">
          <div className="flex items-center gap-2">
            <Server className="w-5 h-5 text-emerald-400" />
            <h3 className="text-base font-bold text-white">
              {node ? '编辑节点配置' : '手动新建 sing-box 节点'}
            </h3>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-zinc-400 hover:text-white hover:bg-zinc-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Form Body */}
        <div className="p-6 overflow-y-auto space-y-4 flex-1">
          {/* Tag & Protocol */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">节点名称 / 备注</label>
              <input
                type="text"
                value={formData.tag || ''}
                onChange={(e) => setFormData({ ...formData, tag: e.target.value })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
                placeholder="例如: 🇭🇰 香港 01 | VLESS"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">协议类型</label>
              <select
                value={formData.type || 'vless'}
                onChange={(e) => setFormData({ ...formData, type: e.target.value as ProxyType })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="vless">VLESS</option>
                <option value="hysteria2">Hysteria 2</option>
                <option value="tuic">TUIC v5</option>
                <option value="vmess">VMess</option>
                <option value="shadowsocks">Shadowsocks</option>
                <option value="trojan">Trojan</option>
                <option value="wireguard">WireGuard</option>
                <option value="socks">SOCKS5</option>
                <option value="http">HTTP/HTTPS</option>
              </select>
            </div>
          </div>

          {/* Server & Port */}
          <div className="grid grid-cols-3 gap-4">
            <div className="col-span-2">
              <label className="block text-xs font-medium text-zinc-300 mb-1">服务器地址 (域名 / IP)</label>
              <input
                type="text"
                value={formData.server || ''}
                onChange={(e) => setFormData({ ...formData, server: e.target.value })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                placeholder="node.example.com"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">端口</label>
              <input
                type="number"
                value={formData.server_port || 443}
                onChange={(e) => setFormData({ ...formData, server_port: parseInt(e.target.value, 10) })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
              />
            </div>
          </div>

          {/* UUID / Password / Method conditional */}
          {(formData.type === 'vless' || formData.type === 'vmess' || formData.type === 'tuic') && (
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">UUID / 用户标识</label>
              <input
                type="text"
                value={formData.uuid || ''}
                onChange={(e) => setFormData({ ...formData, uuid: e.target.value })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                placeholder="00000000-0000-0000-0000-000000000000"
              />
            </div>
          )}

          {(formData.type === 'trojan' || formData.type === 'hysteria2' || formData.type === 'tuic' || formData.type === 'shadowsocks' || formData.type === 'socks' || formData.type === 'http') && (
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">密码 / Token</label>
              <input
                type="text"
                value={formData.password || ''}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                placeholder="节点密码"
              />
            </div>
          )}

          {formData.type === 'shadowsocks' && (
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">加密算法 (Cipher Method)</label>
              <select
                value={formData.method || '2022-blake3-aes-128-gcm'}
                onChange={(e) => setFormData({ ...formData, method: e.target.value })}
                className="w-full px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
              >
                <option value="2022-blake3-aes-128-gcm">2022-blake3-aes-128-gcm (sing-box 推荐)</option>
                <option value="2022-blake3-aes-256-gcm">2022-blake3-aes-256-gcm</option>
                <option value="2022-blake3-chacha20-poly1305">2022-blake3-chacha20-poly1305</option>
                <option value="aes-256-gcm">aes-256-gcm</option>
                <option value="aes-128-gcm">aes-128-gcm</option>
                <option value="chacha20-ietf-poly1305">chacha20-ietf-poly1305</option>
              </select>
            </div>
          )}

          {/* TLS / SNI */}
          <div className="p-3 bg-zinc-950/60 rounded-xl border border-zinc-800 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-zinc-200">TLS 安全传输</span>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.tls?.enabled || false}
                  onChange={(e) =>
                    setFormData({
                      ...formData,
                      tls: { ...formData.tls, enabled: e.target.checked },
                    })
                  }
                  className="sr-only peer"
                />
                <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-600"></div>
              </label>
            </div>

            {formData.tls?.enabled && (
              <div className="grid grid-cols-2 gap-3 pt-1">
                <div>
                  <label className="block text-[11px] text-zinc-400 mb-1">SNI (Server Name)</label>
                  <input
                    type="text"
                    value={formData.tls?.server_name || ''}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        tls: { ...formData.tls, enabled: true, server_name: e.target.value },
                      })
                    }
                    placeholder="如: gateway.icloud.com"
                    className="w-full px-2.5 py-1.5 bg-zinc-900 border border-zinc-700 rounded-lg text-xs text-white"
                  />
                </div>
                <div>
                  <label className="block text-[11px] text-zinc-400 mb-1">Reality 公钥 (pbk)</label>
                  <input
                    type="text"
                    value={formData.tls?.reality?.public_key || ''}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        tls: {
                          ...formData.tls,
                          enabled: true,
                          reality: {
                            enabled: !!e.target.value,
                            public_key: e.target.value,
                            short_id: formData.tls?.reality?.short_id || '',
                          },
                        },
                      })
                    }
                    placeholder="选填 Reality Public Key"
                    className="w-full px-2.5 py-1.5 bg-zinc-900 border border-zinc-700 rounded-lg text-xs text-white font-mono"
                  />
                </div>
              </div>
            )}
          </div>
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
            onClick={handleSave}
            disabled={!formData.tag || !formData.server || !formData.server_port}
            className="px-5 py-2 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 transition"
          >
            <Save className="w-4 h-4" />
            保存节点
          </button>
        </div>
      </div>
    </div>
  );
};
