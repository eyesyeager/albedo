import base64
import hashlib
import logging
from functools import lru_cache
from pathlib import Path

from cryptography.fernet import Fernet
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_ROOT = Path(__file__).resolve().parents[2]

logger = logging.getLogger(__name__)

# 公开文档中曾出现过的弱凭据黑名单，用于生产环境启动校验与登录态检查。
PUBLIC_DEMO_KEYS = frozenset({"albedo-demo-2026"})
PLACEHOLDER_DSN = "mysql+aiomysql://USER:URL_ENCODED_PASSWORD@HOST:PORT/albedo"
LOCAL_CORS_ORIGINS = (
    "http://localhost:5173,http://127.0.0.1:5173,"
    "http://localhost:5174,http://127.0.0.1:5174,"
    "http://localhost:5175,http://127.0.0.1:5175"
)
_DEV_KEY_PATH = BACKEND_ROOT / "var" / ".dev-secret-key"


def is_public_demo_key(value: str) -> bool:
    """规范化后比较，避免大小写 / 首尾空白变体绕过黑名单。"""
    return value.strip().casefold() in {key.casefold() for key in PUBLIC_DEMO_KEYS}


def _derive_data_key(raw: bytes, version: str = "v1") -> bytes:
    digest = hashlib.sha256(b"albedo:data-encryption:" + version.encode() + b"\0" + raw).digest()
    return base64.urlsafe_b64encode(digest)


def _persistent_dev_key() -> str:
    """dev 模式使用固定本地密钥文件，避免每次重启随机生成导致库内凭据不可解密。"""
    try:
        if _DEV_KEY_PATH.exists():
            existing = _DEV_KEY_PATH.read_text(encoding="utf-8").strip()
            if existing:
                Fernet(existing.encode())
                return existing
        generated = Fernet.generate_key().decode()
        _DEV_KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
        _DEV_KEY_PATH.write_text(generated, encoding="utf-8")
        _DEV_KEY_PATH.chmod(0o600)
        logger.warning("SECRET_KEY 未配置：已在 %s 生成 dev 本地密钥（该文件不入库）", _DEV_KEY_PATH)
        return generated
    except (OSError, ValueError, TypeError):
        return Fernet.generate_key().decode()


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(BACKEND_ROOT / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # 连接串与凭据一律由环境变量 / .env 注入，源码内只保留不可用的占位符。
    database_url: str = PLACEHOLDER_DSN
    redis_url: str = "redis://127.0.0.1:6379/0"
    secret_key: str = ""
    # 轮换时填写旧值，MultiFernet 会在解密时依次尝试，加密始终使用最新的派生密钥。
    secret_key_previous: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = ""
    llm_timeout_seconds: int = 60
    llm_temperature: float = 0.7
    checkpoint_backend: str = "redis"
    cookie_secure: bool = False
    csrf_enabled: bool = False
    # 仅在部署于受信任反向代理之后才开启，用于按真实来源 IP 做登录限流。
    trust_proxy_headers: bool = False
    # 默认按生产环境启动；只有显式设置 ENVIRONMENT=dev 才降级本地开发校验。
    environment: str = "prod"
    demo_access_key: str = ""
    platform_domain: str = "http://localhost:8000"
    cors_origins: str = LOCAL_CORS_ORIGINS

    session_idle_hours: int = 2
    session_absolute_hours: int = 24
    login_max_failures: int = 5
    login_lock_seconds: int = 30

    export_dir: str = str(BACKEND_ROOT / "var" / "exports")
    skill_dir: str = str(BACKEND_ROOT / "var" / "skills")
    upload_dir: str = str(BACKEND_ROOT / "var" / "uploads")

    # Skill 包存储：cos（权威对象存储） | local（单机/离线）。
    skill_storage: str = "cos"
    # bucket / region 非敏感，按部署环境给定默认值，可由环境变量覆盖。
    cos_bucket: str = "albedo-1305182252"
    cos_region: str = "ap-nanjing"
    # 密钥只从环境变量或 .env 注入，绝不写入代码、文档与日志。
    cos_secret_id: str = ""
    cos_secret_key: str = ""
    cos_token: str = ""
    cos_endpoint: str = ""
    cos_scheme: str = "https"
    cos_prefix: str = "skills"
    cos_timeout_seconds: int = 30
    # COS 对象的本地只读缓存：key 含 skillId/version，内容不可变，可安全长期复用。
    skill_cache_dir: str = str(BACKEND_ROOT / "var" / "skill-cache")

    # 内置工具：联网搜索
    search_provider: str = "bing"  # bing | duckduckgo | tavily | serper | off
    search_api_key: str = ""
    search_max_results: int = 5
    # 内置工具：HTTP 请求
    http_allow_hosts: str = ""  # 逗号分隔域名白名单，留空表示允许任意公网域名
    http_timeout_seconds: int = 15
    http_max_response_bytes: int = 512 * 1024
    # 对外网关与管理面请求体上限，防止超大 body 造成内存与上游费用放大。
    max_request_body_bytes: int = 2 * 1024 * 1024
    # 自然日统计、日志时间筛选与内置时间工具共用的平台时区（IANA 名称）。
    platform_timezone: str = "Asia/Shanghai"
    # MCP stdio：默认关闭。开启后仅允许白名单里的可执行文件名，命令按固定系统路径
    # 解析，且用户 env 不得覆盖 PATH / LD_* 等可劫持执行的环境变量。
    mcp_stdio_enabled: bool = False
    mcp_stdio_allowed_commands: str = ""

    # 内置工具：代码执行沙箱。应用层白名单只是纵深防御，不是 OS 级隔离，
    # 生产启用前必须配合容器 / seccomp / 只读文件系统；默认保持关闭。
    sandbox_enabled: bool = False
    sandbox_timeout_seconds: int = 8
    sandbox_memory_mb: int = 256
    sandbox_max_output_chars: int = 8000
    # 内置工具：文件解析与图片理解
    tool_upload_max_bytes: int = 10 * 1024 * 1024
    vision_model: str = ""  # 留空复用 LLM_MODEL

    @property
    def session_cookie_name(self) -> str:
        # __Host- 前缀要求 Secure + Path=/ + 无 Domain，只在启用 Secure Cookie 时使用。
        return "__Host-albedo_session" if self.cookie_secure else "albedo_session"

    @property
    def csrf_cookie_name(self) -> str:
        return "__Host-albedo_csrf" if self.cookie_secure else "albedo_csrf"

    @property
    def session_samesite(self) -> str:
        return "strict" if self.cookie_secure else "lax"

    @property
    def fernet_key(self) -> bytes:
        return self.secret_key.encode()

    @property
    def fernet_keys(self) -> list[bytes]:
        """MultiFernet 密钥环：首个用于加密，其余仅用于解密历史数据。"""
        keys: list[bytes] = []
        for raw in (self.secret_key, self.secret_key_previous):
            if not raw:
                continue
            try:
                Fernet(raw.encode())
            except (ValueError, TypeError):
                logger.warning("SECRET_KEY_PREVIOUS 不是合法的 Fernet 密钥，已忽略")
                continue
            keys.append(_derive_data_key(raw.encode()))
            keys.append(raw.encode())
        return keys

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def cos_configured(self) -> bool:
        return bool(self.cos_secret_id and self.cos_secret_key and self.cos_bucket and self.cos_region)

    @model_validator(mode="after")
    def validate_runtime_config(self) -> "Settings":
        if not self.demo_access_key:
            raise ValueError("DEMO_ACCESS_KEY 必须通过环境变量或 .env 配置，源码不提供可用的默认凭据")

        if self.environment == "dev":
            if not self.secret_key:
                self.secret_key = _persistent_dev_key()
            return self

        problems: list[str] = []
        if not self.secret_key:
            problems.append("SECRET_KEY 必须显式配置")
        else:
            try:
                Fernet(self.secret_key.encode())
            except (ValueError, TypeError):
                problems.append("SECRET_KEY 不是合法的 Fernet 密钥")
        if is_public_demo_key(self.demo_access_key):
            problems.append("DEMO_ACCESS_KEY 不得使用公开文档中的演示 Key")
        if PLACEHOLDER_DSN in self.database_url:
            problems.append("DATABASE_URL 必须指向真实实例")
        if not self.llm_api_key or not self.llm_model:
            problems.append("非 dev 环境必须配置真实上游 LLM_API_KEY 与 LLM_MODEL")
        if not self.cookie_secure:
            problems.append("非 dev 环境必须设置 COOKIE_SECURE=true")
        if not self.csrf_enabled:
            problems.append("非 dev 环境必须设置 CSRF_ENABLED=true")
        if "*" in self.cors_origin_list:
            problems.append("非 dev 环境 CORS_ORIGINS 禁止通配来源")
        if self.skill_storage == "cos" and not self.cos_configured:
            problems.append("SKILL_STORAGE=cos 时必须显式配置 COS_SECRET_ID / COS_SECRET_KEY")
        if problems:
            raise ValueError("生产环境配置校验失败：" + "；".join(problems))
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()


# 业务运行参数的取值来源是 system_configs 表；环境变量只提供首次初始化的种子值。
RUNTIME_CONFIG_FIELDS = frozenset({
    "llm_model", "llm_timeout_seconds", "llm_temperature",
    "search_provider", "search_max_results",
    "http_allow_hosts", "http_timeout_seconds", "http_max_response_bytes",
    "sandbox_timeout_seconds", "sandbox_memory_mb", "sandbox_max_output_chars",
    "tool_upload_max_bytes", "vision_model",
    "session_idle_hours", "session_absolute_hours", "login_max_failures", "login_lock_seconds",
})


def apply_runtime_config(values: dict) -> None:
    """把数据库中的 runtime 配置覆盖到进程内 Settings 实例，避免运行期反复查库。"""
    settings = get_settings()
    for key, value in values.items():
        if key not in RUNTIME_CONFIG_FIELDS:
            continue
        current = getattr(settings, key)
        try:
            if isinstance(current, bool):
                converted = value if isinstance(value, bool) else str(value).strip().lower() in {"1", "true", "yes", "on"}
            elif isinstance(current, int):
                converted = int(value)
            elif isinstance(current, float):
                converted = float(value)
            else:
                converted = str(value)
        except (TypeError, ValueError):
            logger.warning("系统配置 %s 的值 %r 类型不合法，已忽略", key, value)
            continue
        object.__setattr__(settings, key, converted)
