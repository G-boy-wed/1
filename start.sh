#!/usr/bin/env bash
# ============================================================================
# 掌心窗 unified v0.4.0 —— 唯一可靠的单进程启动脚本
# 彻底移除 Node.js(mcp, 8787)：REST API + MCP + SQLite 全部由 Python(8513) 单进程接管。
# 手机端 / AI(MCP) 客户端 / 网页前端统一访问 http://<host>:8513。
# ============================================================================
set -e

cd "$(dirname "$0")/server"

# v0.4.6：规范化 token，杜绝「偶发丢失 / 重复写入导致手机端 403」的元凶。
# 根因：bash source 是「后写生效」，而服务端 load_dotenv 是「前写生效」(setdefault)，
# 一旦 .env 出现多条 LINJIAN_TOKEN= 行，两端取到不同值 -> 手机端 /api/poll 偶发 403。
# 本段保证 .env 最终只保留【一条】规范 token 行，且环境变量与服务端完全一致。
ENV_FILE=".env"
strip_tokens() {
  # 移除所有现存 LINJIAN_TOKEN= 行（保留其余配置），为单条规范行让路。
  if [ -f "$ENV_FILE" ]; then
    grep -v '^[[:space:]]*LINJIAN_TOKEN[[:space:]]*=' "$ENV_FILE" > "$ENV_FILE.tmp" || true
    mv "$ENV_FILE.tmp" "$ENV_FILE"
  fi
}
if [ -z "${LINJIAN_TOKEN:-}" ]; then
  # 外部未注入：优先复用 .env 中已存在的有效 token（不重新生成，避免与手机端不一致）。
  if [ -f "$ENV_FILE" ] && grep -q '^[[:space:]]*LINJIAN_TOKEN=[[:space:]]*[^[:space:]]' "$ENV_FILE"; then
    set -a; . ./"$ENV_FILE"; set +a
    # 规范化为单条：strip 掉旧行后把当前生效的 token 重新写回一条。
    CUR="$LINJIAN_TOKEN"
    strip_tokens
    printf 'LINJIAN_TOKEN=%s\n' "$CUR" >> "$ENV_FILE"
  else
    GEN="lj_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    strip_tokens
    printf 'LINJIAN_TOKEN=%s\n' "$GEN" >> "$ENV_FILE"
    export LINJIAN_TOKEN="$GEN"
    echo "已生成访问令牌并写入 server/.env（手机端「连接设置」需填相同 token）。"
  fi
else
  # 外部已注入（如 Render generateValue）：同步规范化进 .env 单一行，保证 source/dotenv 一致。
  strip_tokens
  printf 'LINJIAN_TOKEN=%s\n' "$LINJIAN_TOKEN" >> "$ENV_FILE"
fi
# 最后再 source 一次，确认环境变量与 .env 单条规范值完全一致（消除错位的最后一道保险）。
if [ -f "$ENV_FILE" ]; then set -a; . ./"$ENV_FILE"; set +a; fi
if [ -z "${LINJIAN_TOKEN:-}" ]; then
  echo "严重错误：LINJIAN_TOKEN 仍为空，无法保证鉴权稳定，终止启动。" >&2
  exit 1
fi
export LINJIAN_PORT=8513
export LINJIAN_HOST=0.0.0.0

# 单进程：先停掉旧实例，再后台启动 Python 统一服务。
# 用 pgrep 精确匹配「python3 + linjian_server.py」且排除本脚本/当前 shell，
# 避免 pkill -f 自匹配把启动脚本自己杀掉（SIGTERM）。
for pid in $(pgrep -f "python3 .*linjian_server\.py" 2>/dev/null); do
  [ "$pid" = "$$" ] && continue
  kill "$pid" 2>/dev/null || true
done
sleep 1
nohup python3 linjian_server.py --port 8513 > server.log 2>&1 &
echo "掌心窗公共服务已在 8513 端口启动完毕（REST + MCP + SQLite 单进程）。"
sleep 2
curl -s http://127.0.0.1:8513/health
echo ""
