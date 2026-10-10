import { RefInput } from '@/components/workflow/types/drawer';
import { ReactFlowNode } from '@/components/workflow/types/drawer';
import type { NodeDataType, NodeParameters } from '..';
import type { CSSProperties, FocusEventHandler, ReactNode } from 'react';

export interface ModelNodeParameters extends NodeParameters {
  llmId?: string | number;
  isThink?: boolean;
  modelId?: string;
  template?: string;
  systemTemplate?: string;
  maxTokens?: number | string;
  temperature?: number;
  topK?: number;
  reasonMode?: number;
}

export interface ExtractorParameterDetailProps {
  id: string;
  data: NodeDataType<ModelNodeParameters>;
}

export interface EndNodeParameters extends NodeParameters {
  outputMode?: number;
  reasoningTemplate?: string;
  template?: string;
  templateErrMsg?: string;
  streamOutput?: boolean;
}

export interface EndDetailProps {
  id: string;
  data: NodeDataType<EndNodeParameters>;
}

export interface IteratorDetailProps {
  id: string;
  data: NodeDataType;
}

export interface InputsProps {
  id: string;
  data: NodeDataType;
  allowAdd?: boolean;
  children?: ReactNode;
}

export interface FlowTemplateEditorProps {
  id: string;
  data: NodeDataType;
  value?: string;
  placeholder?: string;
  onBlur?: FocusEventHandler<HTMLDivElement>;
  onChange?: (value: string) => void;
  minHeight?: CSSProperties['minHeight'];
}

export interface TextNodeParameters extends NodeParameters {
  prompt?: string;
  templateErrMsg?: string;
  separator?: string;
  separatorErrMsg?: string;
}

export interface TextHandleDetailProps {
  id: string;
  data: NodeDataType<TextNodeParameters>;
}

export interface TextNodeConfig {
  id: string | number;
  uid: string | number;
  separator: string;
  comment?: string | null;
  [key: string]: unknown;
}

export type TextNodeConfigListChange = TextNodeConfig[] | ((current: TextNodeConfig[]) => TextNodeConfig[]);
export type TextNodeParameterKey = 'mode' | 'prompt' | 'separator';
export type ChangeTextNodeParameter = <Key extends TextNodeParameterKey>(key: Key, value: TextNodeParameters[Key]) => void;

export interface UseNodeDebuggerReturn {
  open: boolean;
  setOpen: (open: boolean) => void;
  refInputs: RefInput[];
  setRefInputs: (refInputs: RefInput[]) => void;
  handleNodeDebug: () => void;
  nodeDebugExect: (
    currentNode: ReactFlowNode,
    debuggerNode: ReactFlowNode
  ) => void;
  remarkStatus: 'show' | 'hide' | null;
  remarkClick: () => void;
  labelInputId: string;
}
