import json
import logging
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from langchain_core.tools import BaseTool, StructuredTool

from app.core.errors import ApiException
from app.runtime.builtin import (
    run_code_interpreter,
    run_file_parser,
    run_http_request,
    run_image_inspector,
    run_skill_script,
    run_web_search,
)
from app.runtime.mcp_loader import load_server_tools
from app.runtime.types import ToolSpec
from app.services.skill_package import MAX_SCRIPT_BYTES, read_member

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
    skills = resolved.get("skill") or []
    for skill in skills:
        # L2：一个 Skill 一个「加载指令」工具，description 复用 L1 触发面。
        specs.append(ToolSpec(
            key=f"skill:{skill['id']}",
            name=f"{skill['name']} / 加载指令",
            description=(
                f"加载「{skill['name']}」的完整 SKILL.md 指令。仅当任务符合以下描述时调用："
                f"{str(skill.get('description') or '加载 Skill 指令')}"
            )[:512],
            kind="skill",
            resource_id=str(skill["id"]),
        ))
    if skills:
        # L3：全局按需工具，避免随挂载数量线性膨胀。
        specs.append(ToolSpec(
            key="skill:*:read_resource",
            name="读取 Skill 资源",
            description="L3 按需读取指定 Skill 包内的参考文档，需提供 skillId 与包内相对路径 path，只返回该文件内容。",
            kind="skill_resource",
            resource_id="*",
        ))
        specs.append(ToolSpec(
            key="skill:*:run_script",
            name="执行 Skill 脚本",
            description="L3 在受限沙箱执行指定 Skill 包内的 Python 脚本，需提供 skillId 与 path，只返回执行输出，脚本源码不进入上下文。",
            kind="skill_script",
            resource_id="*",
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


def skill_instructions_runner(
    tool_name: str,
    instructions: str,
    limit_chars: int = 40000,
    *,
    version: str = "",
    resources: list[str] | None = None,
    scripts: list[str] | None = None,
):
    """L2：命中时加载 SKILL.md 正文，并告知 L3 有哪些资源可按需展开。

    正文以全量下发为前提，仅在超过 skillBodyMaxChars 时截断，并把截断事实
    写进 summary，避免「声称已加载全部、实际只收到一部分」。
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
            "level": 2,
            "version": version,
            "scriptExecuted": False,
            "note": "本次仅加载指令，未执行包内脚本",
            "truncated": truncated,
            "totalChars": total,
            "summary": summary,
            "instructions": text,
            "resources": (resources or [])[:40],
            "scripts": (scripts or [])[:40],
            "nextSteps": "需要更多细节时调用「读取 Skill 资源」；需要确定性操作时调用「执行 Skill 脚本」。",
        })

    return runner


def _find_skill(snapshot: dict, skill_id: str) -> dict | None:
    skills = (snapshot.get("resolvedMounts") or {}).get("skill") or []
    if skill_id:
        return next((item for item in skills if item["id"] == skill_id), None)
    return skills[0] if len(skills) == 1 else None


def _normalized_path(path: str) -> str:
    return str(path or "").strip().replace("\\", "/").lstrip("/")


def skill_resource_runner(snapshot: dict):
    """L3：只把被显式引用的包内文本文件读进上下文。"""

    async def runner(skillId: str = "", path: str = "") -> str:
        skill = _find_skill(snapshot, skillId)
        if skill is None:
            return dump({"status": "error", "tool": "read_skill_resource", "level": 3,
                         "summary": "未找到指定的 Skill，请从可用 Skills 清单中选择 skillId"})
        allowed = set(skill.get("resources") or [])
        member = _normalized_path(path)
        if not allowed:
            return dump({"status": "error", "tool": "read_skill_resource", "level": 3,
                         "summary": "该 Skill 包内没有可读取的参考文件"})
        if member not in allowed:
            return dump({"status": "error", "tool": "read_skill_resource", "level": 3,
                         "summary": "只允许读取该 Skill 包内的文本文件", "available": sorted(allowed)[:40]})
        package = Path(skill["packagePath"]) if skill.get("packagePath") else None
        try:
            text = read_member(package, member).decode("utf-8", "ignore")
        except ApiException as exc:
            return dump({"status": "error", "tool": "read_skill_resource", "level": 3, "summary": exc.message})
        return dump({
            "status": "loaded",
            "tool": "read_skill_resource",
            "level": 3,
            "skillId": skill["id"],
            "version": skill.get("version", ""),
            "path": member,
            "chars": len(text),
            "summary": f"已读取 {member}（{len(text)} 字符）",
            "content": text,
        })

    return runner


def skill_script_runner(snapshot: dict):
    """L3：脚本在受限进程执行，只有输出进上下文，源码始终不进。"""

    async def runner(skillId: str = "", path: str = "") -> str:
        skill = _find_skill(snapshot, skillId)
        if skill is None:
            return dump({"status": "error", "tool": "run_skill_script", "level": 3,
                         "summary": "未找到指定的 Skill，请从可用 Skills 清单中选择 skillId"})
        allowed = set(skill.get("scripts") or [])
        member = _normalized_path(path)
        if member not in allowed:
            return dump({"status": "error", "tool": "run_skill_script", "level": 3,
                         "summary": "只允许执行该 Skill 包内的 Python 脚本", "available": sorted(allowed)[:40]})
        if not skill.get("packagePath"):
            return dump({"status": "error", "tool": "run_skill_script", "level": 3,
                         "summary": "该 Skill 版本没有可用的本地包文件"})
        package = Path(skill["packagePath"])
        try:
            source = read_member(package, member, max_bytes=MAX_SCRIPT_BYTES).decode("utf-8", "ignore")
        except ApiException as exc:
            return dump({"status": "error", "tool": "run_skill_script", "level": 3, "summary": exc.message})
        members = sorted(set(skill.get("resources") or []) | allowed)
        outcome = await run_skill_script(source, package, members)
        return dump({
            "status": outcome["status"],
            "tool": "run_skill_script",
            "level": 3,
            "skillId": skill["id"],
            "version": skill.get("version", ""),
            "path": member,
            "scriptExecuted": True,
            "summary": outcome.get("summary", ""),
            "output": {
                "exitCode": outcome.get("exitCode"),
                "stdout": outcome.get("stdout", ""),
                "stderr": outcome.get("stderr", ""),
            },
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
            tool_name, spec.description,
            skill_instructions_runner(
                tool_name, instructions, limit_chars,
                version=str((skill or {}).get("version") or ""),
                resources=(skill or {}).get("resources") or [],
                scripts=(skill or {}).get("scripts") or [],
            ),
        )

    if spec.kind == "skill_resource":
        return make_tool(tool_name, spec.description, skill_resource_runner(snapshot))
    if spec.kind == "skill_script":
        return make_tool(tool_name, spec.description, skill_script_runner(snapshot))

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
