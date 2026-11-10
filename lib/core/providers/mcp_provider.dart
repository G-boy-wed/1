/// 掌心窗 Flutter 客户端 —— MCP Provider（状态管理 + 多服务器启用 + 历史消息）
///
/// 职责：
///   - 管理多个 MCP 服务器配置，仅向模型暴露「当前启用服务器」的核心工具（硬性要求 #2）。
///   - 封装 McpToolService 调用，调用结果统一进入聊天历史。
///   - 历史消息里的图片只存 McpImageSnapshot 的「路径引用 / 轻量元数据」（硬性要求 #4），
///     绝不把几兆 Base64 原样持久化进 JSON，避免内存暴涨与渲染卡死。
///   - 复用串行队列锁与超时配置（硬性要求 #1 / #3 在服务层实现，这里透传）。
///
/// 依赖（pubspec.yaml）：
///   provider: ^6.1.0
///   （并依赖 services/mcp/mcp_tool_service.dart 的 http / path_provider / path）

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:provider/provider.dart';

import '../services/mcp/mcp_tool_service.dart';

/// 兼容旧版 Dart（<3.0 无 Iterable.firstOrNull）的辅助函数。
T? _firstOrNull<T>(Iterable<T> it) {
  for (final e in it) return e;
  return null;
}

/// 单个 MCP 服务器配置。
class McpServerConfig {
  final String id;
  final String name;
  final String baseUrl; // 例如 http://192.168.x.x:8513/mcp
  final String? authToken;
  final bool enabled; // 是否为「当前启用服务器」

  const McpServerConfig({
    required this.id,
    required this.name,
    required this.baseUrl,
    this.authToken,
    this.enabled = true,
  });
}

/// 一条聊天历史消息（工具结果 / 用户 / 助手）。图片仅存路径引用。
class McpChatMessage {
  final String role; // 'user' | 'assistant' | 'tool'
  final String? text;
  final List<McpImageSnapshot> images; // 仅路径引用 + 元数据
  final String? toolName;
  final DateTime at;

  McpChatMessage({
    required this.role,
    this.text,
    this.images = const [],
    this.toolName,
    DateTime? at,
  }) : at = at ?? DateTime.now();

  Map<String, dynamic> toHistoryJson() => <String, dynamic>{
        'role': role,
        'text': text,
        'toolName': toolName,
        'at': at.toIso8601String(),
        // ④ 图片只落路径 + 元数据，绝不带 Base64
        'images': images.map((i) => i.toHistoryJson()).toList(),
      };

  factory McpChatMessage.fromHistoryJson(Map<String, dynamic> m) =>
      McpChatMessage(
        role: m['role'] as String,
        text: m['text'] as String?,
        toolName: m['toolName'] as String?,
        at: DateTime.tryParse(m['at'] as String? ?? '') ?? DateTime.now(),
        images: <McpImageSnapshot>[
          for (final im in (m['images'] as List? ?? const []))
            McpImageSnapshot.fromHistoryJson(im as Map<String, dynamic>),
        ],
      );
}

/// MCP Provider：暴露给 UI 的状态 + 动作入口。
class McpProvider extends ChangeNotifier {
  final List<McpServerConfig> servers;
  String? _enabledServerId;
  McpToolService? _service;
  McpImageCache? _imageCache;
  List<McpToolDefinition> _tools = <McpToolDefinition>[];
  List<McpChatMessage> _history = <McpChatMessage>[];
  String? _status;
  bool _busy = false;
  final McpPruningPolicy pruning;

  McpProvider({
    required this.servers,
    String? enabledServerId,
    this.pruning = const McpPruningPolicy(),
  }) : _enabledServerId = enabledServerId ??
            _firstOrNull(servers.where((s) => s.enabled).map((s) => s.id));

  McpToolService? get service => _service;
  List<McpToolDefinition> get tools => _tools;
  List<McpChatMessage> get history => _history;
  bool get busy => _busy;
  String? get status => _status;
  String? get enabledServerId => _enabledServerId;

  McpServerConfig? get _enabled =>
      _firstOrNull(servers.where((s) => s.id == _enabledServerId));

  /// 切换当前启用服务器（硬性要求 #2：只暴露启用服务器的核心工具）。
  Future<void> setEnabledServer(String id) async {
    if (_enabledServerId == id) return;
    _enabledServerId = id;
    await connect();
    notifyListeners();
  }

  /// 连接当前启用服务器并拉取工具清单。
  Future<void> connect() async {
    final cfg = _enabled;
    if (cfg == null) {
      _status = '未配置启用的 MCP 服务器';
      notifyListeners();
      return;
    }
    // 进程级复用图片缓存目录
    _imageCache ??= await McpImageCache.create();
    _service?.dispose();
    _service = McpToolService(
      baseUrl: cfg.baseUrl,
      authToken: cfg.authToken,
      pruning: pruning,
      imageCache: _imageCache!,
    );
    try {
      await _service!.initialize();
      _tools = await _service!.listTools();
      _status = '已连接：${cfg.name}（工具 ${_tools.length} 个）';
    } catch (e) {
      _status = '连接失败：${e.toString()}';
    }
    notifyListeners();
  }

  /// 硬性要求 #2：仅暴露「当前启用服务器」的核心工具，且已清洗，禁止全量未清洗暴露。
  Future<List<Map<String, dynamic>>> functionDeclarationsForModel() async {
    if (_service == null) return const [];
    return _service!.buildFunctionDeclarations();
  }

  /// 调用工具并把结果写入历史（图片只存路径引用）。
  Future<McpToolResult> callTool(
    String name, [
    Map<String, dynamic> args = const {},
  ]) async {
    if (_service == null) {
      return McpToolResult.error('MCP 未连接，请先调用 connect()。');
    }
    _busy = true;
    notifyListeners();
    final result = await _service!.callTool(name, args);
    _history.add(McpChatMessage(
      role: 'tool',
      toolName: name,
      text: result.isError ? result.errorText : result.text,
      images: result.images,
    ));
    _busy = false;
    notifyListeners();
    return result;
  }

  void addUserMessage(String text,
      {List<McpImageSnapshot> images = const []}) {
    _history.add(McpChatMessage(
      role: 'user',
      text: text,
      images: images,
    ));
    notifyListeners();
  }

  void addAssistantMessage(String text) {
    _history.add(McpChatMessage(role: 'assistant', text: text));
    notifyListeners();
  }

  /// 导出历史为 JSON（图片仅含路径 / 元数据）。
  Map<String, dynamic> exportHistory() => <String, dynamic>{
        'server': _enabled?.id,
        'messages': _history.map((m) => m.toHistoryJson()).toList(),
      };

  /// 从 JSON 恢复历史（图片路径会指向已落盘的临时文件）。
  void loadHistory(Map<String, dynamic> data) {
    _history = <McpChatMessage>[
      for (final m in (data['messages'] as List? ?? const []))
        McpChatMessage.fromHistoryJson(m as Map<String, dynamic>),
    ];
    notifyListeners();
  }

  /// 清空历史并删除落盘截图，释放空间。
  void clearHistory() {
    for (final m in _history) {
      for (final im in m.images) {
        im.dispose();
      }
    }
    _history.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    _service?.dispose();
    super.dispose();
  }
}

/// 便捷构造：在应用根结点使用
///   mcpProvider(servers: [...])
ChangeNotifierProvider<McpProvider> mcpProvider({
  required List<McpServerConfig> servers,
  String? enabledServerId,
  McpPruningPolicy pruning = const McpPruningPolicy(),
}) =>
    ChangeNotifierProvider<McpProvider>(
      create: (_) => McpProvider(
        servers: servers,
        enabledServerId: enabledServerId,
        pruning: pruning,
      ),
    );
