from app.core.redis_client import try_increment
from app.services.system_config import limit as config_limit


async def allow_qpm(scope: str, limit: int) -> bool:
    """固定窗口配额；窗口长度来自数据库系统配置，Redis 不可用时降级为进程内计数。"""
    if limit <= 0:
        return True
    window = int(config_limit("rateLimitWindowSeconds", 60))
    count = await try_increment(f"albedo:qpm:{scope}", window)
    return count <= limit
