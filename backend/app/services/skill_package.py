"""Skill 包解析与安全读取。

分层语义：
- L1：SKILL.md frontmatter 的 name/description 作为触发面，常驻 system prompt
- L2：SKILL.md 正文，任务命中时才进入上下文
- L3：包内其余文件按需读取；脚本交给受限进程执行，代码本身不进上下文
"""
import io
import json
import re
import zipfile
from pathlib import Path
from typing import Any

from app.core.errors import ApiException
from app.services.system_config import limit as config_limit

SKILL_MD_NAME = "SKILL.md"
MAX_SKILL_MD_BYTES = 256 * 1024
MAX_RESOURCE_BYTES = 64 * 1024
MAX_SCRIPT_BYTES = 64 * 1024
MAX_EXTRACT_FILES = 40
RESOURCE_SUFFIXES = {
    ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".jsonl", ".log",
    ".yml", ".yaml", ".xml", ".html", ".htm", ".sql", ".ini", ".toml", ".conf",
}
SCRIPT_SUFFIXES = {".py"}

_FRONTMATTER = re.compile(r"\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*\r?\n?(.*)\Z", re.S)


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    """解析 YAML frontmatter 的键值子集（不引入额外依赖）。

    支持 `key: value` 以及 `>-` / `>` / `|-` / `|` 折叠块，足以覆盖 SKILL.md 规范。
    """
    match = _FRONTMATTER.match(text)
    if not match:
        return {}, text.strip()
    meta: dict[str, str] = {}
    lines = match.group(1).splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        index += 1
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip().lower()
        value = value.strip()
        if value in {">-", ">", "|-", "|"}:
            block: list[str] = []
            while index < len(lines) and (not lines[index].strip() or lines[index][:1].isspace()):
                block.append(lines[index].strip())
                index += 1
            separator = "\n" if value.startswith("|") else " "
            meta[key] = separator.join(part for part in block if part).strip()
        else:
            meta[key] = value.strip("\"'")
    return meta, match.group(2).strip()


def _find_entry(names: list[str]) -> str | None:
    candidates = [name for name in names if Path(name).name == SKILL_MD_NAME and not name.endswith("/")]
    if not candidates:
        return None
    return sorted(candidates, key=lambda name: (name.count("/"), len(name)))[0]


def _safe_member(member: str) -> str:
    normalized = str(member or "").strip().replace("\\", "/")
    if not normalized or normalized.startswith("/") or ".." in normalized.split("/"):
        raise ApiException("validation_error", "资源路径不合法", 400)
    return normalized.lstrip("/")


def inspect_zip(content: bytes) -> dict:
    """上传期结构校验 + SKILL.md 解析。返回值兼容原有 files/uncompressedBytes/manifest。"""
    max_files = int(config_limit("skillMaxFiles", 500))
    max_uncompressed = int(config_limit("skillMaxUncompressedBytes", 200 * 1024 * 1024))
    max_depth = int(config_limit("skillMaxDepth", 6))
    try:
        archive = zipfile.ZipFile(io.BytesIO(content))
    except zipfile.BadZipFile as exc:
        raise ApiException("skill_manifest_invalid", "Skill 包不是有效的 ZIP 文件", 422) from exc

    files = 0
    total = 0
    for info in archive.infolist():
        name = info.filename
        if name.startswith("/") or "\\" in name or ".." in name.split("/"):
            raise ApiException("validation_error", f"Skill 包包含非法路径：{name}", 400)
        depth = len([part for part in name.split("/") if part])
        if depth > max_depth:
            raise ApiException("skill_manifest_invalid", f"Skill 包目录层级超过 {max_depth} 层", 422)
        mode = (info.external_attr >> 16) & 0o170000
        if mode == 0o120000:
            raise ApiException("skill_manifest_invalid", "Skill 包包含符号链接，已拒绝", 422)
        if info.is_dir():
            continue
        files += 1
        total += info.file_size
        if files > max_files or total > max_uncompressed:
            raise ApiException("payload_too_large", "Skill 包解压后文件数或体积超限", 413)

    if files == 0:
        raise ApiException("skill_manifest_invalid", "Skill 包为空，至少需要包含一个文件", 422)

    manifest = None
    if "manifest.json" in archive.namelist():
        try:
            manifest = json.loads(archive.read("manifest.json"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ApiException("skill_manifest_invalid", "manifest.json 不是合法的 JSON", 422) from exc
        if not isinstance(manifest, dict) or not str(manifest.get("name") or "").strip():
            raise ApiException("skill_manifest_invalid", "manifest.json 缺少 name 字段", 422)

    names = archive.namelist()
    entry = _find_entry(names)
    frontmatter: dict[str, str] = {}
    body = ""
    if entry:
        info = archive.getinfo(entry)
        if info.file_size > MAX_SKILL_MD_BYTES:
            raise ApiException("payload_too_large", "SKILL.md 超过 256 KB 上限", 413)
        frontmatter, body = _parse_frontmatter(archive.read(entry).decode("utf-8", "ignore"))

    return {
        "files": files,
        "uncompressedBytes": total,
        "manifest": manifest,
        "entry": entry or "",
        "frontmatter": frontmatter,
        "body": body,
        "hasSkillMd": bool(entry),
        "members": [name for name in names if not name.endswith("/")],
    }


def package_key(skill_id: str, version: str, file_name: str) -> str:
    """容器内路径无关的相对 key，避免把绝对路径写进数据库。"""
    return f"{skill_id}/{version}/{Path(file_name or '').name}"


def resolve_package_path(file_name: str, key: str = "", file_path: str = "") -> Path | None:
    """同步兼容入口（脚本/管理工具用）。

    运行时请改用 skill_storage.materialize_async，避免 COS 下载阻塞事件循环。
    """
    from app.services.skill_storage import _existing_file, get_skill_storage

    if not key and not file_path:
        return None
    if not key:
        return _existing_file(file_path)
    return get_skill_storage().materialize(key, file_path)


def list_members(path: Path | None) -> list[str]:
    if path is None:
        return []
    with zipfile.ZipFile(path) as archive:
        return [name for name in archive.namelist() if not name.endswith("/")]


def read_member(path: Path | None, member: str, *, max_bytes: int = MAX_RESOURCE_BYTES) -> bytes:
    if path is None:
        raise ApiException("skill_resource_unavailable", "Skill 包文件不可用，请重新上传该版本", 404)
    target = _safe_member(member)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if target not in names:
            raise ApiException("resource_not_found", f"包内不存在文件：{target}", 404)
        info = archive.getinfo(target)
        if info.file_size > max_bytes:
            raise ApiException("payload_too_large", "资源文件超过单次读取上限", 413)
        return archive.read(target)


def resource_members(path: Path | None) -> list[str]:
    return [name for name in list_members(path) if Path(name).suffix.lower() in RESOURCE_SUFFIXES]


def script_members(path: Path | None) -> list[str]:
    return [name for name in list_members(path) if Path(name).suffix.lower() in SCRIPT_SUFFIXES]


def extract_members(path: Path | None, names: list[str], workdir: Path, *, max_bytes: int = MAX_SCRIPT_BYTES) -> None:
    """把脚本执行所需的文件解压到沙箱工作目录，保持包内相对路径。"""
    if path is None:
        raise ApiException("skill_resource_unavailable", "Skill 包文件不可用", 404)
    if len(names) > MAX_EXTRACT_FILES:
        raise ApiException("payload_too_large", "包内文件过多，无法在沙箱中执行", 413)
    with zipfile.ZipFile(path) as archive:
        available = set(archive.namelist())
        for name in names:
            target = _safe_member(name)
            if target not in available:
                continue
            if archive.getinfo(target).file_size > max_bytes:
                continue
            destination = workdir / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(target))


def frontmatter_dict(value: Any) -> dict[str, str]:
    if isinstance(value, dict):
        return {str(key): str(item) for key, item in value.items()}
    return {}
