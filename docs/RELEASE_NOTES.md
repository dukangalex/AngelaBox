• **1.0.74：** 规范化继续吃 Clash JSON、httpupgrade 和节点自带的 ECH；xhttp / MASQUE 会跳过，不会伪装成能用，也不会改成直连。没有 TUN 时运行时补上标准 TUN，并修掉无效 TUN 和本机端口占用。默认脚本 DNS 改为 UDP，避免开脚本后延迟翻倍。
