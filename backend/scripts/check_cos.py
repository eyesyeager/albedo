"""腾讯云 COS 连通性自检：上传 -> 读回 -> 本地缓存解析 -> 删除。

凭据只从环境变量或 backend/.env 读取，脚本不打印任何密钥、签名或完整异常内容。

用法：
    cd backend
    COS_SECRET_ID=xxx COS_SECRET_KEY=yyy .venv/bin/python scripts/check_cos.py
    # 也可把变量写进 backend/.env 后直接运行；可选 --file 指定本地文件内容。
    .venv/bin/python scripts/check_cos.py --file ./some-file.txt

测试对象写在 <cos_prefix>/_healthcheck/ 下，结束即删除，不会污染 skills/ 业务数据。
"""
import argparse
import sys
import tempfile
import uuid
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.core.config import get_settings  # noqa: E402
from app.services.skill_storage import CosSkillStorage  # noqa: E402


def brief(exc: Exception) -> str:
    """沿异常链提取 COS 错误码，不打印完整请求信息（可能含签名参数）。

    CosSkillStorage 会把 SDK 异常包装成 ApiException，真实错误码藏在 __cause__ 里。
    """
    codes: list[str] = []
    current: BaseException | None = exc
    while current is not None and len(codes) < 3:
        found = ""
        for getter in ("get_error_code", "get_status_code"):
            fn = getattr(current, getter, None)
            if callable(fn):
                try:
                    found = str(fn())
                except Exception:  # noqa: BLE001
                    found = ""
                if found:
                    break
        if not found and current.args:
            first = current.args[0]
            if isinstance(first, dict) and first.get("code"):
                found = str(first["code"])
        if found and found not in codes:
            codes.append(found)
        current = current.__cause__ or current.__context__
    return f"COS {' / '.join(codes)}" if codes else type(exc).__name__


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", help="可选：上传该本地文件的内容，默认生成一段探测文本")
    args = parser.parse_args()

    settings = get_settings()
    print(f"配置：bucket={settings.cos_bucket} region={settings.cos_region} "
          f"prefix={settings.cos_prefix} scheme={settings.cos_scheme} "
          f"endpoint={settings.cos_endpoint or '(官方域名)'}")
    if not settings.cos_configured:
        print("✗ COS_SECRET_ID / COS_SECRET_KEY 未配置，无法连接 COS。")
        print("  当前 get_skill_storage() 会降级为 LocalSkillStorage，包写入 backend/var/skills/。")
        return 2

    suffix = Path(args.file).name if args.file else f"probe-{uuid.uuid4().hex}.txt"
    key = f"_healthcheck/{uuid.uuid4().hex[:8]}-{suffix}"
    content = Path(args.file).read_bytes() if args.file else f"albedo cos probe {uuid.uuid4().hex}\n".encode()

    try:
        with tempfile.TemporaryDirectory(prefix="albedo-cos-check-") as tmp:
            storage = CosSkillStorage(settings, Path(tmp))
            print(f"→ 上传：{storage.uri(key)}  ({len(content)} 字节)")
            storage.put(key, content)

            pulled = storage.fetch(key)
            if pulled != content:
                print("✗ 读回内容与上传内容不一致")
                return 1
            print("✓ 上传 / 读回一致")

            cached = storage.materialize(key)
            if cached is None or cached.read_bytes() != content:
                print("✗ 本地缓存解析失败")
                return 1
            print("✓ 本地缓存解析正常（L3 可按路径读取）")

            storage.delete(key)
            print("✓ 已删除测试对象，可在控制台确认 skills/_healthcheck/ 下无残留")
    except Exception as exc:  # noqa: BLE001
        print(f"✗ COS 调用失败：{brief(exc)}")
        print("  常见原因：密钥无权限、bucket/region 不匹配、网络不通或 CAM 策略未放行该前缀。")
        return 1

    print("COS 连通性自检通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
