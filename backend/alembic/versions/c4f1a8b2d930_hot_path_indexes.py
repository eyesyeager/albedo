"""补齐高频筛选字段的复合索引（非破坏性，可在线执行）。

Revision ID: c4f1a8b2d930
Revises: b6d2f1a9c4e7
"""

from typing import Sequence, Union

from alembic import op

revision: str = "c4f1a8b2d930"
down_revision: Union[str, None] = "b6d2f1a9c4e7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

INDEXES = (
    ("ix_log_agent_time", "log_entries", ["agent_id", "timestamp"]),
    ("ix_log_resource_time", "log_entries", ["resource_id", "timestamp"]),
    ("ix_chat_thread_time", "chat_messages", ["thread_id", "timestamp"]),
    ("ix_export_expires_status", "export_tasks", ["expires_at", "status"]),
    ("ix_cred_type_suffix", "credentials", ["type", "suffix"]),
    ("ix_notification_time", "notifications", ["timestamp"]),
    ("ix_run_created", "runs", ["created_at"]),
)


def upgrade() -> None:
    for name, table, columns in INDEXES:
        op.create_index(name, table, columns, unique=False)


def downgrade() -> None:
    for name, table, _ in reversed(INDEXES):
        op.drop_index(name, table_name=table)
