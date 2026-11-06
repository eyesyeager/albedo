import logging
from functools import lru_cache
from pathlib import Path

from cryptography.fernet import Fernet
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_ROOT = Path(__file__).resolve().parents[2]

logger = logging.getLogger(__name__)

# 公开文档中曾出现过的弱凭据黑名单，仅用于生产环境启动校验。
PUBLIC_DEMO_KEYS = frozenset({"albedo-demo-2026"})
PLACEHOLDER_DSN = "mysql+aiomysql://USER:URL_ENCODED_PASSWORD@HOST:PORT/albedo"
LOCAL_CORS_ORIGINS = (
    "http://localhost:5173,http://127.0.0.1:5173,"
    "http://localhost:5174,http://127.0.0.1:5174,"
    "http://localhost:5175,http://127.0.0.1:5175"
)


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
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = ""
    llm_timeout_seconds: int = 60
    llm_temperature: float = 0.7
    checkpoint_backend: str = "redis"
    cookie_secure: bool = False
    csrf_enabled: bool = False
    environment: str = "dev"
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

    # 内置工具：联网搜索
    search_provider: str = "bing"  # bing | duckduckgo | tavily | serper | off
    search_api_key: str = ""
    search_max_results: int = 5
    # 内置工具：HTTP 请求
    http_allow_hosts: str = ""  # 逗号分隔域名白名单，留空表示允许任意公网域名
    http_timeout_seconds: int = 15
    http_max_response_bytes: int = 512 * 1024
    # 内置工具：代码执行沙箱
    sandbox_enabled: bool = True
    sandbox_timeout_seconds: int = 8
    sandbox_memory_mb: int = 256
    sandbox_max_output_chars: int = 8000
    # 内置工具：文件解析与图片理解
    tool_upload_max_bytes: int = 10 * 1024 * 1024
    vision_model: str = ""  # 留空复用 LLM_MODEL

    @property
    def session_cookie_name(self) -> str:
        return "albedo_session"

    @property
    def fernet_key(self) -> bytes:
        return self.secret_key.encode()

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @model_validator(mode="after")
    def validate_runtime_config(self) -> "Settings":
        if not self.demo_access_key:
            raise ValueError("DEMO_ACCESS_KEY 必须通过环境变量或 .env 配置，源码不提供可用的默认凭据")

        if self.environment == "dev":
            if not self.secret_key:
                self.secret_key = Fernet.generate_key().decode()
                logger.warning("SECRET_KEY 未配置：已生成本进程临时密钥，重启后已加密凭据不可解密")
            return self

        problems: list[str] = []
        if not self.secret_key:
            problems.append("SECRET_KEY 必须显式配置")
        else:
            try:
                Fernet(self.secret_key.encode())
            except (ValueError, TypeError):
                problems.append("SECRET_KEY 不是合法的 Fernet 密钥")
        if self.demo_access_key in PUBLIC_DEMO_KEYS:
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
    "sandbox_enabled", "sandbox_timeout_seconds", "sandbox_memory_mb", "sandbox_max_output_chars",
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
