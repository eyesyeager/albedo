"""数据库初始化种子：只写入系统运行所必需的基础数据。

演示用的智能体、模型、日志、趋势、调用记录等一律不再预置，
所有业务数据都来自用户的真实操作与真实运行。
"""

from typing import Any

from app.core.config import get_settings
from app.core.security import suffix_of
from app.db.system_config_defaults import default_system_config


def make_seed() -> dict[str, Any]:
    config = default_system_config()
    platform = config["platform"]
    access_key = get_settings().demo_access_key
    return {
        "settings": {
            "workspaceName": platform["defaultWorkspaceName"],
            "language": platform["defaultLanguage"],
            "timezone": platform["defaultTimezone"],
            "domain": platform["domain"],
            "telemetry": True,
            "autoArchive": True,
            "retentionDays": int(platform["defaultRetentionDays"]),
            "accessKey": access_key,
            "accessKeySuffix": suffix_of(access_key),
        },
        "tools": [
            {
                "id": f"tool-{item['key']}",
                "key": item["key"],
                "name": item["name"],
                "description": item["description"],
                "version": item["version"],
                "enabled": True,
                "callCount": 0,
            }
            for item in config["builtinTools"]
        ],
    }
