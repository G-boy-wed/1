/// MCP 服务器注册表（聊天层与 McpToolService 之间的粘合层）。
///
/// 职责：
///   - 持有多个 MCP 服务器（id → McpServerConfig + McpToolService）；
///   - 暴露 [availableServerIds] 给 ConversationMcpSettings 做会话绑定校验；
///   - [collectFunctionDeclarations] 按会话裁剪策略聚合「各启用服务器的核心工具声明」
///     （已逐条清洗，符合标准 JSON Schema / OpenAPI 3.0，准备注入 LLM）；
///   - [callToolForConversation] 依据工具名路由到对应服务器并调用
///     （复用 McpToolService 的串行队列锁 + 超时保护，永不抛异常打断对话）。
///
/// 依赖：
///   providers/mcp_provider.dart 的 McpServerConfig
///   services/mcp/mcp_tool_service.dart 的 McpToolService / McpSchemaSanitizer /
///     McpToolResult / McpImageCache / McpPruningPolicy
///   models/conversation_mcp_settings.dart 的 ConversationMcpSettings
import 'dart:async';

import 'package:http/http.dart' as http;

import '../../models/conversation_mcp_settings.dart';
import '../../providers/mcp_provider.dart';
import 'mcp_tool_service.dart';

class _ServerEntry {
  final McpServerConfig config;
  final McpToolService service;
  _ServerEntry(this.config, this.service);
}

/// 全局 / 会话级 MCP 工具注册表。
class McpToolRegistry {
  final McpImageCache imageCache;
  final http.Client? _externalClient;
  final Map<String, _ServerEntry> _servers = <String, _ServerEntry>{};
  // 工具名 → 服务器 id 索引（懒构建，加速 callToolForConversation 路由）。
  final Map<String, String> _toolIndex = <String, String>{};
  bool _indexBuilt = false;

  McpToolRegistry(this.imageCache, {http.Client? client})
      : _externalClient = client;

  /// 注册一个服务器并立即构建其工具服务。
  void register(McpServerConfig config, {McpPruningPolicy? pruning}) {
    final svc = McpToolService(
      baseUrl: config.baseUrl,
      authToken: config.authToken,
      pruning: pruning ?? const McpPruningPolicy(),
      imageCache: imageCache,
      client: _externalClient,
    );
    _servers[config.id] = _ServerEntry(config, svc);
    _indexBuilt = false;
  }

  void unregister(String id) {
    final e = _servers.remove(id);
    e?.service.dispose();
    _indexBuilt = false;
  }

  /// 当前已注册且健康的服务器 ID（供 ConversationMcpSettings 校验会话绑定）。
  List<String> get availableServerIds => _servers.keys.toList();

  McpToolService? serviceOf(String id) => _servers[id]?.service;

  /// 按会话裁剪策略聚合「启用服务器」的工具声明（已清洗）。
  /// 仅返回 effectiveServerIds 内的服务器、且经会话级裁剪后的工具，禁止全量未清洗暴露。
  Future<List<Map<String, dynamic>>> collectFunctionDeclarations(
    ConversationMcpSettings settings,
  ) async {
    final out = <Map<String, dynamic>>[];
    final policy = settings.buildPruningPolicy();
    for (final id in settings.effectiveServerIds) {
      final e = _servers[id];
      if (e == null) continue;
      try {
        final all = await e.service.listTools();
        final pruned = policy.prune(all);
        for (final t in pruned) {
          // McpSchemaSanitizer 已剥离 .describe() 等非法注入、补全 items.type，
          // 输出为标准 JSON Schema（OpenAI / OpenAPI 3.0 兼容）。
          out.add(McpSchemaSanitizer.toFunctionDeclaration(t));
        }
      } catch (_) {
        // 单个服务器不可达不影响其它服务器；调用处会据结果判定是否注入 tools。
      }
    }
    return out;
  }

  /// 懒构建「工具名 → 服务器 id」索引，用于工具调用路由。
  Future<void> _ensureIndex() async {
    if (_indexBuilt) return;
    _toolIndex.clear();
    for (final entry in _servers.entries) {
      try {
        final tools = await entry.value.service.listTools();
        for (final t in tools) {
          _toolIndex.putIfAbsent(t.name, () => entry.key);
        }
      } catch (_) {
        // 不可达服务器跳过
      }
    }
    _indexBuilt = true;
  }

  /// 会话级工具调用入口（硬性要求 #3 的「回调」落点）。
  /// 依据工具名路由到对应服务器并执行，复用串行队列锁 + 超时保护。
  Future<McpToolResult> callToolForConversation(
    String conversationId,
    String toolName,
    Map<String, dynamic> args,
  ) async {
    try {
      await _ensureIndex();
      final serverId = _toolIndex[toolName];
      if (serverId == null) {
        return McpToolResult.error(
          '会话 $conversationId：未找到可处理工具「$toolName」的 MCP 服务器。',
        );
      }
      final svc = _servers[serverId]!.service;
      return await svc.callTool(toolName, args);
    } catch (e) {
      return McpToolResult.error(
        '会话 $conversationId：工具「$toolName」路由或调用异常：${e.toString()}',
      );
    }
  }

  void dispose() {
    for (final e in _servers.values) e.service.dispose();
    _servers.clear();
    _toolIndex.clear();
  }
}
