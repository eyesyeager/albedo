import io
import json
import zipfile

from app.core.errors import ApiException
from app.services.system_config import limit as config_limit


def inspect_zip(content: bytes) -> dict:
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
    return {"files": files, "uncompressedBytes": total, "manifest": manifest}
