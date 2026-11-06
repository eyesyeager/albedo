"""导入公开免费的 MCP 服务与 Skill 包到 Albedo（幂等，可重复执行）。

数据来源（均为公开免费、无需自备密钥的资源）：
  - 远程 MCP：Microsoft Learn / DeepWiki / Context7 / Cloudflare Docs / GitMCP / grep.app / Parallel
  - 本地 stdio MCP：官方 filesystem、sequential-thinking，以及免密钥的 duckduckgo-mcp-server
  - Skill：github.com/anthropics/skills（Apache-2.0，跳过 docx/pdf/pptx/xlsx 等专有许可条目）

用法：
    cd backend
    uv run python scripts/import_free_resources.py                # 全部导入
    uv run python scripts/import_free_resources.py --refresh-skills
    uv run python scripts/import_free_resources.py --only mcps
    uv run python scripts/import_free_resources.py --only skills

鉴权只读取 backend/.env 的 DEMO_ACCESS_KEY，不打印任何密钥。
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import os
import shutil
import sys
import zipfile
from pathlib import Path

import httpx

BACKEND_ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.getenv("ALBEDO_BASE_URL", "http://localhost:8000").rstrip("/")
CACHE_DIR = BACKEND_ROOT / "var" / "import-cache"
SKILLS_ZIP = CACHE_DIR / "anthropics-skills-main.zip"
SKILLS_ZIP_URL = "https://codeload.github.com/anthropics/skills/zip/refs/heads/main"

PROJECT_ROOT = BACKEND_ROOT.parent


def env_value(key: str, default: str = "") -> str:
    value = os.getenv(key)
    if value:
        return value
    env_file = BACKEND_ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return default


def remote_mcps() -> list[dict]:
    return [
        {
            "name": "微软官方文档（Microsoft Learn）",
            "description": "微软官方远程 MCP，免密钥检索 Microsoft/Azure/PowerShell/.NET 官方文档与代码示例。",
            "transport": "streamable_http",
            "endpoint": "https://learn.microsoft.com/api/mcp",
        },
        {
            "name": "DeepWiki 代码库百科",
            "description": "免费的 GitHub 仓库文档问答服务，可读取公开仓库结构、维基与源码问答。",
            "transport": "streamable_http",
            "endpoint": "https://mcp.deepwiki.com/mcp",
        },
        {
            "name": "Context7 开发库文档",
            "description": "免费的第三方库最新版文档与代码示例检索，匿名调用有速率限制。",
            "transport": "streamable_http",
            "endpoint": "https://mcp.context7.com/mcp",
        },
        {
            "name": "Cloudflare 官方文档",
            "description": "Cloudflare 官方文档检索（Workers、Pages、R2、D1 等），免密钥。",
            "transport": "streamable_http",
            "endpoint": "https://docs.mcp.cloudflare.com/mcp",
        },
        {
            "name": "GitMCP 仓库文档检索",
            "description": "GitMCP 免密钥实例，按仓库提供文档与代码检索；换仓库只需改 endpoint 路径。",
            "transport": "streamable_http",
            "endpoint": "https://gitmcp.io/modelcontextprotocol/modelcontextprotocol",
        },
        {
            "name": "grep.app 代码搜索",
            "description": "跨 GitHub 公开仓库的真实代码搜索，免密钥。",
            "transport": "streamable_http",
            "endpoint": "https://mcp.grep.app",
        },
        {
            "name": "Parallel 联网搜索",
            "description": "免费的远程联网搜索与网页正文抓取（web_search / web_fetch），无需账号。",
            "transport": "streamable_http",
            "endpoint": "https://mcp.parallel.ai/mcp",
        },
    ]


def stdio_mcps() -> list[dict]:
    npx = shutil.which("npx") or "npx"
    uvx = shutil.which("uvx") or "uvx"
    return [
        {
            "name": "本地文件系统（工作区）",
            "description": f"官方 filesystem MCP，允许智能体读写 albedo 项目目录：{PROJECT_ROOT}",
            "transport": "stdio",
            "endpoint": f"{npx} -y @modelcontextprotocol/server-filesystem {PROJECT_ROOT}",
        },
        {
            "name": "结构化思考（sequential-thinking）",
            "description": "官方 sequential thinking MCP，把复杂问题拆成可回溯的思维步骤。",
            "transport": "stdio",
            "endpoint": f"{npx} -y @modelcontextprotocol/server-sequential-thinking",
        },
        {
            "name": "免密钥网页搜索（DuckDuckGo）",
            "description": "本地 stdio MCP，提供 DuckDuckGo 搜索与网页正文抽取，无需任何 API Key。",
            "transport": "stdio",
            "endpoint": f"{uvx} duckduckgo-mcp-server",
        },
    ]


# 中文展示名 -> anthropics/skills 目录名
SKILLS: dict[str, str] = {
    "Skill 创建指南（skill-creator）": "skill-creator",
    "MCP 服务构建指南（mcp-builder）": "mcp-builder",
    "Web 应用自动化测试（webapp-testing）": "webapp-testing",
    "文档协作撰写（doc-coauthoring）": "doc-coauthoring",
    "前端界面设计（frontend-design）": "frontend-design",
    "Web 制品构建（web-artifacts-builder）": "web-artifacts-builder",
    "视觉设计稿生成（canvas-design）": "canvas-design",
    "主题配色工厂（theme-factory）": "theme-factory",
    "算法艺术生成（algorithmic-art）": "algorithmic-art",
    "Anthropic 品牌规范（brand-guidelines）": "brand-guidelines",
    "内部沟通写作（internal-comms）": "internal-comms",
    "Slack GIF 创作（slack-gif-creator）": "slack-gif-creator",
}


def split_frontmatter(text: str) -> tuple[dict[str, str], str]:
    text = text.lstrip("\ufeff")
    lines = text.splitlines()
    if lines and lines[0].strip() == "---":
        for index in range(1, len(lines)):
            if lines[index].strip() == "---":
                meta: dict[str, str] = {}
                key = None
                for raw in lines[1:index]:
                    if not raw.strip():
                        continue
                    if raw[:1].isspace() and key:
                        meta[key] = f"{meta[key]} {raw.strip()}".strip()
                    elif ":" in raw:
                        key, value = raw.split(":", 1)
                        key = key.strip()
                        meta[key] = value.strip().lstrip(">|").strip()
                return meta, "\n".join(lines[index + 1 :]).strip()
    return {}, text.strip()


def download_skills_zip() -> Path:
    if SKILLS_ZIP.exists():
        return SKILLS_ZIP
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"下载 anthropics/skills：{SKILLS_ZIP_URL}")
    with httpx.Client(timeout=httpx.Timeout(300.0), follow_redirects=True, trust_env=False) as client:
        response = client.get(SKILLS_ZIP_URL)
        response.raise_for_status()
        SKILLS_ZIP.write_bytes(response.content)
    return SKILLS_ZIP


def build_skill_package(name: str, folder: str, zip_bytes: bytes) -> tuple[str, bytes]:
    prefix = "skills-main/skills/"
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as source, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            if not info.filename.startswith(prefix + folder + "/") or info.is_dir():
                continue
            relative = info.filename[len(prefix + folder + "/") :]
            if not relative or "__pycache__" in relative or relative.endswith(".DS_Store"):
                continue
            target.writestr(f"{folder}/{relative}", source.read(info))
    return f"{folder}.zip", out.getvalue()


def sanitize_bmp(text: str) -> str:
    """MySQL 列为 utf8mb3（3 字节）时无法写入 emoji 等非 BMP 字符，导入前剔除。"""
    return "".join(char for char in text if ord(char) <= 0xFFFF)


def skill_payload(name: str, folder: str, zip_bytes: bytes) -> dict | None:
    prefix = "skills-main/skills/"
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        try:
            raw = archive.read(f"{prefix}{folder}/SKILL.md")
        except KeyError:
            print(f"✗ {name}：包内缺少 SKILL.md，已跳过")
            return None
    meta, body = split_frontmatter(raw.decode("utf-8", "ignore"))
    description = meta.get("description", "").replace("\n", " ").strip()
    instructions = sanitize_bmp(
        f"> 来源：github.com/anthropics/skills → skills/{folder}（Apache-2.0）\n\n"
        f"{body or description}"
    )
    file_name, package = build_skill_package(name, folder, zip_bytes)
    return {"name": name, "instructions": instructions, "fileName": file_name, "package": package}


async def login(client: httpx.AsyncClient) -> None:
    access_key = env_value("DEMO_ACCESS_KEY")
    if not access_key:
        raise SystemExit("backend/.env 缺少 DEMO_ACCESS_KEY，无法登录管理面")
    response = await client.post("/api/v1/auth/login", json={"key": access_key})
    if response.status_code != 200:
        raise SystemExit(f"管理面登录失败：HTTP {response.status_code} {response.text[:200]}")


async def import_mcps(client: httpx.AsyncClient) -> tuple[int, int, int]:
    existing = {item["name"] for item in (await client.get("/api/v1/mcps")).json()}
    added = skipped = failed = 0
    for draft in remote_mcps() + stdio_mcps():
        payload = {**draft, "headers": "{}", "timeoutSeconds": 60, "tested": True}
        if draft["name"] in existing:
            print(f"- 跳过（已存在）MCP：{draft['name']}")
            skipped += 1
            continue
        try:
            tested = await client.post("/api/v1/mcps/test", json=payload)
            tested.raise_for_status()
            tools = tested.json().get("toolCount", 0)
            created = await client.post("/api/v1/mcps", json=payload)
            created.raise_for_status()
            print(f"✓ 接入 MCP：{draft['name']}（{tools} 个工具）")
            added += 1
        except httpx.HTTPError as exc:
            response = getattr(exc, "response", None)
            body = response.text[:200] if response is not None else str(exc)[:200]
            print(f"✗ 失败 MCP：{draft['name']} → {body}")
            failed += 1
    return added, skipped, failed


async def import_skills(client: httpx.AsyncClient, refresh: bool) -> tuple[int, int, int]:
    if refresh and SKILLS_ZIP.exists():
        SKILLS_ZIP.unlink()
    zip_bytes = download_skills_zip().read_bytes()
    existing = {item["name"] for item in (await client.get("/api/v1/skills")).json()}
    added = skipped = failed = 0
    for display_name, folder in SKILLS.items():
        if display_name in existing:
            print(f"- 跳过（已存在）Skill：{display_name}")
            skipped += 1
            continue
        payload = skill_payload(display_name, folder, zip_bytes)
        if payload is None:
            failed += 1
            continue
        try:
            response = await client.post(
                "/api/v1/skills",
                data={
                    "name": payload["name"],
                    "instructions": payload["instructions"],
                    "fileName": payload["fileName"],
                    "sizeBytes": str(len(payload["package"])),
                },
                files={"file": (payload["fileName"], payload["package"], "application/zip")},
                timeout=httpx.Timeout(120.0),
            )
            response.raise_for_status()
            print(f"✓ 上传 Skill：{display_name}（{len(payload['package']) // 1024} KB）")
            added += 1
        except httpx.HTTPError as exc:
            response = getattr(exc, "response", None)
            body = response.text[:200] if response is not None else str(exc)[:200]
            print(f"✗ 失败 Skill：{display_name} → {body}")
            failed += 1
    return added, skipped, failed


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", choices=["mcps", "skills"], help="只导入某一类资源")
    parser.add_argument("--refresh-skills", action="store_true", help="重新下载 anthropics/skills 压缩包")
    args = parser.parse_args()

    async with httpx.AsyncClient(base_url=BASE_URL, timeout=httpx.Timeout(90.0), follow_redirects=True) as client:
        await login(client)
        totals = [0, 0, 0]
        if args.only in (None, "mcps"):
            print("== MCP 服务 ==")
            for index, value in enumerate(await import_mcps(client)):
                totals[index] += value
        if args.only in (None, "skills"):
            print("== Skill 包 ==")
            for index, value in enumerate(await import_skills(client, args.refresh_skills)):
                totals[index] += value

    added, skipped, failed = totals
    print(f"\n完成：新增 {added}，跳过 {skipped}，失败 {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
