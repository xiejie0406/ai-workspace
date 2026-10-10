import { RpaInfo, RpaNodeParam } from '@/types/rpa';
import {
  AddNodeType,
  ToolType,
  FlowType,
  PositionType,
  NewNodeType,
} from '../drawer/chat-debugger';
import React from 'react';
import type {
  NodeDataType as WorkflowNodeData,
  InputType,
  OutputType,
  WorkflowProperty,
  WorkflowSchema,
  ReferenceType,
  NodeParameters,
} from '..';

// Hook 相关类型定义

// useFlowTypeRender Hook 相关类型
export interface ItemType {
  fileType?: string;
  type?: string;
  schema?: {
    type?: string;
    value?: {
      type?: string;
      content?: {
        name?: string;
      };
    };
  };
}

// useNodeCommon Hook 相关类型
export interface NodeCommonProps {
  id: string;
  data?: NodeDataType;
}

export interface UseNodeInfoReturn {
  nodeType: string;
  isStartNode: boolean;
  isIteratorStart: boolean;
  isEndNode: boolean;
  isIteratorEnd: boolean;
  isKnowledgeNode: boolean;
  isQuestionAnswerNode: boolean;
  isDecisionMakingNode: boolean;
  isIfElseNode: boolean;
  isIteratorNode: boolean;
  isIteratorChildNode: boolean | string | undefined;
  isAgentNode: boolean;
  isStartOrEndNode: boolean;
  isCodeNode: boolean;
  isDataBaseNode: boolean;
  showInputs: boolean;
  showOutputs: boolean;
  showExceptionFlow: boolean | undefined;
  references: ReferenceItem[];
  inputs: InputItem[];
  outputs: OutputType[];
  showNodeOperation: boolean;
  currentNode?: {
    id: string;
    type?: string;
    data?: NodeDataType;
    [key: string]: unknown;
  };
  nodeParam: NodeParameters;
  nodeIcon?: string;
  nodeDesciption?: string;
  isFixedInputsNode: boolean;
  isRpaNode: boolean;
}

export interface UseNodeFuncReturn {
  handleNodeClick: () => void;
  handleChangeNodeParam: <T>(
    fn: (data: NodeDataType, value: T) => void,
    value: T
  ) => void;
  handleChangeOutputParam: <T>(
    outputId: string,
    fn: (data: OutputItem, value: T, nodeData: NodeDataType) => void,
    value: T
  ) => void;
  handleIteratorEndChange: (
    type: 'add' | 'remove' | 'replace',
    outputId: string,
    value?: unknown,
    currentNode?: { data: NodeDataType }
  ) => void;
  handleAddOutputLine: () => void;
  handleRemoveOutputLine: (outputId: string) => void;
  isFixedOutputComponentFunc: (output: OutputItem) => boolean;
}

export interface UseNodeOutputRenderReturn {
  handleCustomOutputGenerate: () => void;
  renderOutputComponent: (output: OutputItem) => React.ReactElement;
  outputTypeList: Array<{
    label: string;
    value: string;
    children?: Array<{
      label: string;
      value: string;
    }>;
  }>;
}

export interface WorkflowModelSummary {
  llmId?: string | number;
  [key: string]: unknown;
}

export interface UseNodeModelsReturn {
  models: WorkflowModelSummary[];
  model?: WorkflowModelSummary;
  isThinkModel: boolean | undefined;
}

export type NodeDataType = WorkflowNodeData;
export type InputItem = InputType;
// 对象叶子沿用原直接 type/default 形状，不强制具备顶层输出的 schema/id。
export type OutputItem = WorkflowProperty;
export type PropertyItem = WorkflowProperty;
export type ReferenceItem = ReferenceType;
export type SchemaType = WorkflowSchema;
export type RetryConfig = NonNullable<WorkflowNodeData['retryConfig']>;

export interface UseNodeCommonReturn {
  isFixedInputsNode: boolean;
  allowNoInputParams: boolean;
  handleNodeClick: () => void;
  handleChangeNodeParam: <T>(
    fn: (data: NodeDataType, value: T) => void,
    value: T
  ) => void;
  handleChangeInputParam: <T>(
    inputId: string,
    fn: (data: InputItem, value: T) => void,
    value: T
  ) => void;
  handleChangeOutputParam: <T>(
    outputId: string,
    fn: (data: OutputItem, value: T, nodeData: NodeDataType) => void,
    value: T
  ) => void;
  handleAddOutputLine: () => void;
  handleRemoveOutputLine: (outputId: string) => void;
  handleCustomOutputGenerate: () => void;
  titleRender: (nodeData: {
    name: string;
    schema?: SchemaType;
    type?: string;
  }) => React.ReactElement;
  renderTypeInput: (output: OutputItem) => React.ReactElement;
  addUniqueComponentToProperties: (schemasArray: OutputType[]) => OutputType[];
  renderTypeOneClickUpdate: () => React.ReactElement | null;
  handleAddInputLine: () => void;
  handleRemoveInputLine: (inputId: string) => void;
  nodeType: string;
  isConnectable: boolean;
  nodeParam: NodeParameters;
  canvasesDisabled: boolean;
  isStartNode: boolean;
  isEndNode: boolean;
  hasTargetHandle: boolean;
  hasSourceHandle: boolean;
  sourceHandleId?: string;
  exceptionHandleId?: string;
  model?: WorkflowModelSummary;
  nodeIcon?: string;
  nodeDesciption?: string;
  isIteratorStart: boolean;
  isIteratorEnd: boolean;
  isKnowledgeNode: boolean;
  isQuestionAnswerNode: boolean;
  isDecisionMakingNode: boolean;
  isIfElseNode: boolean;
  isIteratorNode: boolean;
  isIteratorChildNode: boolean | string | undefined;
  isAgentNode: boolean;
  isStartOrEndNode: boolean;
  isRpaNode?: boolean;
  isCodeNode: boolean;
  showInputs: boolean;
  showOutputs: boolean;
  showExceptionFlow: boolean | undefined;
  references: ReferenceItem[];
  inputs: InputItem[];
  outputs: OutputType[];
  showNodeOperation: boolean;
  currentNode?: {
    id: string;
    type?: string;
    data?: NodeDataType;
    [key: string]: unknown;
  };
  models: WorkflowModelSummary[];
  outputTypeList: Array<{
    label: string;
    value: string;
    children?: Array<{
      label: string;
      value: string;
    }>;
  }>;
  isThinkModel: boolean | undefined;
}

export interface UseFlowCommonReturn {
  startWorkflowKeydownEvent: boolean;
  handleAddNode: (
    addNode: AddNodeType,
    position: PositionType
  ) => NewNodeType[] | null;
  handleAddToolNode: (tool: ToolType) => void;
  handleAddFlowNode: (flow: FlowType) => void;
  handleAddRpaNode: (rpa: RpaNodeParam) => void;
  handleEdgeAddNode: (
    addNode: AddNodeType,
    position: PositionType,
    sourceHandle: string | null,
    currentNode: NewNodeType
  ) => void;
  handleDebugger: () => void;
  resetBeforeAndWillNode: () => void;
}

export interface UseNodeHandleReturn {
  isConnectable: boolean;
  hasSourceHandle: boolean;
  hasTargetHandle: boolean;
  sourceHandleId?: string;
  exceptionHandleId?: string;
}

export interface UseNodeInputRenderReturn {
  allowNoInputParams: boolean;
  renderTypeInput: (output: OutputItem) => React.ReactElement;
  handleChangeInputParam: <T>(
    inputId: string,
    fn: (data: InputItem, value: T) => void,
    value: T
  ) => void;
  handleAddInputLine: () => void;
  handleRemoveInputLine: (inputId: string) => void;
}

export interface UseVariableMemoryHandlersReturn {
  updateVariableMemoryNodeRef: () => void;
  handleChangeParam: (
    outputId: string,
    fn: (data: InputItem, value: unknown) => void,
    value: unknown
  ) => void;
  handleRemoveInputLine: (inputId: string) => void;
}

export interface UseAddNodeReturn {
  handleAddNode: (
    addNode: AddNodeType,
    position: PositionType
  ) => NewNodeType[] | null;
}

export interface UseAddToolNodeReturn {
  handleAddToolNode: (tool: ToolType) => void;
}

export interface UseAddFlowNodeReturn {
  handleAddFlowNode: (flow: FlowType) => void;
}

export interface UseAddRpaNodeReturn {
  handleAddRpaNode: (rpa: RpaNodeParam) => void;
}

// 重新导出常用的类型以便在hooks中使用
export type {
  AddNodeType,
  ToolType,
  FlowType,
  PositionType,
  NewNodeType,
} from '../drawer/chat-debugger';
