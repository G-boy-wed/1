/// 会话级 MCP 绑定配置（硬性要求 #2 的核心：确认会话确实绑定了掌心窗 Server）。
///
/// 这是「会话 → MCP 服务器」的绑定关系，独立于全局服务器列表。
/// 关键职责：
///   - 记录该会话是否启用 MCP、用户显式开启了哪些服务器；
///   - 通过 [effectiveServerIds] 返回「真正会注入工具的服务器列表」，
///     并在「用户没选但全局有可用服务器」时兜底注入第一个，禁止被空配置覆盖；
///   - 依据 per-conversation 的裁剪策略生成 [McpPruningPolicy]，确保只暴露必要工具。
///
/// 依赖：services/mcp/mcp_tool_service.dart 的 McpPruneMode / McpPruningPolicy。
import '../services/mcp/mcp_tool_service.dart';

/// 会话级 MCP 设置。
class ConversationMcpSettings {
  final String conversationId;
  final bool mcpEnabled; // 该会话是否开启 MCP 工具调用
  final List<String> enabledServerIds; // 用户在该会话显式开启的服务器 ID
  final List<String> availableServerIds; // 全局已注册且健康的服务器 ID（由注册表提供）
  final McpPruneMode pruneMode; // 该会话的工具裁剪模式
  final Set<String> allowlist; // custom 模式白名单
  final Set<String> blocklist; // custom 模式黑名单

  const ConversationMcpSettings({
    required this.conversationId,
    this.mcpEnabled = true,
    this.enabledServerIds = const [],
    this.availableServerIds = const [],
    this.pruneMode = McpPruneMode.coreOnly,
    this.allowlist = const {},
    this.blocklist = const {},
  });

  /// 真正会注入 LLM 的服务器 ID 列表（硬性要求 #2 的「会话绑定有效性」校验点）。
  ///
  /// 规则（严格，禁止空配置覆盖工具注入）：
  ///   1. 未启用 MCP → 返回空（不注入任何工具）；
  ///   2. 取「用户开启 ∩ 全局可用」的交集；
  ///   3. 交集为空但全局仍有可用服务器时，兜底注入第一个可用服务器，
  ///      避免「用户没选 / 配置为空」导致 tools 字段被静默剥离或覆盖为空。
  List<String> get effectiveServerIds {
    if (!mcpEnabled) return const [];
    final set = <String>{};
    for (final id in enabledServerIds) {
      if (availableServerIds.contains(id)) set.add(id);
    }
    if (set.isEmpty && availableServerIds.isNotEmpty) {
      set.add(availableServerIds.first);
    }
    return set.toList();
  }

  /// 该会话是否应当注入 MCP 工具。
  bool get hasEffectiveServers => effectiveServerIds.isNotEmpty;

  /// 依据本会话的裁剪模式生成裁剪策略。
  McpPruningPolicy buildPruningPolicy() {
    switch (pruneMode) {
      case McpPruneMode.all:
        return const McpPruningPolicy(mode: McpPruneMode.all);
      case McpPruneMode.custom:
        return McpPruningPolicy(
          mode: McpPruneMode.custom,
          allowlist: allowlist,
          blocklist: blocklist,
        );
      case McpPruneMode.coreOnly:
        return const McpPruningPolicy(mode: McpPruneMode.coreOnly);
    }
  }

  ConversationMcpSettings copyWith({
    String? conversationId,
    bool? mcpEnabled,
    List<String>? enabledServerIds,
    List<String>? availableServerIds,
    McpPruneMode? pruneMode,
    Set<String>? allowlist,
    Set<String>? blocklist,
  }) =>
      ConversationMcpSettings(
        conversationId: conversationId ?? this.conversationId,
        mcpEnabled: mcpEnabled ?? this.mcpEnabled,
        enabledServerIds: enabledServerIds ?? this.enabledServerIds,
        availableServerIds: availableServerIds ?? this.availableServerIds,
        pruneMode: pruneMode ?? this.pruneMode,
        allowlist: allowlist ?? this.allowlist,
        blocklist: blocklist ?? this.blocklist,
      );
}
