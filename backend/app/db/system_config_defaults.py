"""系统配置初始化值。

仅在首次初始化（或管理员显式恢复默认）时写入 `system_configs` 表。
运行期一律以数据库里的值为准，可通过 `GET/PATCH /api/v1/settings/configs` 读取与修改。
"""

from typing import Any

from app.core.config import get_settings


def default_system_config() -> dict[str, Any]:
    settings = get_settings()
    return {
        "platform": {
            "name": "Albedo",
            "version": "1.0.0",
            "domain": settings.platform_domain,
            "defaultWorkspaceName": "Albedo 工作台",
            "defaultLanguage": "zh-CN",
            "defaultTimezone": "Asia/Shanghai",
            "defaultRetentionDays": 90,
        },
        "options": {
            "agentThemes": [
                {"value": "violet", "label": "紫色"},
                {"value": "blue", "label": "蓝色"},
                {"value": "mint", "label": "薄荷绿"},
                {"value": "orange", "label": "橙色"},
            ],
            "modelTypes": [
                {"value": "chat", "label": "Chat"},
                {"value": "embedding", "label": "Embedding"},
                {"value": "rerank", "label": "Rerank"},
                {"value": "multimodal", "label": "多模态"},
            ],
            "publishableModelTypes": ["chat", "embedding"],
            "mcpTransports": [
                {"value": "sse", "label": "SSE"},
                {"value": "streamable_http", "label": "Streamable HTTP"},
                {"value": "stdio", "label": "stdio"},
            ],
            "logTypes": [
                {"value": "all", "label": "全部类型"},
                {"value": "session", "label": "会话"},
                {"value": "mcp", "label": "MCP"},
                {"value": "skill", "label": "Skill"},
                {"value": "tool", "label": "内置工具"},
                {"value": "model", "label": "模型"},
                {"value": "system", "label": "系统操作"},
            ],
            "logStatuses": [
                {"value": "all", "label": "全部状态"},
                {"value": "running", "label": "运行中"},
                {"value": "success", "label": "成功"},
                {"value": "failed", "label": "失败"},
                {"value": "timeout", "label": "超时"},
            ],
            "logSources": [
                {"value": "test_session", "label": "测试会话"},
                {"value": "standard_api", "label": "标准 API"},
                {"value": "agui", "label": "AGUI"},
                {"value": "model_api", "label": "模型发布 API"},
                {"value": "system", "label": "系统操作"},
            ],
            "languages": [
                {"value": "zh-CN", "label": "简体中文"},
                {"value": "en-US", "label": "English"},
            ],
            "timezones": [
                {"value": "Asia/Shanghai (UTC+8)", "label": "Asia/Shanghai (UTC+8)"},
                {"value": "UTC (UTC+0)", "label": "UTC (UTC+0)"},
                {"value": "America/Los_Angeles (UTC-8)", "label": "America/Los_Angeles (UTC-8)"},
            ],
            "retentionDays": [30, 60, 90, 180, 365],
            "logRanges": [
                {"value": "today", "label": "今天", "days": 1},
                {"value": "7d", "label": "最近 7 天", "days": 7},
                {"value": "30d", "label": "最近 30 天", "days": 30},
                {"value": "1y", "label": "最近 1 年", "days": 365},
            ],
            "historyRanges": [
                {"value": "7d", "label": "最近 7 天", "days": 7},
                {"value": "30d", "label": "最近 30 天", "days": 30},
                {"value": "1y", "label": "最近 1 年", "days": 365},
            ],
            "trendRanges": [
                {"value": "14", "label": "最近 14 天", "days": 14},
                {"value": "30", "label": "最近 30 天", "days": 30},
            ],
            "protocols": [
                {"value": "api", "label": "标准对话 API"},
                {"value": "agui", "label": "AGUI 协议接口"},
            ],
            "sampleLanguages": ["curl", "python", "javascript"],
            "exportFormats": ["csv", "json"],
        },
        "defaults": {
            "agent": {
                "description": "一个全新的智能体，等待进一步配置",
                "systemPrompt": "你是「{name}」，请专业、清晰地回答用户的问题。\n当前时间：{{currentTime}}",
                "theme": "violet",
                "modelParams": {"temperature": 0.7, "topP": 0.9, "maxOutputTokens": 4096},
                "advanced": {
                    "keepContext": True,
                    "autoRetry": True,
                    "outputThinkingSummary": False,
                    "maxToolCalls": 10,
                    "maxRunSeconds": 120,
                },
            },
            "model": {
                "type": "chat",
                "vendor": "",
                "baseUrl": "",
                "headers": "",
                "timeoutSeconds": 60,
                "enabled": True,
            },
            "mcp": {
                "transport": "streamable_http",
                "endpoint": "",
                "description": "自定义 MCP 服务",
                "headers": "",
                "env": "",
                "timeoutSeconds": 60,
                "qpm": 60,
                "maxConcurrency": 5,
            },
            "publication": {"temperature": 0.7, "maxOutputTokens": 4096, "qpm": 60},
            "skill": {"firstVersion": "v1.0.0", "firstNote": "首次上传并通过校验"},
        },
        "limits": {
            "agentNameMaxLength": 50,
            "promptMaxChars": 20000,
            "promptMaxVariables": 50,
            "temperatureMin": 0,
            "temperatureMax": 2,
            "topPMin": 0,
            "topPMax": 1,
            "maxOutputTokensCeiling": 32768,
            "maxToolCallsMin": 1,
            "maxToolCallsMax": 100,
            "maxRunSecondsMin": 3,
            "maxRunSecondsMax": 3600,
            "qpmMin": 1,
            "accessKeyMinLength": 12,
            "skillMaxBytes": 100 * 1024 * 1024,
            "skillMaxFiles": 500,
            "skillMaxUncompressedBytes": 200 * 1024 * 1024,
            "skillMaxDepth": 6,
            # 运行时向模型提供的 Skill 指令上限：过小会让长指令 Skill 半失效。
            "skillInstructionMaxChars": 40000,
            # L2 单次加载的 SKILL.md 正文上限（字符）。超出部分应下沉到 L3 资源文件按需读取。
            "skillBodyMaxChars": 8000,

            "mcpTimeoutMin": 3,
            "mcpTimeoutMax": 600,
            # 默认仍禁止内网/本地 MCP；管理员显式开启后才放行自建服务。
            "mcpAllowPrivateUrls": False,
            "exportExpireDays": 7,
            "downloadSignTtlSeconds": 300,
            "eventTtlSeconds": 600,
            "logPageSize": 8,
            "logSnapshotLimit": 50,
            "logSnapshotMaxItems": 200,
            "exportHistoryMaxItems": 20,
            "exportPollMaxAttempts": 20,
            "exportPollIntervalMs": 150,
            "searchMaxResults": 7,
            "activityMaxItems": 5,
            "chatTimeoutMs": 150000,
            "httpRequestTimeoutMs": 15000,
            "rateLimitWindowSeconds": 60,
            "defaultAgentQpm": 60,
            "retryMaxAttempts": 2,
        },
        "runtime": {
            "llm_model": settings.llm_model,
            "llm_timeout_seconds": settings.llm_timeout_seconds,
            "llm_temperature": settings.llm_temperature,
            "search_provider": settings.search_provider,
            "search_max_results": settings.search_max_results,
            "http_allow_hosts": settings.http_allow_hosts,
            "http_timeout_seconds": settings.http_timeout_seconds,
            "http_max_response_bytes": settings.http_max_response_bytes,
            "sandbox_enabled": settings.sandbox_enabled,
            "sandbox_timeout_seconds": settings.sandbox_timeout_seconds,
            "sandbox_memory_mb": settings.sandbox_memory_mb,
            "sandbox_max_output_chars": settings.sandbox_max_output_chars,
            "tool_upload_max_bytes": settings.tool_upload_max_bytes,
            "vision_model": settings.vision_model,
            "session_idle_hours": settings.session_idle_hours,
            "session_absolute_hours": settings.session_absolute_hours,
            "login_max_failures": settings.login_max_failures,
            "login_lock_seconds": settings.login_lock_seconds,
        },
        "builtinTools": [
            {"key": "web_search", "name": "联网搜索", "description": "检索公网信息并返回结构化结果", "version": "v1.0.0"},
            {"key": "code_interpreter", "name": "代码执行器", "description": "在隔离沙箱中执行 Python 代码", "version": "v1.0.0"},
            {"key": "file_parser", "name": "文件解析", "description": "解析文本内容并提取要点", "version": "v1.0.0"},
            {"key": "current_time", "name": "时间获取", "description": "返回工作区当前时间", "version": "v1.0.0"},
            {"key": "image_inspector", "name": "图片理解", "description": "调用多模态模型理解图片内容", "version": "v1.0.0"},
            {"key": "http_request", "name": "HTTP 请求", "description": "向公网地址发起受控 HTTP 请求", "version": "v1.0.0"},
        ],
    }
