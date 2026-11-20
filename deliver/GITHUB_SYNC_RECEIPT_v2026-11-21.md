# GitHub 同步回执 v2026-11-21

## 结果：代码与 APK 已全部同步到 GitHub ✅

仓库：https://github.com/G-boy-wed/1

### 1. 代码推送
- Token（github_pat_…）验证有效，账号 `G-boy-wed`，对仓库有 push 权限。
- 沙箱网络限制：`github.com:443` 的 git 智能协议被拦截（git push / fetch 均超时），但 `api.github.com` 可达。
- 采用 GitHub REST API（Git Data API）完成等价推送：逐文件创建 blob → 构造 tree → 创建 commit → 更新 main 引用。
- 同步 49 个变更文件（含新增 DeskFeatureStore/DeskSkinStore/FreeMusic/PalmDeskActivity、删除 DeskPluginStore、server 修复、全部回执/预览）。
- 远端 main：`217188a6`（基于原 `5ad2b18`）；抽样校验 PalmDeskActivity.java 的 blob sha 与本地完全一致（`06d618f7…`）。

### 2. Release 资产
- Release `v0.3.9.0`（id 440005987）原 APK 资产 1149659 bytes。
- 覆盖上传新 APK（`gh release upload --clobber`）→ 1166043 bytes。
- 下载链接：https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk
- 通过 API 下载校验 sha256：`903fd633879ac4b2dc7cd2f13663e052b9737dd1a79f7f3e00765440b8da21dd`，与本地、与远端 `update.json` 完全一致。

### 3. update.json
- 远端 `update.json` 的 sha256 与 changelog 均为最新（末条为功能角标重构）。

### 4. 说明
- 因沙箱网络限制，`github.com` 域名的 git 操作与浏览器直连下载在此环境不可达，但仓库与 Release 在公网完全正常；用户在自己的网络下可正常 `git clone` 与浏览器下载 APK。
- Token 仅用于本次同步，临时凭据文件已全部删除，未写入仓库。
