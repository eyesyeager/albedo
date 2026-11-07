import asyncio
import ipaddress
import socket
from urllib.parse import urlparse

from app.core.errors import ApiException

BLOCKED_HOSTS = {"localhost", "metadata.google.internal", "metadata.tencentyun.com"}


def blocked_ip(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return True
    return any((
        address.is_private,
        address.is_loopback,
        address.is_link_local,
        address.is_multicast,
        address.is_reserved,
        address.is_unspecified,
    ))


async def assert_public_url(url: str, *, allow_private: bool = False) -> None:
    """SSRF 校验。allow_private 仅供 MCP 在管理员显式开启配置后放行本地/内网服务。"""
    parsed = urlparse(url.strip())
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ApiException("validation_error", "URL 必须是合法的 HTTP(S) 地址", 400)
    if allow_private:
        return
    host = parsed.hostname.lower()
    if host in BLOCKED_HOSTS or host.endswith(".local") or host.endswith(".internal"):
        raise ApiException("validation_error", "该地址位于受限网络，已被 SSRF 防护拦截", 400)
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        infos = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM),
            timeout=3,
        )
    except (OSError, asyncio.TimeoutError) as exc:
        raise ApiException("upstream_unavailable", "域名解析失败或超时，请检查地址与网络", 502) from exc
    for info in infos:
        if blocked_ip(str(info[4][0])):
            raise ApiException("validation_error", "该地址解析到受限网络，已被 SSRF 防护拦截", 400)
