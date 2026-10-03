import type { KeyboardEvent } from 'react';
import type { ChatInfoType, ChatListItem, InterruptChatType, ReactFlowNode, ReactFlowEdge, StartNodeType } from '../../drawer/chat-debugger';
import type { WorkflowDebugContext } from '@/utils/workflow-debug';

export type ChatChange<T> = T | ((previous: T) => T);
export interface ChatState {
  userInput: string;
  chatList: ChatListItem[];
  chatInfoRef: ChatInfoType;
  messageNodeTextQueue: string;
  endNodeReasoningTextQueue: string;
  endNodeTextQueue: string;
  wsMessageStatus: string;
  preRunningNodeIds: string[];
  currentFollowNodeId: string;
  versionId: string;
  startNodeParams: StartNodeType[];
  buildPassRef: boolean;
  debuggering: boolean;
  interruptChat: InterruptChatType;
  suggestLoading: boolean;
  suggestProblem: string[];
  userWheel: boolean;
  deleteAllModal: boolean;
  chatIdRef: string;
  debugContext: WorkflowDebugContext | undefined;
  debugBuildKey: string;
}
export interface ChatStoreType extends ChatState {
  setChatList(change: ChatChange<ChatListItem[]>): void;
  setStartNodeParams(change: ChatChange<StartNodeType[]>): void;
  setInterruptChat(change: ChatChange<InterruptChatType>): void;
  setSuggestLoading(change: ChatChange<boolean>): void;
  setSuggestProblem(change: ChatChange<string[]>): void;
  setUserWheel(change: ChatChange<boolean>): void;
  setDebuggering(change: ChatChange<boolean>): void;
  setDeleteAllModal(change: ChatChange<boolean>): void;
  getDialogues(id: string, shouldAddDivider?: boolean): void;
  clearNodeStatus(): void;
  handleSaveDialogue(): void;
  handleResumeChat(content: string): void;
  handleRunDebugger(nodes: ReactFlowNode[], edges: ReactFlowEdge[], inputs: StartNodeType[], regen?: boolean): void;
  clearData(setOpen: (open: boolean) => void): void;
  handleEnterKey(event: KeyboardEvent<HTMLInputElement>): void;
  handleStopConversation(): void;
  deleteAllChat(): void;
  handleWorkflowDeleteComparisons(): void;
  canRunDebugger(): boolean;
  setWsMessageStatus(status: string): void;
  resetNodesAndEdges(): { nodes: ReactFlowNode[]; edges: ReactFlowEdge[] };
  setQueue(number: number): void;
  setUserInput(value: string): void;
  getTextQueueContent(): string;
  isChatEnd(): boolean;
  getChatKey(): string;
}
