"""Skill progressive disclosure: L1 trigger surface, L2 versioned body, L3 audit.

Revision ID: b6d2f1a9c4e7
Revises: 8c1e5d3f7a20
Create Date: 2026-11-07
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "b6d2f1a9c4e7"
down_revision: Union[str, None] = "8c1e5d3f7a20"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # MySQL 不允许 BLOB/TEXT/JSON 列带字面量默认值，新增列统一可空，值由 ORM 层写入。
    op.add_column("skills", sa.Column("trigger_description", sa.Text(), nullable=True))
    op.add_column("skill_versions", sa.Column("instructions", sa.Text(), nullable=True))
    op.add_column("skill_versions", sa.Column("entry", sa.String(length=255), nullable=True))
    op.add_column("skill_versions", sa.Column("package_key", sa.String(length=255), nullable=True))
    op.add_column("skill_versions", sa.Column("frontmatter", sa.JSON(), nullable=True))
    op.create_table(
        "skill_load_records",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("thread_id", sa.String(length=64), nullable=False),
        sa.Column("agent_id", sa.String(length=64), nullable=False),
        sa.Column("skill_id", sa.String(length=64), nullable=False),
        sa.Column("version", sa.String(length=32), nullable=False),
        sa.Column("level", sa.Integer(), nullable=False),
        sa.Column("action", sa.String(length=32), nullable=False),
        sa.Column("member", sa.String(length=255), nullable=False, server_default=""),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("chars", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("timestamp", sa.String(length=40), nullable=False),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_skill_load_records")),
    )
    op.create_index(op.f("ix_skill_load_records_run_id"), "skill_load_records", ["run_id"], unique=False)
    op.create_index(op.f("ix_skill_load_records_skill_id"), "skill_load_records", ["skill_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_skill_load_records_skill_id"), table_name="skill_load_records")
    op.drop_index(op.f("ix_skill_load_records_run_id"), table_name="skill_load_records")
    op.drop_table("skill_load_records")
    op.drop_column("skill_versions", "frontmatter")
    op.drop_column("skill_versions", "package_key")
    op.drop_column("skill_versions", "entry")
    op.drop_column("skill_versions", "instructions")
    op.drop_column("skills", "trigger_description")
