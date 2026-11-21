# GitHub 同步回执 v2026-11-22

## 同步结果

- 仓库：`https://github.com/G-boy-wed/1.git`（分支 `main`）
- 本地 HEAD：`ace0750` — fix(fullsweep2): 最后全范围排查修复16项(R1-R3回归+N1-N13遗留)+重新签名+SHA同步
- 远程 main HEAD：`ace0750`（内容与本地树完全一致 `75de2681...`）
- 推送方式：PAT 授权（`https://github_pat_***@` 临时写入 remote，推送后立即还原为公开 URL）
- 历史说明：远程原 `cca6d0e` 与本地 `74ef32c` 内容树 SHA 完全相同（`83e20c37...`），仅因历史 rebase 导致哈希不同；本地为远程超集（多 `ace0750`），故采用 `--force` 覆盖，无实质内容丢失。

## Release 资产

- Release：`v0.3.9.0` — 掌心窗 v0.3.9.0（公开版·签名安装包）
- 旧资产（SHA `c05e29b9...`, 2441047 字节）已删除
- 新资产已上传：`Zhangxinchuang-public-v0.3.9.0.apk`（SHA `5fb3b74c...`, 2404183 字节）
- 下载链接：`https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk`

## 三方哈希一致性核对

| 来源 | SHA-256 |
|---|---|
| 本地 APK | `5fb3b74ca18b9a13253ddbf62afd03f426aebeba525b84c76ee75033bfeaf61c` |
| 根目录 update.json | `5fb3b74ca18b9a13253ddbf62afd03f426aebeba525b84c76ee75033bfeaf61c` |
| server/update.json | `5fb3b74ca18b9a13253ddbf62afd03f426aebeba525b84c76ee75033bfeaf61c` |
| GitHub Release APK（下载回读） | `5fb3b74ca18b9a13253ddbf62afd03f426aebeba525b84c76ee75033bfeaf61c` |

结论：四方哈希完全一致，交付闭环完成。

## 本轮交付内容回顾

- 最后全范围排查发现并修复 16 项（R1-R3 回归 + N1-N13 遗留）
- 清理 `cloudflare-worker/worker.js` 的 focus/专注残留
- APK 重新签名，v1/v2/v3 验签通过，公钥摘要一致
- MCP 123 工具无幽灵、无重复，必须保留能力齐全
- 服务端语法/静态校验、端到端冒烟全部通过
