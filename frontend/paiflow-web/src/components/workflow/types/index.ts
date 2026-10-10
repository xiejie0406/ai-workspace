import { XYPosition } from 'reactflow';
import React from 'react';

export type NodeType = {
  id: string;
  type?: string;
  position: XYPosition;
  data: NodeDataType;
  selected?: boolean;
};

export type NodeParameters = {
  [key: string]: unknown;
  model?: string;
  domain?: string;
  appId?: string;
  uid?: string;
  flowId?: string;
  mode?: number;
  llmId?: string | number;
  isThink?: boolean;
  enableChatHistoryV2?: {
    isEnabled?: boolean;
    rounds?: number | null;
  };
  IterationStartNodeId?: string;
  toolDescription?: string;
  rpaDescription?: string;
  handlingEdge?: string;
  exceptionHandlingEdge?: string;
};

export type NodeDataType<Parameters extends object = NodeParameters> = {
  label?: string;
  labelEdit?: boolean;
  shrink?: boolean;
  parentId?: string;
  nodeType?: string;
  retryConfig?: {
    shouldRetry?: boolean;
    errorStrategy?: number;
    customOutput?: string;
  };
  nodeMeta?: {
    nodeType?: string;
    aliasName: string;
  };
  inputs?: Array<InputType>;
  outputs?: Array<OutputType>;
  nodeParam: Parameters & Record<string, unknown>;
  references?: Array<ReferenceType>;
};

export type ReferenceContent = {
  id?: string;
  nodeId?: string;
  name?: string;
};

export type InputValue = {
  type: string;
  content: string | ReferenceContent;
  contentErrMsg?: string;
};

// 原画布的对象属性同时使用直接 type/properties 与 schema 包装两种结构。
export type WorkflowProperty = {
  id?: string;
  name: string;
  type?: string;
  schema?: WorkflowSchema;
  properties?: WorkflowProperty[];
  default?: unknown;
  required?: boolean;
  nameErrMsg?: string;
  allowedFileType?: string[];
  fileType?: string;
  deleteDisabled?: boolean;
  customParameterType?: string;
  key?: string;
  isChild?: boolean;
  title?: React.ReactElement;
};

export type WorkflowSchema = {
  type: string;
  description?: string;
  descriptionErrMsg?: string;
  default?: unknown;
  properties?: WorkflowProperty[];
  value?: InputValue;
};

export type InputType = {
  id: string;
  name: string;
  schema: WorkflowSchema & { value: InputValue };
  type?: string;
  required?: boolean;
  description?: string;
  default?: unknown;
  disabled?: boolean;
  fileType?: string;
  customParameterType?: string;
  nameErrMsg?: string;
};

export type OutputType = WorkflowProperty & {
  id: string;
  schema: WorkflowSchema;
  fileType?: string;
  deleteDisabled?: boolean;
  customParameterType?: string;
  isChild?: boolean;
};

export type ReferenceType = {
  id?: string;
  label: string;
  value: string;
  originId?: string;
  type?: string;
  parentType?: string;
  parentNode?: boolean;
  fileType?: string;
  children?: ReferenceType[];
  references?: ReferenceType[];
};

// 模板输入的字面值只有 id/label；引用输入继续保留原递归变量信息。
export type TemplateVariableOption = Omit<ReferenceType, 'value' | 'children' | 'references'> & {
  value?: string;
  children?: TemplateVariableOption[];
  references?: TemplateVariableOption[];
};

export type TemplateReplaceOffset = { offsetLeft: number; offsetRight: number };

export interface UseFlowTemplateEditorProps {
  currentSelection: React.MutableRefObject<Range | null>;
  willInertInfo: React.MutableRefObject<{ cursorPosition: number; offset: TemplateReplaceOffset }>;
  setShowDropdown: React.Dispatch<React.SetStateAction<boolean>>;
  setDropdownPosition: React.Dispatch<React.SetStateAction<{ top: number; left: number }>>;
  setMatchingInformation: React.Dispatch<React.SetStateAction<{ keyWord: string; matchingKeyWord: string }>>;
  zoom: number;
  references: ReferenceType[];
  inputs: InputType[];
  treeData: TemplateVariableOption[];
  parentRef: React.RefObject<HTMLDivElement>;
}

export type sourceHandleType = {
  dataType: string;
  id: string;
  baseClasses: string[];
};

export type targetHandleType = {
  inputTypes?: string[];
  type: string;
  fieldName: string;
  id: string;
  proxy?: { field: string; id: string };
};

export type FlowType = {
  name?: string | undefined;
  flowId?: string;
  appId?: string;
  id?: string;
  data?: unknown;
  publishedData?: unknown;
  description?: string;
  updateTime?: unknown;
  style?: unknown;
  is_component?: boolean;
  parent?: string;
  date_created?: string;
  updated_at?: string;
  last_tested_version?: string;
  address: string;
  avatarIcon: string;
  status: number;
  color: string;
  edgeType: string;
  evalSetId?: string;
  evalPageFirstTime?: boolean;
  canPublish?: boolean;
  editing?: boolean;
  backgroundPic?: string;
  advancedConfig?: unknown;
};

export type ErrNodeType = {
  id: string;
  icon: string;
  name: string | undefined;
  errorMsg: string;
  childErrList: ErrNodeType[] | undefined;
  data?: {
    label: string;
  };
};

export type ConnectionLineProps = {
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  connectionLineStyle?: React.CSSProperties;
};

export type UseDropdownControlReturn = {
  handleKeyDown: (e: React.KeyboardEvent<HTMLDivElement>) => void;
  onKeyUp: (e: React.KeyboardEvent<HTMLDivElement>) => void;
  handleDropdownKeyDown: (e: React.KeyboardEvent<HTMLDivElement>) => void;
};

export type UseFlowTemplateEditorReturn = {
  insertOption: (option: string, isLeaf: boolean) => void;
  getCursorPosition: () => void;
  filterArr: (
    arr: TemplateVariableOption[],
    value: string,
    offset: TemplateReplaceOffset,
    content: string
  ) => TemplateVariableOption[];
  noProperties: boolean;
  hasData: boolean;
  inputsOption: TemplateVariableOption[];
};

export type UseFlowTemplateInputReturn = {
  handleClick: () => void;
  handleInput: (event?: React.FormEvent<HTMLDivElement>) => void;
  handleTreeSelect: (selectedKeys: string[]) => void;
};

// 导出 Drawer 相关类型
export * from './drawer';

// 导出 Hooks 相关类型
export * from './hooks';

// 导出 Modal 相关类型
export * from './modal';

// 导出 Nodes 相关类型
export * from './nodes';

// 导出 Components 相关类型
export * from './components';
