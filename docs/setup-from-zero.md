# 掌心窗 v0.3.9.0 修改版 · 从零开始完整教程

> 本教程适用于**第一次接触**本项目的读者，从「拿到源码」一直讲到「AI 能用上全部能力」。
> 全部命令均已在本项目实测通过；不需要你有任何编程基础，照着抄即可。

---

## 目录

| 章节 | 内容 | 你会得到什么 |
|---|---|---|
| [第 0 章](#第-0-章-先搞清楚这是什么东西) | 先搞清楚这是什么东西 | 理解四层结构 |
| [第 1 章](#第-1-章-拿到源码) | 拿到源码 | 电脑上有一份完整代码 |
| [第 2 章](#第-2-章-启动后端-server) | 启动后端 server | 手机和 AI 能互相通信 |
| [第 3 章](#第-3-章-启动-mcp-服务) | 启动 MCP 服务 | AI 拿到 102 个工具 |
| [第 4 章](#第-4-章-手机端安装与连接) | 手机端安装与连接 | App 装到手机并连上后端 |
| [第 5 章](#第-5-章-手机端设置：查岗与管束) | 查岗与管束设置页 | 自己调全部参数 |
| [第 6 章](#第-6-章-归电主题与弹窗位置) | 归电主题与弹窗位置 | 弹窗跟着归电变 |
| [第 7 章](#第-7-章-陪伴对象情绪) | 陪伴对象情绪 | AI 写、手机看、随时间变化 |
| [第 8 章](#第-8-章-分级拉回弹窗) | 分级拉回弹窗 | 软/强两种拉回 |
| [第 9 章](#第-9-章-接入你的-ai-客户端) | 接入 AI 客户端 | AI 真正用起来 |
| [第 10 章](#第-10-章-云端部署-render) | 云端部署 | 手机在外网也能用 |
| [第 11 章](#第-11-章-上传到-github) | 上传 GitHub | 源码进仓库、Actions 打包 |
| [第 12 章](#第-12-章-排错手册) | 排错手册 | 出问题照着查 |

---

## 第 0 章：先搞清楚这是什么东西

掌心窗是一个「手机陪伴 App + AI 后台」的系统，共四层：

```
┌─────────────────────────────────────────────┐
│  你的 AI（Claude / Kelivo / 任何 MCP 客户端）│
└───────────────┬─────────────────────────────┘
                │ MCP 协议（102 个工具）
┌───────────────▼─────────────────────────────┐
│  mcp/server.js        ← AI 的手脚            │  Node.js，端口 8787
└───────────────┬─────────────────────────────┘
                │ HTTP + Token
┌───────────────▼─────────────────────────────┐
│  server/linjian_server.py  ← 中枢 + 调度器   │  Python，端口 8513
└───────────────┬─────────────────────────────┘
                │ HTTP 长轮询
┌───────────────▼─────────────────────────────┐
│  android/            ← 掌心窗 App（手机）     │  Java + Android
│  · 无障碍服务        · 悬浮窗/透明弹窗        │
│  · 屏幕状态采集      · 巡回查岗执行            │
└─────────────────────────────────────────────┘
```

### 关键设计原则（读懂这个，后面就不会困惑）

| 原则 | 含义 |
|---|---|
| **一份配置，两处改** | 手机端「查岗与管束」页面和 MCP 的 `set_tour_schedule` 读写的是**同一个**服务端文件 `data/tour_schedule.json`，不存在第二套存储 |
| **两套情绪，互不覆盖** | 读**用户手机状态**的情绪快照存在 `emotion_state.json`；**陪伴对象自己**的情绪存在 `companion_emotion.json`，两个文件、两个接口，谁也覆盖不了谁 |
| **AI 能写，也能被约束** | AI 可以下发弹窗、拉回、锁 App，但哪些行为允许发生由**用户自己**在 App 里设定的策略决定（`active_care_enabled` 是总开关，`allowed_actions` 是策略清单） |
| **所有时间参数可配** | 查岗间隔、拉回间隔、锁定阈值、安静时段、深夜时段、冷却时间，全部由用户自己填，没有写死的默认值 |

### 目录结构

```
linjian-peek-public/
├── android/          App 源码（Java）
│   ├── app/src/main/java/dev/linjian/peek/   30+ 个类
│   ├── app/src/main/res/layout/              界面布局
│   ├── app/src/main/AndroidManifest.xml
│   └── build.sh                             纯 SDK 编译脚本
├── server/           后端
│   ├── linjian_server.py                      主服务（零第三方依赖）
│   ├── cloudflare-worker/worker.js           Cloudflare 版 MCP（89 工具）
│   ├── Dockerfile                             Railway 用
│   ├── .env.example                           配置样例
│   └── requirements.txt
├── mcp/              MCP 服务
│   ├── server.js                             102 个工具
│   └── package.json
├── docs/             文档（mcp.md 是完整工具手册）
├── .github/workflows/android-debug.yml        GitHub 自动打包 APK
├── render.yaml        Render 一键部署两个服务
├── start.sh           一键启动 MCP
├── README.md
└── LICENSE
```

---

## 第 1 章：拿到源码

### 方式 A：从 GitHub 下载（推荐）

仓库地址：**https://github.com/G-boy-wed/1**

1. 打开页面，点右上角绿色 **Code** 按钮
2. 点 **Download ZIP**
3. 解压后得到 `linjian-peek-public-main` 文件夹，**就是这个源码根目录**

> 关键：解压出来的目录里应该直接看到 `android/`、`server/`、`mcp/`、`README.md`，这才是源码根。
> 如果你看到的是一个叫 `1-main` 或 `linjian-peek-public-main` 的外层文件夹，进去一层才是。

### 方式 B：从我这里拿打包好的

- `linjian-peek-public-repo.zip` — 解压即源码根目录（适合直接导入 IDE）
- `linjian-peek-public-main-modified.zip` — 保留外层目录（适合上传 GitHub）

### 方式 C：命令行克隆

```bash
git clone https://github.com/G-boy-wed/1.git
cd 1
```

### 检查是否拿到完整代码

```bash
# 在源码根目录执行
ls
# 应该看到：android  docs  mcp  server  README.md  LICENSE  render.yaml  start.sh

python3 -m py_compile server/linjian_server.py && echo "后端语法 OK"
node --check mcp/server.js && echo "MCP 语法 OK"
node --check server/cloudflare-worker/worker.js && echo "Worker 语法 OK"
```

---

## 第 2 章：启动后端 server

后端是**零第三方依赖**的 Python 标准库服务，不需要 pip install 任何东西。

### 2.1 生成 Token

Token 是手机和 AI 之间的暗号，两边必须填一样的值。

```bash
python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
```

把这串输出**存好**，例如 `K7mQ2xLp9vBnR4tYsW8eJ1zC6dF3gH5nA`。下一步手机端和 MCP 都要填它。

### 2.2 启动

```bash
cd server
export LINJIAN_TOKEN="你的Token"
export LINJIAN_PORT=8513
export LINJIAN_HOST=0.0.0.0
export LINJIAN_DATA_DIR=./data

python3 linjian_server.py
```

看到这行就算成功：

```
掌心窗 server listening on 0.0.0.0:8513
```

### 2.3 验证

新开一个终端：

```bash
curl -H "X-Auth-Token: 你的Token" http://127.0.0.1:8513/api/tour/state
```

正常会返回一段 JSON，里面 `schedule` 就是当前的巡回策略（第一次是默认值）。

> 数据文件都落在 `server/data/`：`tour_schedule.json`（策略）、`companion_emotion.json`（陪伴对象情绪）、`emotion_state.json`（用户情绪）、`commands.json`（命令队列）。**备份直接拷这个目录。**

---

## 第 3 章：启动 MCP 服务

### 3.1 安装依赖

```bash
cd mcp
npm install
```

> 如果服务器上用 pnpm：`corepack enable && pnpm install`

### 3.2 启动

```bash
export LINJIAN_URL="http://127.0.0.1:8513"
export LINJIAN_TOKEN="你的Token"
export PORT=8787

node server.js
```

看到：

```
掌心窗 unified MCP listening on 0.0.0.0:8787
```

### 3.3 验证

```bash
curl http://127.0.0.1:8787/health
```

再确认工具数量：

```bash
grep -c "server.tool(" mcp/server.js
# 输出 102
```

### 3.4 MCP 完整环境变量表

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LINJIAN_URL` | 自动探测 | 后端地址。填错会导致所有工具失败 |
| `LINJIAN_TOKEN` | 空 | 必须与后端一致 |
| `PORT` | 8787 | MCP 监听端口 |
| `LINJIAN_DEFAULT_DEVICE` | android-phone | 目标设备 ID，手机端要填一样 |
| `LINJIAN_FETCH_TIMEOUT_MS` | 8000 | 普通请求超时 |
| `LINJIAN_COMMAND_QUEUE_TIMEOUT_MS` | 4500 | 命令入队等待 |
| `LINJIAN_COMMAND_STATUS_TIMEOUT_MS` | 1500 | 查询命令状态 |
| `LINJIAN_MAX_TOOL_WAIT_SECONDS` | 12 | 工具最长等待秒数（上限 20） |
| `LINJIAN_CARE_STATE_PATH` | ./care_state.json | 主动关心状态 |
| `LINJIAN_VISIT_STATE_PATH` | ./visit_state.json | 探访状态 |

---

## 第 4 章：手机端安装与连接

### 4.1 编译 APK

#### 方式 A：GitHub Actions（最省事，手机上直接下）

1. 推代码到 GitHub（第 11 章）
2. 打开仓库 → **Actions** 标签 → 左边选 **Build Android Debug APK**
3. 首次可能要点 **I understand, enable them**
4. 点 **Run workflow** → 等 3-5 分钟 → 绿色对勾 → 点进去下载 **app-debug.apk**

#### 方式 B：本地 Gradle

```bash
cd android
# 需要 Android SDK (platform 34) + JDK 17
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本仓库没有 gradle wrapper，需要本机有 `gradle` 命令。

#### 方式 C：纯 SDK 脚本（无 Gradle）

```bash
cd android
export ANDROID_HOME=$HOME/android-sdk
./build.sh
```

### 4.2 安装与授权

1. 把 APK 传到手机，安装（需允许「未知来源」）
2. 打开 App，依次授权：
   - **无障碍服务**（最关键，不开无法读屏、无法操作别的 App）
   - **悬浮窗权限**（归电全屏页、拉回弹窗盖在其他 App 上层都需要）
   - **通知权限**
   - **开机自启**（各家 ROM 路径不同，OPPO/小米/华为需单独开）
   - **电池优化**设为「不限制」

### 4.3 连接后端

App 内：**首页 → 守护 → 连接设置**

| 填什么 | 值 |
|---|---|
| 服务器地址 | `http://你的电脑IP:8513`（手机和电脑同一个 Wi-Fi） |
| Token | 第 2.1 步生成的那串 |
| 设备 ID | `android-phone`（默认，和 MCP 一致即可） |

电脑查 IP：

```bash
# macOS / Linux
ifconfig | grep "inet " | grep -v 127.0.0.1
# Windows
ipconfig
```

> 找不到局域网 IP？用 ngrok / cloudflared 打个公网隧道也行，填隧道地址。
> 电脑防火墙要放行 8513 端口。

### 4.4 验证连通

App 里有「控制测试」按钮，点一下能返回结果就说明手机→后端→App 三方都通了。

---

## 第 5 章：手机端设置：查岗与管束

**入口：首页 → 守护 → 查岗与管束**（原名「管束策略」）

这一页就是你要的「不再只能靠 MCP 改参数」。页面里能改的全部参数：

| 界面项 | 服务端字段 | 说明 |
|---|---|---|
| 启用巡回查岗 | `enabled` | 总开关，关掉后调度器完全不工作 |
| 查岗间隔（分钟） | `check_in_interval_minutes` | 多久查一次当前状态 |
| 强制拉回间隔（分钟） | `force_back_interval_minutes` | 多久弹一次拉回 |
| 冷却时间（分钟） | `cooldown_minutes` | 两次提醒之间的最小间隔，防打扰 |
| 单 App 锁定阈值（分钟） | `single_app_lock_minutes` | 某个 App 连续用超过这么久就锁 |
| 锁定时长（分钟） | `single_app_lock_duration_minutes` | 锁多久后自动解锁 |
| 安静时段起止 | `quiet_hours` | 这段时间不打扰（如 23:00-08:00） |
| 深夜时段起止 | `night_hours` | 这段时间判定为深夜，行为更收敛 |
| 拉回分级阈值（%） | `force_back_miss_threshold` | 想她百分比达到多少就变强级别（默认 70） |
| 弹窗语气 | `alert_tone` | 温柔 / 生气 |
| 豁免 App 列表 | `exempt_packages` | 逗号分隔的包名，这些 App 在前台时不锁、不拉回 |

页面底部还有四个按钮：

- **保存** — 写入服务端，立即生效，**不用重启 App**
- **立即查岗** — 手动触发一次巡回
- **测试弹窗** — 弹一个居中提醒看看效果
- **测试软 / 测试强** — 分别触发两种拉回弹窗

### 双向同步实测

手机端改完，MCP 立刻读到同一份值：

```bash
curl -H "X-Auth-Token: 你的Token" http://127.0.0.1:8513/api/tour/state
```

返回的 `schedule` 里就是你刚才在手机上填的值。反过来也一样，MCP 调用 `set_tour_schedule` 改完，手机端重新进页面就能看到新值。

> **为什么能同步？** 因为两边都读写 `server/data/tour_schedule.json` 这一个文件。手机端通过 `/api/tour/schedule`（POST 写、GET 读），MCP 通过同一组接口。**不存在第二套存储。**

---

## 第 6 章：归电主题与弹窗位置

「归电」是那个把手机从别的 App 拽回来的全屏页面。

### 6.1 主题（3 套）

**入口：首页 → 守护 → 归电设置**

| 主题名 | 特点 |
|---|---|
| **暮夜蓝紫** | 深色冷调，晚上看不刺眼 |
| **云海青灰** | 低饱和灰青，白天看着安静 |
| **落日莓雾** | 暖调粉紫，氛围感最强 |

### 6.2 弹窗位置（3 选 1）

同一页面可设置 **顶部 / 居中 / 底部**。

### 6.3 关键：一份配置，三处生效

这是 v0.3.9.0 重点改动。改主题或位置后，**下面三处同时跟着变**：

1. 归电全屏页配色
2. **拉回弹窗**（PullBackCard）配色与位置
3. **居中提醒弹窗**（AlertCenter）配色与位置

实现方式：抽出公共主题类 `android/app/src/main/java/dev/linjian/peek/GuidianTheme.java`，三处都从 `GuidianState` 读同一份值。**没有单独维护第二套样式。**

MCP 侧对应：

```js
set_guidian_config({
  theme: "暮夜蓝紫",        // 或 "云海青灰" / "落日莓雾"
  popup_position: "top"     // 或 "center" / "bottom"
})
```

---

## 第 7 章：陪伴对象情绪

### 7.1 它和「用户情绪快照」的区别

这是 v0.3.9.0 修正的一个方向性错误——原来「情绪快照」读的是用户手机状态，方向反了。

| | 用户状态情绪快照 | 陪伴对象情绪（新增） |
|---|---|---|
| 是什么 | 用户当前在干什么、心情如何（**读手机得出**） | **TA 自己的**情绪状态（**由 AI 写入**） |
| 存储 | `emotion_state.json` | `companion_emotion.json` |
| 接口 | `/api/emotion/snapshot`、`/api/emotion/tag` | `/api/companion/emotion` |
| MCP 工具 | `get_emotion_snapshot`、`write_emotion_tag` | `get_companion_emotion`、`write_companion_emotion` |
| 手机端 | 「此刻状态」页展示 | 「查岗与管束」页可显示、可手写 |

**两套完全独立，互不覆盖。** 原有快照保留。

### 7.2 字段

| 字段 | 含义 | 范围 |
|---|---|---|
| `emotion_tag` | 情绪标签 | 平静 / 想你 / 吃醋 / 生气 / 担心 / 委屈 … |
| `intensity` | 情绪强度 | 0-100 |
| `miss_percent` | 想她的百分比 | 0-100 |
| `note` | 备注 | 文本 |
| `updated_at` | 更新时间 | ISO 时间戳 |

### 7.3 MCP 读写

```js
// 写
write_companion_emotion({
  emotion_tag: "想你",
  intensity: 70,
  miss_percent: 55,
  note: "刚聊完半小时没回消息"
})

// 读
get_companion_emotion({})
```

### 7.4 连续性：不是静态覆盖

情绪会**随时间自然衰减**，也会**因长时间没回来而累积加深**：

| 变化 | 效果 |
|---|---|
| 每过 1 小时 | 想她百分比 -6，情绪强度 -8 |
| 每 30 分钟没回来 | 想她百分比 +4 |
| 回来并标记已返回 | 归零重置 |

实测：写入 90% → 1 小时后 84% → 再 3 小时没回来涨到 100%。

### 7.5 驱动三个东西

情绪值不是摆设，它会实际驱动：

1. **通知文案** — 按标签和想她百分比选措辞（`别躲了，TA在等你。` / `外面太晚了，TA有点担心你。`）
2. **弹窗语气** — 生气/吃醋时自动切 `angry`
3. **拉回频率** — 想她越深，拉回间隔越短（最短可缩到设定间隔的 35%）

---

## 第 8 章：分级拉回弹窗

v0.3.9.0 把「强制拉回直接跳转」改成了「先弹窗」。

### 8.1 分级规则

| 级别 | 触发条件 | 按钮 | 拒绝 |
|---|---|---|---|
| **软级别** | `miss_percent` < 阈值 | 「强制拉回」+「可以拒绝」 | **允许**，点「可以拒绝」展开理由抽屉（可填快捷理由或自写一句） |
| **强级别** | `miss_percent` ≥ 阈值，**或** 情绪为生气/吃醋/担心/委屈 | 「强制拉回」+「可以拒绝」（配色更重、生气语气） | **同样允许**，依旧给两选项，不剥夺拒绝入口 |

阈值默认 **70%**，在「查岗与管束」页面可改。

### 8.2 拒绝理由必须写日志

点「再等一会儿」→ 弹出理由输入框 → 必须填 → 写入三处：

1. 本地事件日志（`ActivityEventStore`）
2. 调试日志（`DebugState`）
3. 上报服务端事件流（AI 可以读到，用来调整策略）

### 8.3 显示层级

| 场景 | 实现 |
|---|---|
| 其他 App 在前台 | **悬浮窗**（WindowManager），盖在上面 |
| 锁屏 | 切**透明 Activity**（`PullBackActivity`），同一张卡片 |

### 8.4 自测

「查岗与管束」页面底部有 **测试软 / 测试强** 两个按钮，直接看两种效果。

---

## 第 9 章：接入你的 AI 客户端

### 9.1 支持的传输方式

| 地址 | 协议 |
|---|---|
| `http://host:8787/mcp` | Streamable HTTP（**推荐**） |
| `http://host:8787/sse` + `/messages` | SSE（旧版客户端） |

### 9.2 Claude Desktop 配置

在 `claude_desktop_config.json` 里加：

```json
{
  "mcpServers": {
    "zhangxinchuang": {
      "command": "node",
      "args": ["/绝对路径/mcp/server.js"],
      "env": {
        "LINJIAN_URL": "http://127.0.0.1:8513",
        "LINJIAN_TOKEN": "你的Token",
        "PORT": "8787"
      }
    }
  }
}
```

### 9.3 远程 MCP 客户端（Kelivo / 其他）

填 URL + Token：

```
URL:   https://你的域名/mcp
Token: 与后端一致的 LINJIAN_TOKEN
```

### 9.4 验证工具已加载

MCP 起来后，客户端应该能列出 **102 个工具**。手动调一个只读的验证：

```js
get_usage_snapshot({})      // 读用量快照
get_tour_schedule({})       // 读巡回策略
get_companion_emotion({})   // 读陪伴对象情绪
```

### 9.5 常用工具速查

完整手册见 `docs/mcp.md`。

| 分类 | 代表工具 |
|---|---|
| **屏幕** | `peek_screen`、`get_screen_nodes`、`tap`、`swipe`、`input_text` |
| **应用** | `open_app`、`close_app`、`get_app_list`、`save_known_app` |
| **守护** | `lock_app`、`unlock_app`、`screen_break_app`、`set_focus_plan` |
| **归电** | `get_guidian_state`、`set_guidian_config`、`trigger_guidian`、`mark_guidian_returned` |
| **弹窗** | `show_center_alert`、`force_back_to_chat` |
| **巡回** | `set_tour_schedule`、`get_tour_schedule`、`start_tour`、`stop_tour`、`check_now` |
| **情绪** | `get_companion_emotion`、`write_companion_emotion`、`get_emotion_snapshot`、`write_emotion_tag` |
| **提醒** | `set_reminder`、`list_reminders`、`send_notification`、`set_alarm` |
| **主动关心** | `care_action`、`send_phone_command` |

### 9.6 AI 门禁说明

AI 调用写操作时会被策略门禁检查：

- `active_care_enabled` — **真实开关**，关掉后所有主动关心行为都不执行
- `allowed_actions` — 策略建议清单，限制 AI 能做什么

这两个也都可以在手机端或 MCP 改。

---

## 第 10 章：云端部署（Render）

手机要在外网也能用，就把两个服务部署到云上。

### 10.1 Render Blueprint 一键部署

1. 打开 [render.com](https://render.com) 登录
2. **New → Blueprint**
3. 连你的 GitHub 仓库 `G-boy-wed/1`
4. Render 会读 `render.yaml`，**自动创建两个服务**：

| 服务名 | 类型 | 作用 |
|---|---|---|
| `zhangxinchuang-server` | Python | 后端（根目录 `server`） |
| `zhangxinchuang-mcp` | Node | MCP（根目录 `mcp`） |

Blueprint 会自动生成共享的 `LINJIAN_TOKEN`，且 MCP 的 `LINJIAN_URL` 会自动指向 server 的公网地址，**不用手填**。

5. 部署完记下两个地址：

```
https://zhangxinchuang-server-xxxx.onrender.com
https://zhangxinchuang-mcp-xxxx.onrender.com
```

### 10.2 免费版注意

- Render 免费版 **15 分钟无请求就休眠**，第一次调用会慢几十秒（冷启动），MCP 侧已把超时放宽到能容忍冷启动
- 服务间调用走公网，比内网慢一点点，但更稳（已修掉旧版 `hostport` fetch failed 的问题）

### 10.3 手机端填云端地址

「首页 → 守护 → 连接设置」：

```
服务器地址: https://zhangxinchuang-server-xxxx.onrender.com
Token:      Render 的 LINJIAN_TOKEN
```

### 10.4 Railway 手动部署

**server 服务：**

- Root Directory: `server`
- Build Command: 留空（用仓库里的 `Dockerfile`）
- Start Command: 留空（Dockerfile 里已有 `CMD`）
- 环境变量：`LINJIAN_TOKEN`（自己生成）、`LINJIAN_HOST=0.0.0.0`、`LINJIAN_KEEP=3`

**mcp 服务：**

- Root Directory: `mcp`
- Build Command: `npm install`
- Start Command: `npm start`
- 环境变量：`LINJIAN_TOKEN`（**与 server 一致**）、`LINJIAN_URL`（server 的公网地址）、`PORT=8787`

### 10.5 Cloudflare Worker 版 MCP

不想用 Render 的话，`server/cloudflare-worker/worker.js` 是独立版 MCP，**89 个工具**，跑在 Cloudflare Workers 上。

需要环境变量：

| 变量 | 说明 |
|---|---|
| `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` | 数据存储 |
| `KV` namespace | 命令队列与状态 |
| `LINJIAN_TOKEN` | 校验用 |

---

## 第 11 章：上传到 GitHub

### 11.1 如果你是第一次用 GitHub

**装 Git：**

```bash
# Windows: 到 https://git-scm.com 下载安装
# macOS: brew install git
# Ubuntu: sudo apt install git
```

**配置身份：**

```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱@example.com"
```

### 11.2 生成 Token

Fine-grained token（细粒度，推荐，只授权这一个仓库）：

1. 打开 GitHub → 右上角头像 → **Settings**
2. 左侧拉到最底部 → **Developer settings**
3. **Personal access tokens** → **Fine-grained tokens**
4. 点 **Generate new token**
5. **Token name** 随便填，比如 `push`
6. **Expiration** 选个短期限（比如 30 天，用完就删更安全）
7. **Repository access** → 选 **Only select repositories** → 勾选 `G-boy-wed/1`
8. **Repository permissions**：
   - **Contents** → **Read and write**
   - **Workflows** → **Read and write**（要传 `.github/workflows/` 就必须，否则 push 会被拒）
9. 点 **Generate token** → 复制那串 `github_pat_` 开头的码

> ⚠️ **Contents 不给写权限会 403；不给 Workflows 权限传工作流文件会被拒。** 这两个是最常见的坑。

### 11.3 推送代码

```bash
cd 你的源码目录

git init -b main
git add -A
git commit -m "掌心窗 v0.3.9.0 修改版"
git remote add origin https://github.com/G-boy-wed/1.git
git push -u origin main
```

推送时提示输入密码：**用户名填 GitHub 用户名**，**密码粘贴 token**（不是 GitHub 登录密码）。

也可以用我准备好的脚本，它会自动处理凭据：

```bash
bash push_to_github.sh
```

### 11.4 推送完立刻撤销 Token

Token 用完就作废，这是账号安全底线：

1. Settings → Developer settings → Personal access tokens → Fine-grained tokens
2. 点进你的 token → 右边红色 **Delete** → 确认

### 11.5 用 Actions 自动打 APK

代码推上去后：

1. 仓库 → **Actions** 标签
2. 左边选 **Build Android Debug APK**
3. 点 **Run workflow** → 等几分钟
4. 绿色对勾 → 点进去 → **Artifacts** 下载 APK

手机浏览器打开仓库也能下。

---

## 第 12 章：排错手册

| 症状 | 原因 | 解决 |
|---|---|---|
| MCP 工具全报连不上 | `LINJIAN_URL` 填错或后端没起 | 浏览器打开 `http://host:8513/health` 看是否通 |
| 后端 403 | Token 两边不一致 | 手机端和 MCP 的 `LINJIAN_TOKEN` 必须完全相同 |
| push 报 403 | token 没给 Contents 写权限 | 编辑 token → Repository permissions → Contents → Read and write |
| push 报 workflow 拒绝 | 没给 Workflows 权限 | 同上，加 Workflows → Read and write |
| 手机收不到命令 | App 没连上后端 | 检查「连接设置」地址，端口 8513 防火墙是否放行 |
| 没有悬浮窗 | 没开悬浮窗权限 | 设置里给「显示在其他应用上层」 |
| 无障碍不生效 | 没开无障碍服务 | 设置里开「无障碍服务」→ 找到掌心窗 |
| 拉回弹窗没出现 | 前台 App 在豁免列表 | 检查「查岗与管束」的豁免 App 列表 |
| 弹窗颜色不对 | 主题没同步 | 归电设置里改主题后重进 App |
| 改了参数不生效 | 没点保存 | 「查岗与管束」页面点 **保存** |
| Android 编译报错 | 缺 SDK / JDK 版本不对 | 需要 JDK 17 + platform 34 |
| `npm install` 失败 | 网络 | 换国内镜像：`npm config set registry https://registry.npmmirror.com` |

---

## 附：快速命令清单

```bash
# 启动后端
cd server && LINJIAN_TOKEN=你的Token LINJIAN_PORT=8513 python3 linjian_server.py

# 启动 MCP
cd mcp && npm install && LINJIAN_URL=http://127.0.0.1:8513 LINJIAN_TOKEN=你的Token PORT=8787 node server.js

# 读巡回策略
curl -H "X-Auth-Token: 你的Token" http://127.0.0.1:8513/api/tour/state

# 读陪伴对象情绪
curl -H "X-Auth-Token: 你的Token" http://127.0.0.1:8513/api/companion/emotion

# 写陪伴对象情绪
curl -X POST -H "X-Auth-Token: 你的Token" -H "Content-Type: application/json" \
  -d '{"emotion_tag":"想你","intensity":70,"miss_percent":55}' \
  http://127.0.0.1:8513/api/companion/emotion

# MCP 健康检查
curl http://127.0.0.1:8787/health
```

---

**版本**：v0.3.9.0 修改版
**仓库**：https://github.com/G-boy-wed/1
**工具数**：MCP 102 个 / Cloudflare Worker 89 个
**隐私**：无私人绑定信息，所有参数用户自定
