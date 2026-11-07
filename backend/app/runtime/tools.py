import json
import logging
from datetime import datetime, timedelta, timezone

from langchain_core.tools import BaseTool, StructuredTool

from app.runtime.builtin import (
    run_code_interpreter,
    run_file_parser,
    run_http_request,
    run_image_inspector,
    run_web_search,
)
from app.runtime.mcp_loader import load_server_tools
from app.runtime.types import ToolSpec

import re

_SAFE_NAME = re.compile(r"[^A-Za-z0-9_-]")
LOCAL_TZ = timezone(timedelta(hours=8))
logger = logging.getLogger("albedo.runtime.tools")


def safe_tool_name(index: int, name: str) -> str:
    cleaned = _SAFE_NAME.sub("_", name).strip("_")[:56] or f"tool_{index}"
    return f"t{index}_{cleaned}"


def collect_tool_specs(snapshot: dict) -> list[ToolSpec]:
    resolved = snapshot.get("resolvedMounts") or {}
    specs: list[ToolSpec] = []
    for server in resolved.get("mcp") or []:
        for tool in server.get("tools") or []:
            if not tool.get("enabled", True):
                continue
            specs.append(ToolSpec(
                key=f"mcp:{server['id']}:{tool['key']}",
                name=f"{server['name']} / {tool['name']}",
                description=str(tool.get("description") or ""),
                kind="mcp",
                resource_id=str(server["id"]),
            ))
    for tool in resolved.get("tool") or []:
        if not tool.get("enabled", True):
            continue
        specs.append(ToolSpec(
            key=f"tool:{tool['id']}",
            name=f"{tool['name']} / {tool['key']}",
            description=str(tool.get("description") or ""),
            kind="tool",
            resource_id=str(tool["id"]),
        ))
    for skill in resolved.get("skill") or []:
        specs.append(ToolSpec(
            key=f"skill:{skill['id']}",
            name=f"{skill['name']} / 加载",
            description=str(skill.get("description") or "加载 Skill 指令"),
            kind="skill",
            resource_id=str(skill["id"]),
        ))
    return specs


def dump(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def make_tool(name: str, description: str, runner) -> BaseTool:
    return StructuredTool.from_function(coroutine=runner, name=name, description=description[:512])


def current_time_runner(tool_name: str):
    async def runner(query: str = "") -> str:
        moment = datetime.now(LOCAL_TZ)
        return dump({
            "status": "success",
            "tool": tool_name,
            "time": moment.isoformat(timespec="seconds"),
            "timezone": "Asia/Shanghai (UTC+8)",
            "summary": f"当前时间 {moment.strftime('%Y-%m-%d %H:%M:%S')}（UTC+8）",
        })

    return runner


def skill_instructions_runner(tool_name: str, instructions: str, limit_chars: int = 40000):
    """真实加载 Skill 包内声明的指令文本，供模型遵循；明确不宣称执行了包内脚本。

    指令以全量下发为前提，仅在超过系统配置 skillInstructionMaxChars 时截断，
    并把截断事实写进 summary，避免「声称已加载全部、实际只收到一部分」。
    """

    async def runner(query: str = "") -> str:
        if not instructions.strip():
            return dump({"status": "error", "tool": tool_name, "summary": "Skill 指令为空，无法加载"})
        total = len(instructions)
        limit = max(1000, int(limit_chars or total))
        truncated = total > limit
        text = instructions[:limit] if truncated else instructions
        summary = f"已加载 Skill 指令（共 {total} 字"
        summary += f"，本次提供前 {limit} 字，超出部分被截断）" if truncated else "，已全量提供）"
        return dump({
            "status": "loaded",
            "tool": tool_name,
            "scriptExecuted": False,
            "truncated": truncated,
            "totalChars": total,
            "summary": summary,
            "instructions": text,
        })

    return runner


def error_runner(tool_name: str, reason: str):
    """工具不可用时返回明确的失败状态，绝不伪装成执行成功。"""

    async def runner(query: str = "") -> str:
        logger.error("工具 %s 不可用：%s", tool_name, reason)
        return dump({"status": "error", "tool": tool_name, "summary": reason})

    return runner


async def resolve_tool(
    spec: ToolSpec,
    snapshot: dict,
    tool_name: str,
    preloaded: list[BaseTool] | None = None,
) -> BaseTool:
    resolved = snapshot.get("resolvedMounts") or {}
    if spec.kind == "mcp":
        server = next((item for item in resolved.get("mcp") or [] if item["id"] == spec.resource_id), None)
        if server is None:
            return make_tool(tool_name, spec.description, error_runner(tool_name, "MCP 资源已失效"))
        expected = spec.key.split(":", 2)[-1]
        loaded = preloaded if preloaded is not None else await load_server_tools(server)
        for tool in loaded:
            if tool.name == expected or tool.name.endswith(expected):
                return tool
        return make_tool(tool_name, spec.description, error_runner(tool_name, "MCP 连接失败或该工具不可用"))

    if spec.kind == "skill":
        skill = next((item for item in resolved.get("skill") or [] if item["id"] == spec.resource_id), None)
        instructions = str((skill or {}).get("instructions") or "")
        limit_chars = int((skill or {}).get("instructionLimit") or 40000)
        return make_tool(
            tool_name, spec.description, skill_instructions_runner(tool_name, instructions, limit_chars)
        )

    row = next((item for item in resolved.get("tool") or [] if item["id"] == spec.resource_id), None)
    key = str((row or {}).get("key") or "")
    builtin_runners = {
        "web_search": run_web_search,
        "code_interpreter": run_code_interpreter,
        "file_parser": run_file_parser,
        "image_inspector": run_image_inspector,
        "http_request": run_http_request,
    }
    if key == "current_time":
        return make_tool(tool_name, spec.description, current_time_runner(tool_name))
    runner = builtin_runners.get(key)
    if runner is not None:
        return make_tool(tool_name, spec.description, runner)
    return make_tool(tool_name, spec.description, error_runner(tool_name, f"未知内置工具 {key!r}，未注册任何实现"))


async def build_tools(specs: list[ToolSpec], snapshot: dict) -> tuple[list[BaseTool], dict[str, ToolSpec]]:
    tools: list[BaseTool] = []
    by_name: dict[str, ToolSpec] = {}
    server_tools: dict[str, list[BaseTool]] = {}
    for server in (snapshot.get("resolvedMounts") or {}).get("mcp") or []:
        if server.get("enabled", True):
            loaded = await load_server_tools(server)
            if not loaded:
                logger.error("MCP %s 工具发现失败或返回空列表，该服务挂载的工具将返回 error 状态", server.get("id"))
            server_tools[str(server["id"])] = loaded
    for index, spec in enumerate(specs):
        tool_name = safe_tool_name(index, spec.name)
        preloaded = server_tools.get(str(spec.resource_id)) if spec.kind == "mcp" else None
        tool = await resolve_tool(spec, snapshot, tool_name, preloaded)
        tools.append(tool)
        by_name[tool.name] = spec
    return tools, by_name
