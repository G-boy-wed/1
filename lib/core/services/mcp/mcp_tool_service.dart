/// 掌心窗 Flutter 客户端 —— MCP 工具调用服务（硬性重构版）
///
/// 本文件实现四条硬性要求：
///   ① 解除超时截断 / 超时延长：移除硬编码 20s 短超时，全局默认 60s，长任务默认 120s，
///      捕获 TimeoutException 并返回明确提示，禁止向上抛异常打断对话。
///   ② Schema 兼容清洗 + 动态裁剪：构建传给 LLM 的 tools 时逐条清洗（剥离无效字段、
///      修复 description 转义引号 / .describe() 污染、修复无效 default、补全 array.items.type），
///      且只暴露「当前启用服务器」的核心工具，禁止全量未清洗暴露。
///   ③ 并发动作串行队列锁：手机控制类动作（send_phone_command / tap_text / input_text /
///      run_sequence 等）走 FIFO 互斥锁，前一个完成返回后一个才执行。
///   ④ 截屏 Base64 内存优化与落盘：peek_screen / latest_screen 返回的高清 Base64 统一落盘到
///      临时缓存目录，消息实体内只保留本地路径引用 / 轻量元数据，绝不塞原始 Base64。
///
/// 依赖（pubspec.yaml）：
///   http: ^1.2.0
///   path_provider: ^2.1.0
///   path: ^1.8.0

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

// ===========================================================================
// ④ 截屏 / 图片 Base64 落盘存储模型
//    内存里只持有「文件路径 + 轻量元数据」，绝不持有几兆字节的原始 Base64 / Uint8List。
// ===========================================================================
class McpImageSnapshot {
  final String path; // 本地临时缓存文件路径
  final String mimeType; // image/jpeg | image/png
  final int? width;
  final int? height;
  final int? sizeBytes; // 落盘后的字节数
  final DateTime createdAt;
  final String? sourceTool; // 来源工具名，便于排查

  const McpImageSnapshot({
    required this.path,
    required this.mimeType,
    this.width,
    this.height,
    this.sizeBytes,
    required this.createdAt,
    this.sourceTool,
  });

  File get file => File(path);

  bool get exists => file.existsSync();

  /// 历史消息持久化只落这些：路径 + 元数据，绝不带 Base64。
  Map<String, dynamic> toHistoryJson() => <String, dynamic>{
        'kind': 'mcp_image_snapshot',
        'path': path,
        'mimeType': mimeType,
        if (width != null) 'width': width,
        if (height != null) 'height': height,
        if (sizeBytes != null) 'sizeBytes': sizeBytes,
        'createdAt': createdAt.toIso8601String(),
        if (sourceTool != null) 'sourceTool': sourceTool,
      };

  factory McpImageSnapshot.fromHistoryJson(Map<String, dynamic> m) =>
      McpImageSnapshot(
        path: m['path'] as String,
        mimeType: (m['mimeType'] as String?) ?? 'image/jpeg',
        width: m['width'] as int?,
        height: m['height'] as int?,
        sizeBytes: m['sizeBytes'] as int?,
        createdAt:
            DateTime.tryParse(m['createdAt'] as String? ?? '') ?? DateTime.now(),
        sourceTool: m['sourceTool'] as String?,
      );

  /// 把 Base64 文本解码后落盘，返回快照引用（不把大字节留在内存）。
  static Future<McpImageSnapshot> fromBase64(
    String base64, {
    required String mimeType,
    required Directory cacheDir,
    String? sourceTool,
    int? width,
    int? height,
  }) async {
    final bytes = _decodeBase64(base64);
    final ext = mimeType.contains('png') ? 'png' : 'jpg';
    final name =
        'snap_${DateTime.now().microsecondsSinceEpoch}.$ext';
    final file = File(p.join(cacheDir.path, name));
    await file.writeAsBytes(bytes, flush: true);
    return McpImageSnapshot(
      path: file.path,
      mimeType: mimeType,
      width: width,
      height: height,
      sizeBytes: bytes.lengthInBytes,
      createdAt: DateTime.now(),
      sourceTool: sourceTool,
    );
  }

  /// 删除落盘文件，释放空间。
  Future<void> dispose() async {
    try {
      if (exists) await file.delete();
    } catch (_) {
      /* 忽略清理失败 */
    }
  }

  static Uint8List _decodeBase64(String s) {
    var t = s.replaceAll(RegExp(r'\s+'), '');
    while (t.length % 4 != 0) t += '=';
    return base64Decode(t);
  }
}

/// 截屏缓存目录管理：落盘 + 配额（LRU 删最旧），防止临时目录被撑爆。
class McpImageCache {
  final Directory dir;
  final int maxFiles;

  McpImageCache(this.dir, {this.maxFiles = 60});

  static Future<McpImageCache> create({int maxFiles = 60}) async {
    final base = await getTemporaryDirectory();
    final dir = Directory(p.join(base.path, 'mcp_snapshots'));
    if (!await dir.exists()) await dir.create(recursive: true);
    return McpImageCache(dir, maxFiles: maxFiles);
  }

  /// Base64 -> 落盘 -> 返回路径引用。
  Future<McpImageSnapshot> saveBase64(
    String base64,
    String mimeType, {
    String? sourceTool,
    int? width,
    int? height,
  }) async {
    final snap = await McpImageSnapshot.fromBase64(
      base64,
      mimeType: mimeType,
      cacheDir: dir,
      sourceTool: sourceTool,
      width: width,
      height: height,
    );
    await _enforceQuota();
    return snap;
  }

  Future<void> _enforceQuota() async {
    try {
      final files = await dir
          .list()
          .where((e) => e is File)
          .cast<File>()
          .toList();
      files.sort((a, b) => a.statSync().modified.compareTo(b.statSync().modified));
      while (files.length > maxFiles) {
        final f = files.removeAt(0);
        try {
          await f.delete();
        } catch (_) {
          /* 忽略 */
        }
      }
    } catch (_) {
      /* 忽略 */
    }
  }

  Future<void> clear() async {
    try {
      if (await dir.exists()) await dir.delete(recursive: true);
      await dir.create(recursive: true);
    } catch (_) {
      /* 忽略 */
    }
  }
}

// ===========================================================================
// 工具调用结果（永不抛异常，统一走结果对象）
// ===========================================================================
class McpToolResult {
  final bool isError;
  final String? text; // 汇总文本（来自 text content）
  final List<McpImageSnapshot> images; // 落盘后的图片引用，不含 Base64
  final Map<String, dynamic>? raw; // 已剔除 image data 的精简 raw
  final String? errorText;

  const McpToolResult({
    this.isError = false,
    this.text,
    this.images = const [],
    this.raw,
    this.errorText,
  });

  factory McpToolResult.ok(
    String? text, {
    List<McpImageSnapshot> images = const [],
    Map<String, dynamic>? raw,
  }) =>
      McpToolResult(text: text, images: images, raw: raw);

  factory McpToolResult.error(String msg) =>
      McpToolResult(isError: true, errorText: msg, text: msg);

  factory McpToolResult.timeout(String tool, Duration d) => McpToolResult(
        isError: true,
        errorText:
            '工具「$tool」调用超过 ${d.inSeconds} 秒仍未返回，已自动结束等待，不会中断对话。可重试，或改用更快的指令。',
        text: '工具「$tool」调用超时（>${d.inSeconds}s），已安全返回，对话继续。',
      );
}

// ===========================================================================
// 工具定义 + ② Schema 清洗 / 动态裁剪
// ===========================================================================
class McpToolDefinition {
  final String name;
  final String description;
  final Map<String, dynamic> inputSchema;
  final Map<String, dynamic>? signals; // 服务端扩展元数据，不进 LLM schema

  const McpToolDefinition({
    required this.name,
    required this.description,
    required this.inputSchema,
    this.signals,
  });

  factory McpToolDefinition.fromJson(Map<String, dynamic> m) =>
      McpToolDefinition(
        name: m['name'] as String,
        description: (m['description'] as String?) ?? '',
        inputSchema: (m['inputSchema'] as Map<String, dynamic>?) ??
            <String, dynamic>{
              'type': 'object',
              'properties': <String, dynamic>{}
            },
        signals: m['signals'] as Map<String, dynamic>?,
      );
}

class McpSchemaSanitizer {
  static const Set<String> _validTypes = <String>{
    'string',
    'number',
    'integer',
    'boolean',
    'object',
    'array',
    'null'
  };

  /// 清洗 description：去掉 .describe(...) / z.object(...) 代码污染，
  /// 还原被错误转义的引号与换行。
  static String cleanDescription(String raw) {
    if (raw.isEmpty) return raw;
    var s = raw;
    // 去掉 describe( "..." ) 代码残留
    s = s.replaceAllMapped(RegExp(r'\.?\bdescribe\s*\('), (m) => '');
    // 去掉 z.object(...) / z.string() 等 zod 残留（非贪婪、跨行）
    s = s.replaceAll(RegExp(r'\bz\.\w+\([\s\S]*?\)'), '');
    // 还原转义：字面量 \" -> "、\\n -> 换行、\\t -> 制表符、\\' -> '
    s = s
        .replaceAll(r'\\"', '"')
        .replaceAll(r'\\n', '\n')
        .replaceAll(r'\\t', '\t')
        .replaceAll(r"\\'", "'");
    // 折叠多余空白
    s = s
        .replaceAll(RegExp(r'[ \t]+\n'), '\n')
        .replaceAll(RegExp(r'\n{2,}'), '\n')
        .trim();
    return s;
  }

  /// 递归清洗单个 schema 节点（JSON Schema 子集，兼容 Gemini function_declarations）。
  static Map<String, dynamic> sanitizeSchema(Map<String, dynamic> schema) {
    final out = <String, dynamic>{};

    // 1) 推理 type。联合类型数组（如 ["integer","null"]，Gemini 只接受单个
    //    type 字符串）取第一个非 null 元素，保持与 server/mcp_engine.py、
    //    cloudflare-worker worker.js 的 sanitize 行为一致。
    final type = schema['type'];
    final String inferredType;
    String? singleType;
    if (type is String) {
      singleType = type;
    } else if (type is List) {
      for (final t in type) {
        if (t is String && t != 'null' && t.isNotEmpty) {
          singleType = t;
          break;
        }
      }
    }
    if (singleType != null && _validTypes.contains(singleType)) {
      inferredType = singleType;
    } else if (schema.containsKey('properties') ||
        schema.containsKey('required')) {
      inferredType = 'object';
    } else if (schema.containsKey('items')) {
      inferredType = 'array';
    } else {
      inferredType = 'string';
    }
    out['type'] = inferredType;

    // 2) description（清洗）
    if (schema['description'] is String) {
      final d = cleanDescription(schema['description'] as String);
      if (d.isNotEmpty) out['description'] = d;
    }

    // 3) properties（递归）。object 类型必须保证带 properties 字段，
    //    否则 Gemini function_declarations 会按缺字段处理（参考此前 items.type 缺失 400）。
    if (schema['properties'] is Map) {
      final props = <String, dynamic>{};
      (schema['properties'] as Map).forEach((k, v) {
        if (v is Map) {
          props[k as String] = sanitizeSchema(v as Map<String, dynamic>);
        }
      });
      out['properties'] = props;
    } else if (inferredType == 'object') {
      out['properties'] = <String, dynamic>{};
    }

    // 4) required
    if (schema['required'] is List) {
      final props = out['properties'] as Map?;
      final req = (schema['required'] as List)
          .whereType<String>()
          .where((e) => props == null || props[e] != null)
          .toList();
      if (req.isNotEmpty) out['required'] = req;
    }

    // 5) items（array）—— 缺失 items 时兜底成 string，避免 Gemini 400 "missing field items.type"
    if (inferredType == 'array') {
      final items = schema['items'];
      if (items is Map && items.isNotEmpty) {
        out['items'] = sanitizeSchema(items as Map<String, dynamic>);
      } else {
        out['items'] = <String, dynamic>{'type': 'string'};
      }
    }

    // 6) enum
    if (schema['enum'] is List && (schema['enum'] as List).isNotEmpty) {
      out['enum'] = (schema['enum'] as List)
          .where((e) => e != null)
          .toList();
    }

    // 7) default（修复无效默认值：污染文本 / 类型不符则丢弃或强制转换）
    if (schema.containsKey('default')) {
      final dv = schema['default'];
      if (_validDefault(dv, inferredType)) {
        out['default'] = _coerceDefault(dv, inferredType);
      }
    }

    // 8) 常见数值 / 字符串约束
    for (final k in const [
      'minimum',
      'maximum',
      'exclusiveMinimum',
      'exclusiveMaximum',
      'minLength',
      'maxLength',
      'minItems',
      'maxItems'
    ]) {
      if (schema[k] is num) out[k] = schema[k];
    }
    if (schema['pattern'] is String) out['pattern'] = schema['pattern'];
    if (schema['format'] is String) out['format'] = schema['format'];

    return out;
  }

  static bool _validDefault(dynamic dv, String type) {
    if (dv == null) return false;
    if (dv is String) {
      // 污染检测：字符串默认值里混进代码
      if (dv.contains('.describe(') ||
          dv.contains('z.') ||
          dv.contains('function')) {
        return false;
      }
      if (type == 'string') return true;
      if (type == 'integer' || type == 'number') {
        return num.tryParse(dv) != null;
      }
      // object/array 不该有 string 默认
      if (type == 'object' || type == 'array') return false;
      return true;
    }
    if (type == 'integer') return dv is int;
    if (type == 'number') return dv is num;
    if (type == 'boolean') return dv is bool;
    if (type == 'array') return dv is List;
    if (type == 'object') return dv is Map;
    return true;
  }

  static dynamic _coerceDefault(dynamic dv, String type) {
    if (type == 'integer' && dv is String) return int.tryParse(dv);
    if (type == 'number' && dv is String) return num.tryParse(dv);
    return dv;
  }

  /// 把一个服务端工具清洗成 LLM 可用的 function_declaration 形态。
  static Map<String, dynamic> toFunctionDeclaration(McpToolDefinition tool) =>
      <String, dynamic>{
        'name': tool.name,
        'description': cleanDescription(tool.description),
        'parameters': sanitizeSchema(tool.inputSchema),
      };
}

enum McpPruneMode { all, coreOnly, custom }

/// 动态裁剪策略（硬性要求 #2）：仅暴露当前启用服务器的核心工具，禁止全量未清洗暴露。
class McpPruningPolicy {
  final McpPruneMode mode;
  final Set<String> coreToolNames; // coreOnly 白名单
  final Set<String> allowlist; // custom
  final Set<String> blocklist; // custom
  final int maxToolsForModel; // 安全上限，超出截断

  const McpPruningPolicy({
    this.mode = McpPruneMode.coreOnly,
    this.coreToolNames = _defaultCore,
    this.allowlist = const {},
    this.blocklist = const {},
    this.maxToolsForModel = 100,
  });

  List<McpToolDefinition> prune(List<McpToolDefinition> tools) {
    Iterable<McpToolDefinition> it = tools;
    switch (mode) {
      case McpPruneMode.all:
        break;
      case McpPruneMode.coreOnly:
        it = it.where((t) => coreToolNames.contains(t.name));
        break;
      case McpPruneMode.custom:
        it = it.where((t) =>
            (allowlist.isEmpty || allowlist.contains(t.name)) &&
            !blocklist.contains(t.name));
        break;
    }
    var list = it.toList();
    if (list.length > maxToolsForModel) {
      list = list.sublist(0, maxToolsForModel);
    }
    return list;
  }

  /// 默认核心工具白名单（覆盖手机控制 / 截屏 OCR / 状态读取 / 记忆 / 专注门禁 /
  /// 训诫关怀 / 天气日历课表日记等高频能力，剔除冗长重复的纯管理类工具）。
  static const Set<String> _defaultCore = <String>{
    // —— 手机控制 / 截屏 / OCR ——
    'send_phone_command', 'get_phone_state', 'get_screen_nodes', 'tap_text',
    'input_text', 'peek_screen', 'latest_screen', 'screen_ocr', 'analyze_image',
    'open_app', 'phone_home', 'phone_back', 'phone_recents', 'phone_screen_off',
    'run_sequence', 'run_preset', 'send_notification', 'show_center_alert',
    'force_back_to_chat',
    // —— 状态读取 ——
    'get_life_state', 'get_senses_state', 'get_guidian_state',
    'get_usage_snapshot', 'get_emotion_snapshot', 'get_companion_emotion',
    'get_care_policy', 'get_care_history', 'get_visit_stats', 'get_last_visit',
    'get_visit_history', 'get_activity_events', 'get_companion_actions',
    'get_live_media_context', 'get_guardian_calendar', 'get_tour_schedule',
    'get_current_time', 'linjian_status', 'get_window_whisper',
    // —— 共同记忆 ——
    'write_shared_memory', 'search_shared_memory', 'list_shared_memory',
    // —— 专注 / 门禁 / 锁 / 休息 ——
    'get_focus_status', 'start_focus_mode', 'end_focus_mode', 'set_focus_plan',
    'reply_focus_request', 'approve_focus_unlock', 'deny_focus_unlock',
    'request_focus_unlock', 'get_screen_break_state', 'get_lock_state',
    'screen_break_app', 'end_screen_break', 'lock_app', 'unlock_app',
    'start_screen_break', 'stop_screen_break', 'extend_screen_break',
    'get_screen_break_release_requests',
    // —— 训诫 / 关怀 / 引导 ——
    'trigger_discipline', 'get_discipline_state', 'set_discipline_mode',
    'set_care_policy', 'record_care_event', 'record_visit', 'active_care_check',
    'care_action', 'trigger_guidian', 'mark_guidian_returned',
    'set_guidian_config', 'companion_media_interact', 'write_emotion_tag',
    'write_companion_emotion', 'set_companion_emotion', 'set_window_whisper',
    'set_tour_schedule', 'set_alarm', 'save_known_app', 'list_known_apps',
    // —— 天气 / 日历 / 课表 / 日记 ——
    'get_weather_state', 'send_weather_notification',
    'add_guardian_calendar_event', 'list_guardian_days', 'add_guardian_day',
    'update_guardian_day', 'delete_guardian_day', 'import_timetable',
    'create_diary_book', 'list_diary_books', 'write_diary_entry',
    'list_diary_entries', 'read_diary_entry', 'search_diary_entries',
    'update_diary_entry', 'delete_diary_entry', 'add_activity_event',
  };
}

// ===========================================================================
// MCP JSON-RPC 异常
// ===========================================================================
class McpRpcException implements Exception {
  final String message;
  McpRpcException(this.message);
  @override
  String toString() => 'McpRpcException: $message';
}

// ===========================================================================
// 核心服务：McpToolService
// ===========================================================================
class McpToolService {
  final String baseUrl; // 例如 http://192.168.x.x:8513/mcp
  final String? authToken; // 可选 X-Auth-Token
  final McpPruningPolicy pruning;
  final McpImageCache imageCache;
  final http.Client _client;

  // —— ① 超时配置（移除硬编码 20s 截断）——
  final Duration defaultTimeout; // 全局默认，≥60s
  final Duration longRunningTimeout; // 长任务默认，≥120s

  // —— ③ 手机控制类动作的串行 FIFO 互斥锁 ——
  final Set<String> _phoneControlTools = const {
    'send_phone_command', 'tap_text', 'input_text', 'run_sequence',
    'run_preset', 'phone_home', 'phone_back', 'phone_recents',
    'phone_screen_off', 'open_app', 'screen_break_app', 'lock_app',
    'unlock_app', 'get_screen_nodes', 'peek_screen',
  };
  Future<void>? _mutexTail = Future.value(null);
  final List<String> _serialLog = <String>[];

  int _rpcId = 1;

  McpToolService({
    required this.baseUrl,
    this.authToken,
    this.pruning = const McpPruningPolicy(),
    required this.imageCache,
    this.defaultTimeout = const Duration(seconds: 60),
    this.longRunningTimeout = const Duration(seconds: 120),
    http.Client? client,
  }) : _client = client ?? http.Client();

  void dispose() => _client.close();

  Duration _timeoutFor(String tool) =>
      _phoneControlTools.contains(tool) || _longRunning.contains(tool)
          ? longRunningTimeout
          : defaultTimeout;

  // 长任务工具（截屏 / OCR / 连招 / 输入 等），默认 120s。
  static const Set<String> _longRunning = <String>{
    'peek_screen', 'latest_screen', 'screen_ocr', 'analyze_image',
    'run_sequence', 'run_preset', 'tap_text', 'input_text', 'get_screen_nodes',
    'import_timetable', 'draft_xhs_comment', 'xhs_comment',
    'send_visible_comment_after_confirmation', 'get_live_media_context',
    'companion_media_interact', 'trigger_discipline', 'set_focus_plan',
    'add_guardian_calendar_event',
  };

  // ---------------- MCP 协议层（Streamable HTTP，无状态）----------------

  Future<Map<String, dynamic>> _rpc(
    String method,
    Map<String, dynamic> params, {
    Duration? timeout,
  }) async {
    final id = _rpcId++;
    final body = jsonEncode(<String, dynamic>{
      'jsonrpc': '2.0',
      'id': id,
      'method': method,
      'params': params,
    });
    final t = timeout ?? defaultTimeout;
    final resp = await _client
        .post(
          Uri.parse(baseUrl),
          headers: <String, String>{
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'MCP-Protocol-Version': '2025-06-18',
            if (authToken != null) 'X-Auth-Token': authToken!,
          },
          body: body,
        )
        .timeout(t,
            onTimeout: () => throw TimeoutException('rpc timeout', t));
    if (resp.statusCode != 200) {
      throw McpRpcException('HTTP ${resp.statusCode}: ${resp.body}');
    }
    final decoded = jsonDecode(resp.body);
    // 批处理可能返回数组，这里统一取首个 / 唯一对象
    final obj =
        decoded is List ? (decoded.isNotEmpty ? decoded.first : null) : decoded;
    if (obj == null) throw McpRpcException('empty mcp response');
    if (obj is! Map) throw McpRpcException('unexpected mcp response type');
    if (obj['error'] != null) {
      throw McpRpcException(jsonEncode(obj['error']));
    }
    return (obj['result'] as Map<String, dynamic>?) ?? <String, dynamic>{};
  }

  Future<void> initialize() async {
    await _rpc('initialize', <String, dynamic>{
      'protocolVersion': '2025-06-18',
      'capabilities': <String, dynamic>{'tools': <String, dynamic>{}},
      'clientInfo': <String, dynamic>{
        'name': 'zhangxinchuang-flutter',
        'version': '0.4.1'
      },
    });
    // 通知 initialized（无 id，服务端返回 202 忽略）
    try {
      await _client.post(
        Uri.parse(baseUrl),
        headers: <String, String>{
          'Content-Type': 'application/json',
          'MCP-Protocol-Version': '2025-06-18'
        },
        body: jsonEncode(<String, dynamic>{
          'jsonrpc': '2.0',
          'method': 'notifications/initialized'
        }),
      );
    } catch (_) {
      /* 忽略 */
    }
  }

  Future<List<McpToolDefinition>> listTools() async {
    final res = await _rpc('tools/list', <String, dynamic>{});
    final list = (res['tools'] as List?) ?? <dynamic>[];
    return <McpToolDefinition>[
      for (final t in list)
        McpToolDefinition.fromJson(t as Map<String, dynamic>)
    ];
  }

  // ---------------- ③ 串行 FIFO 互斥锁 ----------------

  /// 手机控制动作严格 FIFO：前一个完成返回后，下一个才执行，禁止并发踩踏。
  Future<T> _serial<T>(Future<T> Function() task) {
    final prev = _mutexTail!;
    final completer = Completer<T>();
    final next = Future<T>(() async {
      await prev;
      return await task();
    });
    next.then(completer.complete, onError: completer.completeError);
    // 无论成功失败都保持队列存活，避免单点异常卡死后续动作
    _mutexTail = next.then((_) => null, onError: (_) => null);
    return completer.future;
  }

  bool _isPhoneControl(String name) => _phoneControlTools.contains(name);

  // ---------------- ① 对外主入口：永不抛异常打断对话 ----------------

  Future<McpToolResult> callTool(
    String name, [
    Map<String, dynamic> args = const {},
    Duration? timeout,
  ]) async {
    try {
      final effective = timeout ?? _timeoutFor(name);
      Future<McpToolResult> runner() => _invokeMcp(name, args, effective);
      if (_isPhoneControl(name)) {
        _serialLog.add(name);
        return await _serial(runner);
      }
      return await runner();
    } on TimeoutException {
      // 双保险：_invokeMcp 内已吞掉，这里再兜一层
      return McpToolResult.timeout(name, timeout ?? _timeoutFor(name));
    } catch (e) {
      return McpToolResult.error('工具「$name」调用异常：${e.toString()}');
    }
  }

  Future<McpToolResult> _invokeMcp(
    String name,
    Map<String, dynamic> args,
    Duration timeout,
  ) async {
    try {
      final result = await _rpc(
        'tools/call',
        <String, dynamic>{'name': name, 'arguments': args},
        timeout: timeout,
      );
      return await _parseToolResult(name, result);
    } on TimeoutException {
      // ① 超时返回明确提示，禁止向上抛。
      return McpToolResult.timeout(name, timeout);
    } on McpRpcException catch (e) {
      return McpToolResult.error('工具「$name」RPC 失败：${e.message}');
    } catch (e) {
      return McpToolResult.error('工具「$name」调用失败：${e.toString()}');
    }
  }

  Future<McpToolResult> _parseToolResult(
    String name,
    Map<String, dynamic> result,
  ) async {
    final isErr = result['isError'] == true;
    final content = (result['content'] as List?) ?? <dynamic>[];
    final texts = <String>[];
    final images = <McpImageSnapshot>[];
    for (final c in content) {
      if (c is! Map) continue;
      final type = c['type'];
      if (type == 'text' && c['text'] is String) {
        texts.add(c['text'] as String);
      } else if ((type == 'image' ||
              type == 'resource' ||
              type == 'image_url') &&
          c['data'] is String) {
        // ④ 把 Base64 落盘，消息只保留路径引用
        final mime = (c['mimeType'] as String?) ?? 'image/jpeg';
        final snap = await imageCache.saveBase64(
          c['data'] as String,
          mime,
          sourceTool: name,
        );
        images.add(snap);
      } else if (type == 'resource' && c['resource'] is Map) {
        final r = c['resource'] as Map;
        final data = r['data'] ?? r['blob'];
        final mime = (r['mimeType'] as String?) ?? 'image/jpeg';
        if (data is String) {
          final snap = await imageCache.saveBase64(data, mime,
              sourceTool: name);
          images.add(snap);
        }
      }
    }
    final text = texts.isNotEmpty ? texts.join('\n') : null;
    if (isErr) {
      return McpToolResult.error(text ?? '工具「$name」返回错误结果。');
    }
    return McpToolResult.ok(text,
        images: images, raw: _stripBase64(result));
  }

  /// 给业务层一个不含图片大文本的精简 raw（可选使用）。
  Map<String, dynamic> _stripBase64(Map<String, dynamic> result) {
    try {
      final clone = jsonDecode(jsonEncode(result)) as Map<String, dynamic>;
      final c = clone['content'] as List?;
      if (c != null) {
        for (final e in c) {
          if (e is Map && e['data'] is String) {
            e['data'] = '<base64 omitted, see images>';
          }
        }
      }
      return clone;
    } catch (_) {
      return result;
    }
  }

  // ---------------- ② 构建传给 LLM 的 tools 列表 ----------------

  /// 先按当前服务器裁剪，再逐条清洗，禁止全量未清洗暴露。
  Future<List<Map<String, dynamic>>> buildFunctionDeclarations() async {
    final all = await listTools();
    final pruned = pruning.prune(all);
    return <Map<String, dynamic>>[
      for (final t in pruned)
        McpSchemaSanitizer.toFunctionDeclaration(t),
    ];
  }

  /// 调试用：返回裁剪后的工具名。
  Future<List<String>> prunedToolNames() async {
    final all = await listTools();
    return pruning.prune(all).map((t) => t.name).toList();
  }
}
