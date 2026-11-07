"""add mcp_servers.env_enc for stdio environment variables

Revision ID: 8c1e5d3f7a20
Revises: 3a9d5c7e21f4
Create Date: 2026-11-07

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '8c1e5d3f7a20'
down_revision: Union[str, None] = '3a9d5c7e21f4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # MySQL 的 TEXT 列不支持 DEFAULT；已有行按 NULL 处理，读取时统一视为空字符串。
    op.add_column('mcp_servers', sa.Column('env_enc', sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column('mcp_servers', 'env_enc')
