"""调用趋势：按平台配置的时区从真实日志记录聚合自然日，不再依赖预置数列。"""

from collections import Counter
from datetime import date, datetime, timedelta, timezone, tzinfo
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.core.config import get_settings


def platform_tz() -> tzinfo:
    """平台时区统一由部署配置提供，避免各模块硬编码 UTC+8 与 workspace 设置冲突。"""
    try:
        return ZoneInfo(get_settings().platform_timezone)
    except (ZoneInfoNotFoundError, ValueError, KeyError):
        return timezone.utc


def today_local() -> date:
    return datetime.now(platform_tz()).date()


def local_date(iso_value: str) -> str:
    try:
        moment = datetime.fromisoformat(iso_value.replace("Z", "+00:00"))
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        return moment.astimezone(platform_tz()).strftime("%Y-%m-%d")
    except (ValueError, AttributeError):
        return ""


def build_trend_points(timestamps: list[str], days: int = 30) -> list[dict]:
    counts = Counter(key for key in (local_date(item) for item in timestamps) if key)
    today = today_local()
    points: list[dict] = []
    for offset in range(days - 1, -1, -1):
        day = today - timedelta(days=offset)
        key = day.strftime("%Y-%m-%d")
        points.append({
            "date": key,
            "label": f"{day.month} 月 {day.day} 日",
            "count": counts.get(key, 0),
        })
    return points
