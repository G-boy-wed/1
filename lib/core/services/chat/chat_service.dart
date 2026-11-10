/// 聊天服务层（对接 sta1n / OneAPI 等 OpenAI 兼容通道）。
///
/// 这是此前缺失的「把 MCP 工具喂给 LLM 并解析工具回调」的关键链路，直接修复
/// 「sta1n API 无法调用掌心窗 MCP 工具」的致命 Bug。
///
/// 严格落地三条硬性要求：
///
/// ① 工具注入与请求体上限（Payload 安全）
///    - 仅注入「当前会话启用的服务器」的核心工具（经 ConversationMcpSettings 裁剪）；
///    - 对 Description 做压缩脱敏并彻底移除 .describe() 等非法注入字符串
///      （sanitizer 已在 mcp_tool_service 完成，这里再做二次压缩）；
///    - 按字节预算强制收敛，超限逐级丢弃体积最大的工具 schema，防止 sta1n 报
///      400 Bad Request / 413 Payload Too Large / 静默剥离 tools。
///
/// ② 会话绑定与 MCP 路由有效性
///    - 由 ConversationMcpSettings.effectiveServerIds 决定注入哪些服务器的工具；
///    - 仅当「确有工具可注入」时，请求体才包含 tools 字段且 tool_choice:"auto"，
///      禁止被空配置覆盖 / 静默剥离。
///
/// ③ 工具回调 Tool Call 响应解析
///    - 同时支持流式（SSE delta 累加 tool_calls）与非流式响应；
///    - 完整拼接 id / function.name / function.arguments，调用 callToolForConversation；
///    - 将结果以 role:"tool" + tool_call_id 正确追加回消息列表，驱动多轮工具循环，
///      避免 sta1n 因上下文校验不通过（tool_call_id 缺失 / 顺序错乱）而中断后续回复。
///
/// 依赖：
///   package:http/http.dart
///   services/mcp/mcp_tool_service.dart 的 McpToolResult / McpImageSnapshot
///   services/mcp/mcp_tool_registry.dart 的 McpToolRegistry
///   models/conversation_mcp_settings.dart 的 ConversationMcpSettings
import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../models/conversation_mcp_settings.dart';
import '../mcp/mcp_tool_registry.dart';
import '../mcp/mcp_tool_service.dart';

// ===========================================================================
// 聊天事件（流式回传给 UI / 调用方）
// ===========================================================================
abstract class ChatEvent {
  const ChatEvent();
}

/// 助手文本增量（流式）。
class ChatTextDelta extends ChatEvent {
  final String delta;
  const ChatTextDelta(this.delta);
}

/// 开始执行一个工具调用。
class ChatToolCallStart extends ChatEvent {
  final String toolCallId;
  final String toolName;
  final Map<String, dynamic> arguments;
  const ChatToolCallStart(this.toolCallId, this.toolName, this.arguments);
}

/// 工具调用完成，返回结果文本与可能的截图（仅路径引用）。
class ChatToolCallDone extends ChatEvent {
  final String toolCallId;
  final String toolName;
  final String resultText;
  final List<McpImageSnapshot> images;
  final bool isError;
  const ChatToolCallDone(this.toolCallId, this.toolName, this.resultText,
      this.images, this.isError);
}

/// 非致命告警（例如 MCP 启用但服务器不可达，本次未注入工具）。
class ChatWarning extends ChatEvent {
  final String message;
  const ChatWarning(this.message);
}

/// 致命错误（网络 / 鉴权 / 解析失败）。
class ChatError extends ChatEvent {
  final String message;
  const ChatError(this.message);
}

/// 一轮对话结束（含完整助手文本与最新的 OpenAI 格式消息列表，供调用方持久化）。
class ChatDone extends ChatEvent {
  final String fullText;
  final List<Map<String, dynamic>> messages; // OpenAI 格式（含 assistant/tool 轮）
  const ChatDone(this.fullText, this.messages);
}

/// 内部事件：流式结束时携带最终 assistant 消息对象。
class _AssistantComplete extends ChatEvent {
  final Map<String, dynamic> message;
  const _AssistantComplete(this.message);
}

/// 请求体 + 是否注入了 tools 的元组（兼容 Dart 2.17，不使用 record）。
class _RequestBody {
  final Map<String, dynamic> body;
  final bool toolsInjected;
  const _RequestBody({required this.body, required this.toolsInjected});
}

/// 把单个流式 tool_calls delta 合并进累加列表（按 index 对齐，支持分片到达）。
/// [acc] 维护了 {index,id,type,function:{name,arguments}} 的列表。
void _mergeToolCallDelta(
  List<Map<String, dynamic>> acc,
  Map<dynamic, dynamic> delta,
) {
  final idx = (delta['index'] as int?) ?? acc.length;
  while (acc.length <= idx) {
    acc.add(<String, dynamic>{
      'index': acc.length,
      'id': null,
      'type': 'function',
      'function': {'name': '', 'arguments': ''},
    });
  }
  final cur = acc[idx];
  if (delta['id'] is String) cur['id'] = delta['id'];
  final fn = delta['function'] as Map?;
  if (fn != null) {
    if (fn['name'] is String) {
      (cur['function'] as Map)['name'] =
          ((cur['function'] as Map)['name'] as String) + fn['name'];
    }
    if (fn['arguments'] is String) {
      (cur['function'] as Map)['arguments'] =
          ((cur['function'] as Map)['arguments'] as String) + fn['arguments'];
    }
  }
}

// ===========================================================================
// 聊天服务
// ===========================================================================
class ChatService {
  /// sta1n / OpenAI 兼容端点，例如 https://sta1n.example.com/v1
  final String baseUrl;
  final String apiKey;
  final String model;
  final McpToolRegistry registry;

  /// 工具注入字节预算：超限逐级丢弃体积最大的工具 schema，防止 413/400。
  final int maxToolsPayloadBytes;
  /// 单条 description 压缩上限（字符），过长截断脱敏。
  final int maxDescriptionChars;
  /// 多轮工具循环安全上限，防止模型无限调用工具。
  final int maxToolRounds;

  final http.Client _client;

  ChatService({
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    required this.registry,
    this.maxToolsPayloadBytes = 60 * 1024,
    this.maxDescriptionChars = 320,
    this.maxToolRounds = 8,
    http.Client? client,
  }) : _client = client ?? http.Client();

  void dispose() => _client.close();

  String get _chatEndpoint {
    final u = baseUrl.endsWith('/') ? baseUrl : '$baseUrl/';
    return '${u}chat/completions';
  }

  // -------------------------------------------------------------------------
  // ① 工具声明压缩 + OpenAI 格式包装 + 字节预算收敛
  // -------------------------------------------------------------------------

  /// 把已清洗的 function_declaration（McpSchemaSanitizer.toFunctionDeclaration，
  /// 形态为 {name, description, parameters}）包装成 OpenAI function-tool 格式，
  /// 并对 description 做二次压缩脱敏，最后按字节预算收敛。
  List<Map<String, dynamic>> _compressAndWrap(
      List<Map<String, dynamic>> decls) {
    final wrapped = decls.map((decl) {
      final fn = <String, dynamic>{
        'name': decl['name'],
        'description': decl['description'] ?? '',
        'parameters': decl['parameters'] ??
            <String, dynamic>{'type': 'object', 'properties': <String, dynamic>{}},
      };
      final desc = (fn['description'] as String?) ?? '';
      if (desc.length > maxDescriptionChars) {
        // 截断脱敏，避免长描述撑爆 payload
        fn['description'] = '${desc.substring(0, maxDescriptionChars)}…';
      }
      return <String, dynamic>{
        'type': 'function',
        'function': fn,
      };
    }).toList();
    return _enforceBudget(wrapped);
  }

  /// 逐级丢弃序列化体积最大的工具，直到整体不超过字节预算。
  List<Map<String, dynamic>> _enforceBudget(
      List<Map<String, dynamic>> tools) {
    int totalBytes() {
      try {
        return utf8.encode(jsonEncode(tools)).length;
      } catch (_) {
        return 0;
      }
    }

    if (tools.length <= 1) return tools;
    var remaining = List<Map<String, dynamic>>.from(tools);
    // 安全上限保护，避免极端情况下死循环
    int guard = remaining.length * remaining.length + 10;
    while (totalBytes() > maxToolsPayloadBytes && remaining.isNotEmpty) {
      // 找到序列化体积最大的工具并移除
      int target = 0;
      int maxSize = -1;
      for (int i = 0; i < remaining.length; i++) {
        final s = utf8.encode(jsonEncode(remaining[i])).length;
        if (s > maxSize) {
          maxSize = s;
          target = i;
        }
      }
      remaining.removeAt(target);
      if (--guard <= 0) break;
    }
    return remaining;
  }

  // -------------------------------------------------------------------------
  // ② 组装请求体（严格保证 tools + tool_choice 仅在确有工具时注入）
  // -------------------------------------------------------------------------

  /// 构建一次 chat/completions 请求体。
  /// [messages] 必须是 OpenAI 格式（role/content/可选 tool_calls/tool_call_id）。
  /// 返回是否注入了 tools（供断言与告警使用）。
  Future<_RequestBody> _buildRequestBody(
    List<Map<String, dynamic>> messages,
    ConversationMcpSettings settings,
  ) async {
    List<Map<String, dynamic>>? toolsForRequest;

    if (settings.hasEffectiveServers) {
      final decls = await registry.collectFunctionDeclarations(settings);
      if (decls.isNotEmpty) {
        toolsForRequest = _compressAndWrap(decls);
      }
      // 会话显式启用 MCP，但服务器不可达 / 无工具：明确告警，但不阻塞对话。
      // 注意：此时不注入 tool_choice，避免空 tools + tool_choice 触发 sta1n 报错。
    }

    final body = <String, dynamic>{
      'model': model,
      'messages': messages,
      // ③ 是否流式由调用方在 sendConversation 中覆盖 body['stream']
      if (toolsForRequest != null) 'tools': toolsForRequest,
      if (toolsForRequest != null) 'tool_choice': 'auto',
    };
    return _RequestBody(body: body, toolsInjected: toolsForRequest != null);
  }

  // -------------------------------------------------------------------------
  // ③ 主入口：发送对话 + 多轮工具循环（流式与非流式统一）
  // -------------------------------------------------------------------------

  /// 发起一轮对话（含工具调用循环）。返回事件流。
  ///
  /// [initialMessages]：OpenAI 格式历史（不含本次用户消息）。方法内部会追加用户轮。
  /// [userText]：本次用户输入（纯文本）。
  /// [userImages]：可选的用户附带截图（仅路径引用，不内联 Base64）。
  /// [stream]：是否流式（默认 true）。
  Stream<ChatEvent> sendConversation({
    required String conversationId,
    required ConversationMcpSettings settings,
    required List<Map<String, dynamic>> initialMessages,
    required String userText,
    List<McpImageSnapshot> userImages = const [],
    bool stream = true,
  }) async* {
    // 深拷贝历史，避免污染调用方的消息列表
    final messages = List<Map<String, dynamic>>.from(initialMessages);

    // 追加用户轮（图片仅带路径提示，不塞大字节）
    final userContent = <Map<String, dynamic>>[
      {'type': 'text', 'text': userText},
    ];
    for (final img in userImages) {
      userContent.add({
        'type': 'text',
        'text': '[附截图，已保存到本地：${img.path}]',
      });
    }
    messages.add({
      'role': 'user',
      'content': userContent,
    });

    String fullText = '';
    int toolRound = 0;

    while (true) {
      final built = await _buildRequestBody(messages, settings);
      // ② 断言：启用 MCP 且确有工具时，tools + tool_choice 必须同时存在，
      //    禁止被空配置覆盖 / 静默剥离。
      if (settings.hasEffectiveServers && built.toolsInjected) {
        assert(built.body.containsKey('tools'),
            'MCP 已启用但请求体缺失 tools 字段（被空配置覆盖）');
        assert(built.body['tool_choice'] == 'auto',
            'MCP 已启用但 tool_choice 未正确传入 "auto"');
      } else if (settings.hasEffectiveServers && !built.toolsInjected) {
        yield const ChatWarning(
            'MCP 已在本会话启用，但未能从服务器获取任何工具，本次未注入 tools（请检查 掌心窗 MCP 是否在线）。');
      }

      final body = Map<String, dynamic>.from(built.body);
      body['stream'] = stream;

      Map<String, dynamic>? assistantMsg;
      try {
        if (stream) {
          await for (final ev in _sendStream(body)) {
            if (ev is ChatTextDelta) {
              fullText += ev.delta;
              yield ev;
            } else if (ev is _AssistantComplete) {
              assistantMsg = ev.message;
            }
          }
        } else {
          assistantMsg = await _sendNonStream(body);
        }
      } catch (e) {
        yield ChatError('请求 sta1n 失败：${e.toString()}');
        yield ChatDone(fullText, messages);
        return;
      }

      if (assistantMsg == null) {
        yield ChatError('sta1n 返回内容为空或解析失败。');
        yield ChatDone(fullText, messages);
        return;
      }

      final toolCalls = assistantMsg['tool_calls'] as List?;

      // 无工具调用 → 本轮结束
      if (toolCalls == null || toolCalls.isEmpty) {
        messages.add(assistantMsg);
        yield ChatDone(fullText, messages);
        return;
      }

      // ③ 有工具调用：先把助手消息（含 tool_calls）记入上下文
      messages.add(assistantMsg);

      if (++toolRound > maxToolRounds) {
        yield const ChatWarning('已达工具调用轮次上限，停止循环，直接返回当前文本。');
        yield ChatDone(fullText, messages);
        return;
      }

      // 逐个执行工具调用并回写 role:"tool"
      for (final tc in toolCalls) {
        final id = tc['id'] as String? ?? 'call_${toolRound}_${tc.hashCode}';
        final fn = (tc['function'] as Map?) ?? {};
        final name = (fn['name'] as String?) ?? '';
        final rawArgs = (fn['arguments'] as String?) ?? '{}';

        Map<String, dynamic> args = <String, dynamic>{};
        try {
          final decoded = jsonDecode(rawArgs.isEmpty ? '{}' : rawArgs);
          if (decoded is Map) {
            args = Map<String, dynamic>.from(decoded);
          }
        } catch (_) {
          // 参数解析失败：以空参数调用，并在结果中说明，避免上下文断裂
          args = <String, dynamic>{};
        }

        yield ChatToolCallStart(id, name, args);

        final result = await registry.callToolForConversation(
          conversationId,
          name,
          args,
        );

        final resultText = result.isError
            ? '工具执行失败：${result.errorText ?? result.text ?? '未知错误'}'
            : (result.text ?? '(工具无文本返回)');

        // ③ 关键：以 role:"tool" + tool_call_id 回写，匹配助手轮的 tool_calls
        final toolContent = StringBuffer()..write(resultText);
        for (final img in result.images) {
          toolContent.write('\n[工具截图已保存到本地：${img.path}]');
        }
        messages.add({
          'role': 'tool',
          'tool_call_id': id,
          'content': toolContent.toString(),
        });

        yield ChatToolCallDone(
            id, name, resultText, result.images, result.isError);
      }

      // 循环继续：把工具结果送回模型，让其生成最终回复
      continue;
    }
  }

  // -------------------------------------------------------------------------
  // ③ 流式请求（SSE）：累加文本与 tool_calls delta，回传 ChatEvent 流
  // -------------------------------------------------------------------------

  /// 发送流式请求，解析 SSE，依次 yield [ChatTextDelta]，最后 yield [_AssistantComplete]。
  Stream<ChatEvent> _sendStream(Map<String, dynamic> body) async* {
    final request = http.Request('POST', Uri.parse(_chatEndpoint))
      ..headers['Content-Type'] = 'application/json'
      ..headers['Authorization'] = 'Bearer $apiKey'
      ..headers['Accept'] = 'text/event-stream'
      ..body = jsonEncode(body);

    final response = await _client.send(request);
    if (response.statusCode != 200) {
      final err = await response.stream.bytesToString();
      throw Exception('HTTP ${response.statusCode}: $err');
    }

    final accContent = StringBuffer();
    // tool_calls 按 index 暂存：{index,id,function:{name,arguments}}
    final accToolCalls = <Map<String, dynamic>>[];

    await for (final line
        in response.stream.transform(utf8.decoder).transform(const LineSplitter())) {
      final trimmed = line.trim();
      if (trimmed.isEmpty) continue;
      if (!trimmed.startsWith('data:')) continue;
      final data = trimmed.substring(5).trim();
      if (data == '[DONE]') break;

      Map<String, dynamic>? chunk;
      try {
        chunk = jsonDecode(data) as Map<String, dynamic>;
      } catch (_) {
        continue; // 跳过心跳 / 非 JSON 行
      }

      final choices = chunk['choices'] as List?;
      if (choices == null || choices.isEmpty) continue;
      final delta = (choices.first as Map)['delta'] as Map? ?? {};

      // 文本增量
      if (delta['content'] is String) {
        final piece = delta['content'] as String;
        accContent.write(piece);
        yield ChatTextDelta(piece);
      }

      // tool_calls delta（可能分片到达）
      final tcs = delta['tool_calls'];
      if (tcs is List) {
        for (final tc in tcs) {
          _mergeToolCallDelta(accToolCalls, tc as Map);
        }
      }
    }

    // 组装完整 assistant 消息并终态回传
    yield _AssistantComplete(<String, dynamic>{
      'role': 'assistant',
      'content': accContent.toString().isNotEmpty
          ? accContent.toString()
          : null,
      if (accToolCalls.isNotEmpty)
        'tool_calls': accToolCalls
            .map((tc) => <String, dynamic>{
                  'id': tc['id'] ?? 'call_${tc['index']}',
                  'type': 'function',
                  'function': <String, dynamic>{
                    'name': (tc['function'] as Map)['name'],
                    'arguments': (tc['function'] as Map)['arguments'],
                  },
                })
            .toList(),
    });
  }

  // -------------------------------------------------------------------------
  // ③ 非流式请求：单条 JSON 直接解析
  // -------------------------------------------------------------------------

  Future<Map<String, dynamic>> _sendNonStream(
      Map<String, dynamic> body) async {
    final resp = await _client
        .post(
          Uri.parse(_chatEndpoint),
          headers: <String, String>{
            'Content-Type': 'application/json',
            'Authorization': 'Bearer $apiKey',
            'Accept': 'application/json',
          },
          body: jsonEncode(body),
        )
        .timeout(const Duration(seconds: 120));
    if (resp.statusCode != 200) {
      throw Exception('HTTP ${resp.statusCode}: ${resp.body}');
    }
    final decoded = jsonDecode(resp.body) as Map<String, dynamic>;
    final choices = decoded['choices'] as List?;
    if (choices == null || choices.isEmpty) {
      throw Exception('响应缺少 choices');
    }
    final message = (choices.first as Map)['message'] as Map?;
    if (message == null) throw Exception('响应缺少 message');
    return Map<String, dynamic>.from(message);
  }
}
