"""
Global Variables Node Module

This module provides functionality for managing global variables in workflow execution.
It includes a VariablesManage class for cache operations and a GlobalVariablesNode class
that extends BaseNode to handle global variable operations (set/get) in workflow nodes.
"""

import json
from typing import Any, Literal

from pydantic import Field

from workflow.engine.entities.variable_pool import ParamKey, VariablePool
from workflow.engine.nodes.base_node import BaseNode
from workflow.engine.nodes.entities.node_run_result import (
    NodeRunResult,
    WorkflowNodeExecutionStatus,
)
from workflow.exception.e import CustomException
from workflow.exception.errors.err_code import CodeEnum
from workflow.extensions.otlp.log_trace.node_log import NodeLog
from workflow.extensions.otlp.trace.span import Span

# Cache expiration time for parameters (7 days in seconds)
PARAMETER_EXPIRE_TIME_S = 60 * 60 * 24 * 7

# Variable pool identifier constant
VARIABLE_POOL = "VARIABLE_POOL"

# Redis key prefix for variable pool storage
VARIABLE_POOL_PREFIX = "sparkflowV2:variable_pool"


from worker.variables import VariablesManage


class GlobalVariablesNode(BaseNode):
    """
    Workflow node for managing global variables.

    This node extends BaseNode to provide functionality for setting and getting
    global variables that can be shared across workflow execution contexts.
    Supports both 'set' and 'get' operations for variable management.
    """

    method: Literal["set", "get"] = Field(...)  # Operation method: "set" or "get"

    async def async_execute(
        self,
        variable_pool: VariablePool,
        span: Span,
        event_log_node_trace: NodeLog | None = None,
        **kwargs: Any,
    ) -> NodeRunResult:
        """
        Asynchronous execution method for global variable operations.

        Handles both 'set' and 'get' operations:
        - 'set': Stores input variables as global variables in cache
        - 'get': Retrieves global variables and outputs them

        :param variable_pool: Variable pool containing workflow variables
        :param span: Tracing span for monitoring and logging
        :param event_log_node_trace: Optional node logging trace
        :param kwargs: Additional keyword arguments
        :return: NodeRunResult with execution status and data
        """
        try:
            inputs: dict = {}
            outputs: dict = {}
            flow_id: str = variable_pool.system_params.get(ParamKey.FlowId)
            # Build Redis key components for cache operations
            redis_key = {
                "flow_id": flow_id,
                "uid": span.uid,
                "app_id": span.app_id,
                "chat_id": variable_pool.chat_id,
            }
            span.add_info_events(
                {"redis_key": json.dumps(redis_key, ensure_ascii=False)}
            )

            # Initialize variable manager for cache operations
            var_manager = VariablesManage(
                flow_id=flow_id,
                uid=span.uid,
                app_id=span.app_id,
                chat_id=variable_pool.chat_id,
                node_id=self.node_id,
            )
            # Handle 'set' operation: store input variables as global variables
            if self.method == "set":
                for key in self.input_identifier:
                    inputs[key] = variable_pool.get_variable(
                        node_id=self.node_id, key_name=key, span=span
                    )
                    await var_manager.add_variable(key, inputs[key])
                span.add_info_events({"set": json.dumps(inputs, ensure_ascii=False)})

            # Handle 'get' operation: retrieve global variables
            elif self.method == "get":
                global_vars = await var_manager.get_all_variables()
                for key in self.output_identifier:
                    if key in global_vars:
                        # Use global variable if available
                        outputs.update({key: global_vars.get(key)})
                    else:
                        # Fallback to local variable pool if global variable not found
                        outputs.update(
                            {
                                key: variable_pool.get_variable(
                                    node_id=self.node_id, key_name=key, span=span
                                )
                            }
                        )
                span.add_info_events({"get": json.dumps(outputs, ensure_ascii=False)})
            # Order outputs according to output_identifier sequence
            order_outputs = {}
            for output in self.output_identifier:
                if output in outputs:
                    order_outputs.update({output: outputs.get(output)})
                else:
                    # Fallback to variable pool if output not in processed outputs
                    order_outputs.update(
                        {
                            output: variable_pool.get_variable(
                                node_id=self.node_id, key_name=output, span=span
                            )
                        }
                    )

            # Return successful execution result
            return NodeRunResult(
                status=WorkflowNodeExecutionStatus.SUCCEEDED,
                inputs=inputs,
                outputs=order_outputs,
                node_id=self.node_id,
                node_type=self.node_type,
                alias_name=self.alias_name,
            )
        except Exception as err:
            # Record exception in tracing span and return failed result
            span.record_exception(err)
            return NodeRunResult(
                status=WorkflowNodeExecutionStatus.FAILED,
                error=CustomException(
                    CodeEnum.VARIABLE_NODE_EXECUTION_ERROR,
                    cause_error=err,
                ),
                node_id=self.node_id,
                alias_name=self.alias_name,
                node_type=self.node_type,
            )
