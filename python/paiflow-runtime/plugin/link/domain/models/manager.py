import os
from typing import Optional

from plugin.link.consts import const
from plugin.link.domain.entity.tool_schema import Tools
from plugin.link.domain.models.utils import DatabaseService, RedisService

data_base_singleton: Optional[DatabaseService] = None
redis_singleton: Optional[RedisService] = None


def init_data_base() -> None:
    """
    Initialize the database.
    """
    # Use global statement to modify module-level singleton instance
    global data_base_singleton
    mysql_host = os.getenv(const.MYSQL_HOST_KEY)
    mysql_port = os.getenv(const.MYSQL_PORT_KEY)
    user = os.getenv(const.MYSQL_USER_KEY)
    password = os.getenv(const.MYSQL_PASSWORD_KEY)
    db = os.getenv(const.MYSQL_DB_KEY)
    db_url = (
        f"mysql+pymysql://{user}:{password}@{mysql_host}:{mysql_port}/{db}"
        "?charset=utf8mb4"
    )
    data_base_singleton = DatabaseService(database_url=db_url)
    data_base_singleton.create_db_and_tables()

    # Initialize Redis service using global singleton pattern
    # Use global statement to modify module-level singleton instance
    global redis_singleton
    if not (
        addr := os.getenv(const.REDIS_CLUSTER_ADDR_KEY)
        or os.getenv(const.REDIS_ADDR_KEY)
    ):
        raise ValueError("Redis address is not set in environment variables")

    password = os.getenv(const.REDIS_PASSWORD_KEY)
    database = int(os.getenv(const.REDIS_DATABASE_KEY, "0"))
    redis_singleton = RedisService(
        cluster_addr=addr, password=password, database=database
    )


def get_db_engine() -> Optional[DatabaseService]:
    """
    Get the global database service singleton instance.

    Returns:
        DatabaseService: The initialized database service instance
    """
    return data_base_singleton


def get_redis_engine() -> Optional[RedisService]:
    """
    Get the global Redis service singleton instance.

    Returns:
        RedisService: The initialized Redis service instance
    """
    return redis_singleton


# 原可执行服务/供应商示例已剥离；来源和范围见 import-manifest.json。
