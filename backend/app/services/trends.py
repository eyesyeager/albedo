"""调用趋势：从真实日志记录按自然日聚合，不再依赖预置的固定数列。"""

from collections import Counter
from datetime import datetime, timedelta, timezone

LOCAL_TZ = timezone(timedelta(hours=8))


def local_date(iso_value: str) -> str:
    try:
        moment = datetime.fromisoformat(iso_value.replace("Z", "+00:00"))
        return moment.astimezone(LOCAL_TZ).strftime("%Y-%m-%d")
    except (ValueError, AttributeError):
        return ""


def build_trend_points(timestamps: list[str], days: int = 30) -> list[dict]:
    counts = Counter(date for date in (local_date(item) for item in timestamps) if date)
    today = datetime.now(LOCAL_TZ).date()
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
