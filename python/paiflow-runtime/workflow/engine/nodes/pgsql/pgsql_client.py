import json
import os
import time
from typing import Any, Dict

from workflow.consts.database import ExecuteEnv
from workflow.exception.e import CustomException
from workflow.exception.errors.err_code import CodeEnum
from workflow.extensions.otlp.trace.span import Span


class PGSqlConfig:
    """Configuration class for PostgreSQL database operations.

    This class holds all the necessary configuration parameters required
    for executing database operations through the PostgreSQL service.
    """

    def __init__(
        self,
        appId: str,
        apiKey: str,
        database_id: int,
        uid: str,
        spaceId: str,
        dml: str,
        env: str = ExecuteEnv.TEXT.value,
    ):
        """Initialize PostgreSQL configuration.

        :param appId: Application identifier for authentication
        :param apiKey: API key for service authentication
        :param database_id: Unique identifier of the target database
        :param uid: User identifier for the operation
        :param spaceId: Workspace or space identifier
        :param dml: Data Manipulation Language statement to execute
        :param env: Execution environment (defaults to TEXT environment)
        """
        self.appId = appId
        self.apiKey = apiKey
        self.database_id = database_id
        self.uid = uid
        self.spaceId = spaceId
        self.dml = dml
        self.env = env
        self.url = ""


class PGSqlClient:
    """Client for executing PostgreSQL database operations.

    This client handles the communication with the PostgreSQL service,
    including authentication, request formatting, and response processing.
    """

    def __init__(self, *, config: PGSqlConfig):
        """Initialize the PostgreSQL client.

        :param config: Configuration object containing database connection parameters
        """
        self.config = config

    async def exec_dml(self, span: Span) -> Dict[str, Any]:
        """Execute Data Manipulation Language (DML) statement.

        Sends a POST request to the PostgreSQL service with the configured
        DML statement and returns the execution result.

        :param span: Tracing span for monitoring and logging
        :return: Dictionary containing the execution result and response data
        :raises CustomException: If environment variable is not set or request fails
        """
        from worker.errors import WorkerError
        raise WorkerError("RESOURCE_UNAVAILABLE", "旧PGSQL HTTP/任意DML入口已封禁，须用中心结构化数据能力")

    def payload(self) -> Dict[str, Any]:
        """Construct the request payload for database operations.

        Builds a dictionary containing all necessary parameters for
        the PostgreSQL service request.

        :return: Dictionary containing request parameters
        """

        # Build payload with required database operation parameters
        _payload = {
            "app_id": self.config.appId,
            "database_id": self.config.database_id,
            "uid": self.config.uid,
            "dml": self.config.dml,
            "env": self.config.env,
        }

        return _payload
