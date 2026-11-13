import asyncio
import ipaddress
import logging
import socket
from urllib.parse import urlparse

from app.core.errors import ApiException

logger = logging.getLogger(__name__)

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


def _parsed_target(url: str):
    parsed = urlparse((url or "").strip())
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ApiException("validation_error", "URL 必须是合法的 HTTP(S) 地址", 400)
    return parsed


def assert_public_literal(url: str) -> None:
    """不联网的字面量校验，用于每次真实转发前拦截被绕过/被篡改的地址。

    域名型目标的解析结果由 assert_public_url 校验；应用层无法完全消除 DNS
    rebinding 的 TOCTOU，部署时仍应配合出口防火墙限制内网与云元数据网段。
    """
    parsed = _parsed_target(url)
    host = parsed.hostname.lower()
    if host in BLOCKED_HOSTS or host.endswith(".local") or host.endswith(".internal"):
        raise ApiException("validation_error", "该地址位于受限网络，已被 SSRF 防护拦截", 400)
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        return
    if blocked_ip(str(literal)):
        raise ApiException("validation_error", "该地址指向受限网络，已被 SSRF 防护拦截", 400)


async def assert_public_url(url: str, *, allow_private: bool = False) -> None:
    """SSRF 校验。allow_private 仅供 MCP 在内网隔离部署时显式放行。"""
    parsed = _parsed_target(url)
    if allow_private:
        logger.warning("出网地址按配置放行受限网络目标，请确认仅在隔离网络内启用该开关")
        return
    assert_public_literal(url)
    host = parsed.hostname.lower()
    try:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
    except ValueError as exc:
        raise ApiException("validation_error", "URL 端口不合法", 400) from exc
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
