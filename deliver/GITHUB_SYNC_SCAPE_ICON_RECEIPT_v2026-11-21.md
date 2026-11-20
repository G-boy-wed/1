# 掌心窗 · ins风景角标 + GitHub同步 回执（v2026-11-21）

## 本轮完成

1. **角标换成 ins 风低饱和真实风景图**：用户否决了几何图标（"很丑"），改用 Unsplash 12 张真实风景照（日出/云海/森林/湖泊/瀑布/雪山/星空/山谷/海岸/海面/木桥/云雾山），裁边去水印 + 饱和度 0.55 + 柔和胶片暖调，打成 12 款内置角标图标。无任何 emoji/文字/简笔画。
2. **修复 3 个真实 bug**（子代理深度审查发现）：
   - 【高】旧功能入口"自定义上传图标"存错 key：写入用原 id（如 `diary`）、读取用 `old_diary`，导致上传无效。已按 category 补 `old_` 前缀。
   - 【中】自定义 URI 解码失败（权限撤销/文件被删）时图标变纯色块。已回退到内置风景图。
   - 【中】机端下发背景色 `#RRGGBB` 因用含 `#` 的长度判断未补 alpha，变透明无效。已改用去 `#` 后长度判断。
3. **第四页贴图**（上一轮已完成，本轮保留）：old0–old5 独立槽，可增删/拖动/缩放。

## GitHub 同步（REST Git Data API，因 github.com git 协议超时）

- 远端 main 原先停在 `e951bbac`（tree `83f00d17`，与本地 `16695c9` 内容一致）。
- 以该 tree 为 base，增量上传 `16695c9..bd54dda` 的 48 个文件（41 新增 + 7 修改）blob，创建新 tree `776afaa0`（与本地 HEAD tree 完全一致）。
- 新 commit：`b68de1ae`，parent=`e951bbac`，message=本地最新提交。
- main ref 已更新：`b68de1aea43a8b3935b763a07226baf00770d7c3`；随后补推本回执文档，最终 main = `67a8688fc02ea502cb2300843ddb0a4ab19e4279`（tree `78e217e4` 与本地完全一致）。

## Release APK 覆盖

- Release `v0.3.9.0`（id 440005987）旧 asset 已删除，新 APK 已上传（id 772852717，2,436,951 字节）。
- 下载链接：https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk
- 三方 SHA-256 一致：`3287dd4420c409ed0abee9b2b076d7a6b2f2013aa89f9a2fb2451501b9478aed`
  - 本地构建 / 上传 / Release 下载回读 全部相同。

## 验证

- 编译通过，v1/v2/v3 验签全 Verified（同既有公开证书）。
- DEX 命中："日出山峦""云海雪山""星空夜幕""firstEmptyOldSlot""DeskCommand""ic_badge_11"。
- MCP：schema 静态校验零错误，py_compile 通过；图标索引 0-11 描述与 12 款风景图同步。
- Token 用完已清理，未写入仓库任何文件。

## 本地提交

- `bd54ddaa` feat(icon): 角标改为12张ins风低饱和Unsplash风景照(去水印/胶片调色),移除几何图; fix: 旧入口自定义图标存错key(old_前缀)/URI解码失败无回退/#RRGGBB背景色变透明; mcp描述同步
