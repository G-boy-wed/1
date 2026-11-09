#!/usr/bin/env bash
# ============================================================================
# 掌心窗 unified v0.4.0 —— 唯一可靠的单进程启动脚本
# 彻底移除 Node.js(mcp, 8787)：REST API + MCP + SQLite 全部由 Python(8513) 单进程接管。
# 手机端 / AI(MCP) 客户端 / 网页前端统一访问 http://<host>:8513。
# ============================================================================
set -e

cd "$(dirname "$0")/server"

# 若未设置访问令牌，自动生成一份长随机值并写入 .env，保证开箱即用。
if [ -z "${LINJIAN_TOKEN:-}" ]; then
  if [ -f .env ] && grep -q '^LINJIAN_TOKEN=' .env; then
    # 复用已有 .env（不覆盖）
    set -a; . ./.env; set +a
  else
    GEN="lj_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    printf 'LINJIAN_TOKEN=%s\n' "$GEN" >> .env
    export LINJIAN_TOKEN="$GEN"
    echo "已生成访问令牌并写入 server/.env（手机端「连接设置」需填相同 token）。"
  fi
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
