// Code Node 相关类型定义
import type { NodeDataType } from '..';

export interface CodeDetailProps {
  id: string;
  data: CodeNodeData;
}

export type CodeNodeData = NodeDataType<CodeNodeParam>;

export interface CodeNodeParam {
  code?: string;
  codeErrMsg?: string;
}
