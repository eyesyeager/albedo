"""add mcp_tools.input_schema

Revision ID: 3a9d5c7e21f4
Revises: 7f9c2e4a1b36
Create Date: 2026-11-06

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '3a9d5c7e21f4'
down_revision: Union[str, None] = '7f9c2e4a1b36'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('mcp_tools', sa.Column('input_schema', sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column('mcp_tools', 'input_schema')
