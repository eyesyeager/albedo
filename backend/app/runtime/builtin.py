"""内置工具的真实实现：联网搜索、代码执行、文件解析、图片理解、HTTP 请求。

每个 runner 都返回 JSON 字符串，`status` 取值 success / degraded / error，
运行时会把整段结果回写给模型，同时保留可直接审计的 summary。
"""

import ast
import asyncio
import base64
import json
import mimetypes
import os
import re
import signal
import sys
import tempfile
from html import unescape
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

import httpx

from app.core.config import get_settings
from app.core.errors import ApiException
from app.core.network import assert_public_url
from app.services.skill_package import extract_members

BING_ENDPOINT = "https://www.bing.com/search"
DUCKDUCKGO_ENDPOINT = "https://html.duckduckgo.com/html/"
BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
TEXT_SUFFIXES = {
    ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".jsonl", ".log", ".yml", ".yaml",
    ".py", ".js", ".ts", ".tsx", ".html", ".htm", ".xml", ".sql", ".ini", ".toml", ".conf", ".sh",
}
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
# 白名单：只允许无文件系统 / 进程 / 网络副作用的计算类标准库模块。
# 之前的黑名单不含 os、open、__globals__ 等逃逸路径，任何 Agent Token 持有者都能绕过，
# 因此改为白名单；配合「默认关闭」与进程降权形成多层防护。
ALLOWED_MODULES = frozenset({
    "math", "cmath", "statistics", "decimal", "fractions", "numbers",
    "json", "re", "string", "textwrap", "unicodedata",
    "itertools", "functools", "operator", "collections", "heapq", "bisect",
    "datetime", "calendar", "time", "random", "enum", "dataclasses", "typing",
    "copy", "pprint", "uuid", "base64", "binascii", "hashlib",
})
# 危险内置名 / 模块名 / 反射入口：作为名称、调用目标或属性出现一律拒绝。
BLOCKED_NAMES = frozenset({
    "eval", "exec", "compile", "__import__", "execfile", "input", "breakpoint",
    "open", "globals", "locals", "vars", "dir", "getattr", "setattr", "delattr",
    "hasattr", "help", "memoryview", "super", "type", "object",
    "os", "sys", "subprocess", "socket", "socketserver", "ctypes", "multiprocessing",
    "threading", "signal", "pty", "fcntl", "resource", "shutil", "pathlib",
    "tempfile", "io", "builtins", "importlib", "pickle", "marshal", "posix", "nt",
    "site", "code", "codeop", "runpy", "pkgutil", "zipimport", "webbrowser",
    "platform", "urllib", "urllib3", "requests", "httpx", "http", "ftplib",
    "telnetlib", "smtplib", "codecs", "encodings",
})
# 不以 _ 开头但可用于逃逸的属性名。
UNSAFE_ATTRIBUTES = frozenset({
    "mro", "subclasses", "gi_frame", "cr_frame", "f_globals", "f_locals", "func_globals",
})
MAX_PARSE_CHARS = 20_000
PREVIEW_CHARS = 2_000

_RESULT_LINK = re.compile(r'<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', re.S)
_RESULT_SNIPPET = re.compile(r'<a[^>]*class="result__snippet"[^>]*>(.*?)</a>', re.S)
_BING_ITEM = re.compile(r'<li class="b_algo".*?</li>', re.S)
_BING_TITLE = re.compile(r'<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', re.S)
_BING_SNIPPET = re.compile(r'<p[^>]*>(.*?)</p>', re.S)
_TAG = re.compile(r"<[^>]+>")


def dump(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def fail(tool: str, summary: str, **extra: object) -> str:
    return dump({"status": "error", "tool": tool, "summary": summary, **extra})


def degraded(tool: str, summary: str, **extra: object) -> str:
    return dump({"status": "degraded", "tool": tool, "summary": summary, **extra})


def resolve_upload(name: str) -> tuple[Path | None, str]:
    """把参数解析为 var/uploads 下的真实文件，阻断路径穿越与超限文件。"""
    settings = get_settings()
    root = Path(settings.upload_dir).resolve()
    raw = Path(str(name or "").strip()).name
    if not raw or raw in {".", ".."}:
        return None, "缺少文件名参数，请传入工作区文件目录中的文件名"
    candidate = (root / raw).resolve()
    if candidate.parent != root:
        return None, "文件路径越界，已阻断"
    if not candidate.is_file():
        return None, f"未找到文件 {raw}，请先放入工作区文件目录 var/uploads"
    if candidate.stat().st_size > settings.tool_upload_max_bytes:
        limit_mb = settings.tool_upload_max_bytes / (1024 * 1024)
        return None, f"文件超过 {limit_mb:.0f} MB 解析上限"
    return candidate, ""


# ---------------------------------------------------------------- 联网搜索


def _clean_text(fragment: str) -> str:
    return unescape(_TAG.sub("", fragment or "")).strip()


def _unwrap_redirect(href: str) -> str:
    target = href.strip()
    if target.startswith("//"):
        target = f"https:{target}"
    parsed = urlparse(target)
    if parsed.netloc.endswith("duckduckgo.com") and parsed.path.startswith("/l/"):
        found = parse_qs(parsed.query).get("uddg")
        if found:
            return unquote(found[0])
    return target


def parse_bing(html_text: str, limit: int) -> list[dict[str, str]]:
    results: list[dict[str, str]] = []
    for item in _BING_ITEM.findall(html_text or "")[:limit]:
        title = _BING_TITLE.search(item)
        if title is None:
            continue
        snippet = _BING_SNIPPET.search(item)
        results.append({
            "title": _clean_text(title.group(2)),
            "url": unescape(title.group(1)),
            "snippet": _clean_text(snippet.group(1)) if snippet else "",
        })
    return results


def parse_duckduckgo(html_text: str, limit: int) -> list[dict[str, str]]:
    links = _RESULT_LINK.findall(html_text or "")
    snippets = _RESULT_SNIPPET.findall(html_text or "")
    results: list[dict[str, str]] = []
    for index, (href, title) in enumerate(links[:limit]):
        results.append({
            "title": _clean_text(title),
            "url": _unwrap_redirect(href),
            "snippet": _clean_text(snippets[index]) if index < len(snippets) else "",
        })
    return results


async def run_web_search(query: str, max_results: int = 5) -> str:
    settings = get_settings()
    provider = (settings.search_provider or "bing").strip().lower()
    keyword = (query or "").strip()
    if not keyword:
        return fail("web_search", "缺少检索关键词 query")
    if provider in {"off", "none", "disabled"}:
        return degraded("web_search", "搜索服务已关闭（SEARCH_PROVIDER=off）")

    limit = max(1, min(int(max_results or settings.search_max_results), 20))
    timeout = float(settings.http_timeout_seconds)
    results: list[dict[str, str]] = []
    try:
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
            if provider == "bing":
                response = await client.get(
                    BING_ENDPOINT,
                    params={"q": keyword, "count": limit},
                    headers={"User-Agent": BROWSER_UA, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"},
                )
                response.raise_for_status()
                results = parse_bing(response.text, limit)
            elif provider == "duckduckgo":
                response = await client.get(
                    DUCKDUCKGO_ENDPOINT,
                    params={"q": keyword},
                    headers={"User-Agent": BROWSER_UA, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"},
                )
                response.raise_for_status()
                results = parse_duckduckgo(response.text, limit)
            elif provider == "tavily":
                if not settings.search_api_key:
                    return degraded("web_search", "SEARCH_PROVIDER=tavily 但未配置 SEARCH_API_KEY")
                response = await client.post(
                    "https://api.tavily.com/search",
                    json={"api_key": settings.search_api_key, "query": keyword, "max_results": limit, "include_answer": False},
                )
                response.raise_for_status()
                for item in (response.json().get("results") or [])[:limit]:
                    results.append({
                        "title": str(item.get("title") or ""),
                        "url": str(item.get("url") or ""),
                        "snippet": str(item.get("content") or "")[:400],
                    })
            elif provider == "serper":
                if not settings.search_api_key:
                    return degraded("web_search", "SEARCH_PROVIDER=serper 但未配置 SEARCH_API_KEY")
                response = await client.post(
                    "https://google.serper.dev/search",
                    headers={"X-API-KEY": settings.search_api_key, "Content-Type": "application/json"},
                    json={"q": keyword, "num": limit},
                )
                response.raise_for_status()
                for item in (response.json().get("organic") or [])[:limit]:
                    results.append({
                        "title": str(item.get("title") or ""),
                        "url": str(item.get("link") or ""),
                        "snippet": str(item.get("snippet") or "")[:400],
                    })
            else:
                return degraded("web_search", f"未知的搜索服务 {provider}，可选 bing/duckduckgo/tavily/serper")
    except Exception as exc:  # noqa: BLE001
        return fail("web_search", f"检索失败：{type(exc).__name__}: {str(exc)[:160]}", provider=provider, query=keyword)
    if not results:
        return degraded("web_search", "检索服务返回 0 条结果，可换关键词重试", provider=provider, query=keyword)
    return dump({
        "status": "success",
        "tool": "web_search",
        "provider": provider,
        "query": keyword,
        "resultCount": len(results),
        "results": results,
        "summary": f"检索「{keyword}」命中 {len(results)} 条结果",
    })


# ---------------------------------------------------------------- 代码执行


def check_code(code: str) -> str:
    """白名单静态检查：导入、名称、属性与字面量。返回空串表示通过。

    注意：这只是纵深防御，不是 OS 级隔离。沙箱默认关闭，启用时必须配合
    容器 / seccomp / 只读文件系统，否则不应在不可信输入下开启。
    """
    try:
        tree = ast.parse(code)
    except SyntaxError as exc:
        return f"代码语法错误（第 {exc.lineno} 行）：{exc.msg}"
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".")[0]
                if root not in ALLOWED_MODULES:
                    return f"禁止导入模块 {root}（代码执行沙箱仅允许计算类标准库）"
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                return "禁止相对导入（代码执行沙箱仅允许计算类标准库）"
            root = (node.module or "").split(".")[0]
            if root not in ALLOWED_MODULES:
                return f"禁止导入模块 {root}（代码执行沙箱仅允许计算类标准库）"
        elif isinstance(node, ast.Name):
            if node.id in BLOCKED_NAMES:
                return f"禁止使用 {node.id}（代码执行沙箱不允许动态执行与系统访问）"
        elif isinstance(node, ast.Attribute):
            attr = node.attr
            if attr.startswith("_") or attr in UNSAFE_ATTRIBUTES or attr in BLOCKED_NAMES:
                return f"禁止访问属性 {attr}（代码执行沙箱不允许反射与属性逃逸）"
        elif isinstance(node, ast.Call):
            func = node.func
            name = func.id if isinstance(func, ast.Name) else (func.attr if isinstance(func, ast.Attribute) else "")
            if name in BLOCKED_NAMES:
                return f"禁止调用 {name}（代码执行沙箱不允许动态执行与系统访问）"
        elif isinstance(node, ast.Constant) and isinstance(node.value, str) and "__" in node.value:
            return "禁止包含双下划线字面量（可绕过属性检查的格式化字符串逃逸）"
    return ""


def _limit_setter(cpu_seconds: int, memory_mb: int):
    def apply() -> None:
        # 以 root 运行时降权到 nobody，缩小对宿主文件系统的可访问范围。
        try:
            if hasattr(os, "geteuid") and os.geteuid() == 0:
                os.setgroups([])
                os.setgid(65534)
                os.setuid(65534)
        except (OSError, AttributeError):
            pass
        try:
            import resource
        except ImportError:  # 非 POSIX 平台
            return
        limits: list[tuple[str, tuple[int, int]]] = [
            ("RLIMIT_CPU", (cpu_seconds + 1, cpu_seconds + 2)),
            ("RLIMIT_FSIZE", (8 * 1024 * 1024, 8 * 1024 * 1024)),
            ("RLIMIT_NPROC", (1, 1)),
            ("RLIMIT_NOFILE", (64, 64)),
        ]
        if sys.platform.startswith("linux"):
            limits.append(("RLIMIT_AS", (memory_mb * 1024 * 1024, memory_mb * 1024 * 1024)))
        for name, value in limits:
            target = getattr(resource, name, None)
            if target is None:
                continue
            try:
                resource.setrlimit(target, value)
            except (ValueError, OSError):
                continue

    return apply


async def run_code_interpreter(code: str) -> str:
    settings = get_settings()
    source = (code or "").strip()
    if not source:
        return fail("code_interpreter", "缺少代码参数 code")
    if not settings.sandbox_enabled:
        return degraded("code_interpreter", "代码执行沙箱已关闭（SANDBOX_ENABLED=false）")
    problem = check_code(source)
    if problem:
        return fail("code_interpreter", problem)

    timeout = max(1, int(settings.sandbox_timeout_seconds))
    limit = max(200, int(settings.sandbox_max_output_chars))
    with tempfile.TemporaryDirectory(prefix="albedo-sandbox-") as workdir:
        script = Path(workdir) / "main.py"
        script.write_text(source, encoding="utf-8")
        env = {
            "PATH": "/usr/bin:/bin:/usr/local/bin",
            "HOME": workdir,
            "TMPDIR": workdir,
            "PYTHONHASHSEED": "0",
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-I", "-B", str(script),
            cwd=workdir,
            env=env,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=True,
            preexec_fn=_limit_setter(timeout, max(64, int(settings.sandbox_memory_mb))),
        )
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
        except (asyncio.TimeoutError, TimeoutError):
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                process.kill()
            await process.wait()
            return degraded(
                "code_interpreter",
                f"执行超过 {timeout} 秒上限，进程已终止",
                timeoutSeconds=timeout,
            )

    out = stdout.decode("utf-8", errors="replace")
    err = stderr.decode("utf-8", errors="replace")
    truncated = len(out) > limit
    payload = {
        "status": "success" if process.returncode == 0 else "error",
        "tool": "code_interpreter",
        "exitCode": process.returncode,
        "stdout": out[:limit],
        "stderr": err[:limit],
        "truncated": truncated,
    }
    if process.returncode == 0:
        payload["summary"] = f"代码执行完成，输出 {len(out)} 字符"
    else:
        payload["summary"] = f"代码执行失败（退出码 {process.returncode}）：{err.strip()[:200] or '无错误输出'}"
    return dump(payload)


# ---------------------------------------------------------------- Skill L3 脚本


async def run_skill_script(source: str, package: Path, members: list[str]) -> dict:
    """在受限进程中执行 Skill 包内脚本，只返回输出；脚本源码不进入上下文。

    复用 code_interpreter 的 AST 静态拦截与 RLIMIT 降级沙箱（非容器级隔离）。
    包内文本文件解压到同一临时工作目录，供脚本以相对路径读取。
    """
    settings = get_settings()
    if not settings.sandbox_enabled:
        return {"status": "degraded", "summary": "代码执行沙箱已关闭（SANDBOX_ENABLED=false）", "stdout": "", "stderr": ""}
    problem = check_code(source or "")
    if problem:
        return {"status": "error", "summary": problem, "stdout": "", "stderr": ""}
    timeout = max(1, int(settings.sandbox_timeout_seconds))
    output_limit = max(200, int(settings.sandbox_max_output_chars))
    with tempfile.TemporaryDirectory(prefix="albedo-skill-sandbox-") as workdir:
        root = Path(workdir)
        try:
            extract_members(package, members, root)
        except ApiException as exc:
            return {"status": "error", "summary": exc.message, "stdout": "", "stderr": ""}
        # 白名单禁止 open()，包内文本资源改由宿主预读并注入 RESOURCES。
        # 脚本用 RESOURCES["相对路径"] 读取，不再具备任意文件读取能力。
        resources: dict[str, str] = {}
        for member in sorted(root.rglob("*")):
            if not member.is_file() or member.name == "main.py":
                continue
            if member.suffix.lower() not in TEXT_SUFFIXES:
                continue
            try:
                resources[str(member.relative_to(root))] = member.read_text(encoding="utf-8")[:MAX_PARSE_CHARS]
            except (OSError, UnicodeDecodeError):
                continue
        script = root / "main.py"
        script.write_text(
            "RESOURCES = " + json.dumps(resources, ensure_ascii=False) + "\n" + source,
            encoding="utf-8",
        )
        env = {
            "PATH": "/usr/bin:/bin:/usr/local/bin",
            "HOME": workdir,
            "TMPDIR": workdir,
            "PYTHONHASHSEED": "0",
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-I", "-B", str(script),
            cwd=workdir,
            env=env,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=True,
            preexec_fn=_limit_setter(timeout, max(64, int(settings.sandbox_memory_mb))),
        )
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
        except (asyncio.TimeoutError, TimeoutError):
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                process.kill()
            await process.wait()
            return {
                "status": "degraded",
                "summary": f"执行超过 {timeout} 秒上限，进程已终止",
                "stdout": "", "stderr": "",
            }
        out = stdout.decode("utf-8", errors="replace")[:output_limit]
        err = stderr.decode("utf-8", errors="replace")[:output_limit]
        status = "success" if process.returncode == 0 else "error"
        summary = (
            f"脚本执行完成，输出 {len(out)} 字符"
            if status == "success"
            else f"脚本执行失败（退出码 {process.returncode}）：{err.strip()[:200] or '无错误输出'}"
        )
        return {"status": status, "exitCode": process.returncode, "stdout": out, "stderr": err, "summary": summary}


# ---------------------------------------------------------------- 文件解析


def _read_text_file(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def _read_pdf(path: Path) -> tuple[str, dict]:
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    pages = [page.extract_text() or "" for page in reader.pages[:50]]
    return "\n\n".join(pages), {"pages": len(reader.pages)}


def _read_docx(path: Path) -> tuple[str, dict]:
    from docx import Document

    document = Document(str(path))
    blocks = [paragraph.text for paragraph in document.paragraphs if paragraph.text.strip()]
    for table in document.tables[:10]:
        for row in table.rows[:50]:
            blocks.append("\t".join(cell.text.strip() for cell in row.cells))
    return "\n".join(blocks), {"paragraphs": len(document.paragraphs), "tables": len(document.tables)}


def _read_xlsx(path: Path) -> tuple[str, dict]:
    from openpyxl import load_workbook

    workbook = load_workbook(str(path), read_only=True, data_only=True)
    blocks: list[str] = []
    for sheet in workbook.worksheets[:10]:
        blocks.append(f"[sheet] {sheet.title}")
        for index, row in enumerate(sheet.iter_rows(values_only=True)):
            if index >= 100:
                blocks.append("...")
                break
            blocks.append("\t".join("" if cell is None else str(cell) for cell in row))
    return "\n".join(blocks), {"sheets": len(workbook.worksheets)}


def _read_csv(path: Path) -> tuple[str, dict]:
    rows = path.read_text(encoding="utf-8", errors="ignore").splitlines()[:200]
    return "\n".join(rows), {"rows": len(rows)}


def extract_file(path: Path) -> tuple[str, dict]:
    suffix = path.suffix.lower()
    if suffix in {".pdf"}:
        return _read_pdf(path)
    if suffix in {".docx"}:
        return _read_docx(path)
    if suffix in {".xlsx", ".xlsm"}:
        return _read_xlsx(path)
    if suffix in {".csv", ".tsv"}:
        return _read_csv(path)
    if suffix in TEXT_SUFFIXES:
        return _read_text_file(path), {}
    raise ValueError(f"暂不支持解析 {suffix or '未知'} 类型，支持 PDF/Word/Excel/CSV/纯文本")


async def run_file_parser(file_name: str = "", query: str = "") -> str:
    # 兼容统一的 query 调用形态；路径穿越仍由 resolve_upload 拒绝。
    path, error = resolve_upload(file_name or query)
    if path is None:
        return fail("file_parser", error)
    try:
        text, meta = await asyncio.to_thread(extract_file, path)
    except Exception as exc:  # noqa: BLE001
        return fail("file_parser", f"解析失败：{type(exc).__name__}: {str(exc)[:160]}", fileName=path.name)
    chars = len(text)
    return dump({
        "status": "success",
        "tool": "file_parser",
        "fileName": path.name,
        "suffix": path.suffix.lower(),
        "chars": chars,
        "truncated": chars > MAX_PARSE_CHARS,
        "preview": text[:PREVIEW_CHARS],
        "content": text[:MAX_PARSE_CHARS],
        "summary": f"已解析 {path.name}（{chars} 字符）",
        **meta,
    })


# ---------------------------------------------------------------- 图片理解


async def run_image_inspector(file_name: str, question: str = "请描述这张图片的内容，并提取其中的文字") -> str:
    settings = get_settings()
    model = (settings.vision_model or settings.llm_model or "").strip()
    if not (settings.llm_base_url and settings.llm_api_key and model):
        return degraded("image_inspector", "未配置多模态模型凭据，请设置 LLM_BASE_URL / LLM_API_KEY / VISION_MODEL")
    path, error = resolve_upload(file_name)
    if path is None:
        return fail("image_inspector", error)
    if path.suffix.lower() not in IMAGE_SUFFIXES:
        return fail("image_inspector", f"不支持 {path.suffix or '未知'} 类型，仅支持 PNG/JPG/WEBP/GIF")
    mime = mimetypes.guess_type(path.name)[0] or "image/png"
    encoded = base64.b64encode(await asyncio.to_thread(path.read_bytes)).decode()
    payload = {
        "model": model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": question or "请描述这张图片的内容"},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}},
            ],
        }],
        "max_tokens": 1024,
        "temperature": 0.2,
    }
    endpoint = f"{settings.llm_base_url.rstrip('/')}/chat/completions"
    try:
        async with httpx.AsyncClient(timeout=float(settings.llm_timeout_seconds)) as client:
            response = await client.post(
                endpoint,
                headers={"Authorization": f"Bearer {settings.llm_api_key}"},
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
    except Exception as exc:  # noqa: BLE001
        return fail("image_inspector", f"多模态调用失败：{type(exc).__name__}: {str(exc)[:160]}", fileName=path.name)
    choices = data.get("choices") or []
    content = ""
    if choices:
        message = choices[0].get("message") or {}
        content = message.get("content") or ""
        if isinstance(content, list):
            content = "".join(str(part.get("text") or "") for part in content if isinstance(part, dict))
    if not str(content).strip():
        return degraded("image_inspector", "多模态模型未返回文本内容", fileName=path.name, model=model)
    return dump({
        "status": "success",
        "tool": "image_inspector",
        "fileName": path.name,
        "model": model,
        "content": str(content)[:4000],
        "summary": f"已完成 {path.name} 的图片理解",
    })


# ---------------------------------------------------------------- HTTP 请求

ALLOWED_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"}
BLOCKED_HEADERS = {"host", "content-length", "connection", "transfer-encoding"}


async def run_http_request(url: str, method: str = "GET", headers_json: str = "", body: str = "") -> str:
    settings = get_settings()
    target = (url or "").strip()
    verb = (method or "GET").strip().upper()
    if verb not in ALLOWED_METHODS:
        return fail("http_request", f"不支持的请求方法 {verb}，仅允许 {'/'.join(sorted(ALLOWED_METHODS))}")
    try:
        await assert_public_url(target)
    except ApiException as exc:
        return fail("http_request", exc.message, url=target)
    except Exception as exc:  # noqa: BLE001
        return fail("http_request", f"地址校验失败：{str(exc)[:160]}", url=target)

    allow = [item.strip().lower() for item in (settings.http_allow_hosts or "").split(",") if item.strip()]
    host = (urlparse(target).hostname or "").lower()
    if allow and host not in allow:
        return fail("http_request", f"域名 {host} 不在 HTTP_ALLOW_HOSTS 白名单内，已拦截", url=target)

    try:
        extra = json.loads(headers_json) if (headers_json or "").strip() else {}
    except json.JSONDecodeError:
        return fail("http_request", "headers_json 不是合法 JSON")
    if not isinstance(extra, dict):
        return fail("http_request", "headers_json 必须是 JSON 对象")
    headers = {
        str(key): str(value)
        for key, value in extra.items()
        if str(key).lower() not in BLOCKED_HEADERS
    }

    limit = max(1024, int(settings.http_max_response_bytes))
    timeout = float(settings.http_timeout_seconds)
    try:
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
            request = client.build_request(
                verb,
                target,
                headers=headers,
                content=body.encode("utf-8") if body else None,
            )
            response = await client.send(request, stream=True)
            try:
                chunks: list[bytes] = []
                size = 0
                truncated = False
                async for chunk in response.aiter_bytes():
                    if size + len(chunk) > limit:
                        chunks.append(chunk[: limit - size])
                        truncated = True
                        break
                    chunks.append(chunk)
                    size += len(chunk)
                raw = b"".join(chunks)
            finally:
                await response.aclose()
    except Exception as exc:  # noqa: BLE001
        return fail("http_request", f"请求失败：{type(exc).__name__}: {str(exc)[:160]}", url=target)

    text = raw.decode(response.encoding or "utf-8", errors="replace")
    return dump({
        "status": "success" if response.status_code < 400 else "error",
        "tool": "http_request",
        "url": target,
        "method": verb,
        "statusCode": response.status_code,
        "contentType": response.headers.get("content-type", ""),
        "bytes": len(raw),
        "truncated": truncated,
        "location": response.headers.get("location", ""),
        "body": text[:4000],
        "summary": f"{verb} {target} 返回 HTTP {response.status_code}（{len(raw)} 字节）",
    })
