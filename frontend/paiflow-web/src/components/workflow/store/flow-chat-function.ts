import React from 'react';
import type { ChatChange, ChatState } from '../types/zustand/chat';
import { ChatListItem } from '../types';
import { ReactFlowNode, ReactFlowEdge } from '../types';
import {
  getDialogueAPI,
  saveDialogueAPI,
  getInputsType,
  addComparisons,
  buildFlowAPI,
  workflowDialogClear,
  workflowDeleteComparisons,
} from '@/services/flow';
import { nextQuestionAdvice } from '@/services/common';
import { v4 as uuid } from 'uuid';
import { moveToPosition } from './flow-function';
import {
  ChatInfoType,
  WebSocketMessageData,
  FlowResultType,
  StartNodeType,
  InterruptChatType,
} from '../types';
import {
  generateDefaultInput,
  generateInputsAndOutputsOrder,
} from '../utils/reactflowUtils';
import useFlowsManager from './use-flows-manager';
import useFlowStore from './use-flow-store';
import { isJSON } from '@/utils';
import i18n from 'i18next';
import { cloneDeep } from 'lodash';
import { getFixedUrl, getAuthorization } from '@/components/workflow/utils';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { message } from 'antd';
import useSpaceStore from '@/store/space-store';
import { WorkflowDebugContext, WorkflowDebugProtocolError, readDebugReceipt, type DebugReceipt } from '@/utils/workflow-debug';

const initInterruptChat: InterruptChatType = {
  eventId: '',
  interrupt: false,
  nodeId: '',
  type: '',
  option: null,
  needReply: true,
};

const initChatInfo: ChatInfoType = {
  question: [],
  answer: {
    messageContent: '',
    reasoningContent: '',
    content: '',
  },
  answerItem: '',
  option: null,
};

export const initialStatus: ChatState = {
  userInput: '',
  chatList: [],
  chatInfoRef: cloneDeep(initChatInfo),
  messageNodeTextQueue: '',
  endNodeReasoningTextQueue: '',
  endNodeTextQueue: '',
  wsMessageStatus: '',
  preRunningNodeIds: [],
  currentFollowNodeId: '',
  versionId: '',
  startNodeParams: [],
  buildPassRef: false,
  debuggering: false,
  interruptChat: initInterruptChat,
  suggestLoading: false,
  suggestProblem: [],
  userWheel: false,
  deleteAllModal: false,
  chatIdRef: uuid().replace(/-/g, ''),
  debugContext: undefined as WorkflowDebugContext | undefined,
  debugBuildKey: '',
};

const debugScope = (): string => {
  const space = useSpaceStore.getState();
  const manager = useFlowsManager.getState();
  const history = manager.historyVersionData;
  const rawId = history !== null && typeof history === 'object' && !Array.isArray(history)
    ? (history as Record<string, unknown>).id : undefined;
  const selectedId = typeof rawId === 'number' && Number.isSafeInteger(rawId) && rawId > 0 ? String(rawId)
    : typeof rawId === 'string' && /^[1-9]\d{0,18}$/.test(rawId) && BigInt(rawId) <= 9223372036854775807n ? rawId : undefined;
  if (manager.historyVersion && !selectedId) return '';
  return JSON.stringify([getAuthorization(), space.spaceId || '', space.spaceType, space.enterpriseId || '',
    manager.currentFlow?.id, manager.currentFlow?.flowId, !!manager.historyVersion, manager.historyVersion ? selectedId : null]);
};
const debugHeaders = (): Record<string, string> => ({
  'Content-Type': 'application/json', Authorization: getAuthorization(),
  'space-id': useSpaceStore.getState().spaceId || '',
});
const debugFailure = (): void => { message.error('调试连接或停止请求未确认，请重新连接当前运行'); };

const readDebugStream = async (context: WorkflowDebugContext, path: string, body: string,
  nodes: ReactFlowNode[], edges: ReactFlowEdge[], get: () => any,
  set: ((value: Record<string, unknown>) => void) | undefined): Promise<void> => {
  const signal = context.nextStream();
  for (let attempt = 0; attempt < 4 && context.alive() && !signal.aborted; attempt++) {
    try {
      await fetchEventSource(getFixedUrl(path), {
        method: 'POST', headers: { ...debugHeaders(), ...(context.lastEventId ? { 'Last-Event-ID': context.lastEventId } : {}) },
        body, signal, openWhenHidden: true,
        async onopen(response) {
          if (signal.aborted || !context.alive()) throw new Error('读取已断开');
          context.open(response);
          if (path === '/workflow/resume') get().setInterruptChat({ ...initInterruptChat });
        },
        onerror(error) { throw error; }, // 原库在abort后仍会排重试timer，必须阻断其隐式重发。
        onmessage(event) {
          if (!context.alive() || signal.aborted) return;
          if (!event.data && !event.id) return; // SSE注释/心跳没有业务帧。
          if (context.frame(event.id, event.data)) handleMessage(nodes, edges, event, get, set);
        },
      });
    } catch (error) {
      if (!context.alive() || signal.aborted) return;
      if (error instanceof WorkflowDebugProtocolError) { context.detach(); debugFailure(); return; }
    }
    if (!context.alive() || signal.aborted || context.waiting) return;
    // 只有已确认同Run的连接可续读；固定body/key与游标不随重连重建。
    if (!context.runId || attempt === 3) { debugFailure(); return; }
    path = '/workflow/chat'; body = context.chatBody;
    await new Promise<void>(resolve => {
      const timer = setTimeout(resolve, 500);
      signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
    });
  }
};

const releaseDebugView = (set: (value: Record<string, unknown>) => void): void => {
  // 断开只释放本地视图；同次撤销构建标志，避免原抽屉effect把它当成结束后保存。
  set({
    debugContext: undefined,
    debugBuildKey: '',
    debuggering: false,
    buildPassRef: false,
    interruptChat: { ...initInterruptChat },
    wsMessageStatus: '',
    messageNodeTextQueue: '',
    endNodeReasoningTextQueue: '',
    endNodeTextQueue: '',
    preRunningNodeIds: [],
    currentFollowNodeId: '',
    versionId: '',
  });
  const manager = useFlowsManager.getState();
  manager.setFlowResult({ status: '', timeCost: '', totalTokens: '' });
  manager.setShowNodeList(true);
  manager.setCanvasesDisabled(!!manager.historyVersion);
};
const watchDebugContext = (context: WorkflowDebugContext, get: () => any,
  set: (value: Record<string, unknown>) => void): void => {
  context.addCleanup(() => {
    if (!context.terminal && get().debugContext === context) releaseDebugView(set);
  });
  context.addCleanup(useSpaceStore.subscribe(() => { context.alive(); }));
  context.addCleanup(useFlowsManager.subscribe(() => { context.alive(); }));
  const detach = (): void => context.detach();
  const changed = (): void => { context.alive(); };
  window.addEventListener('pagehide', detach);
  window.addEventListener('storage', changed);
  context.addCleanup(() => { window.removeEventListener('pagehide', detach); window.removeEventListener('storage', changed); });
};

const getDialogues = (id: string, set, shouldAddDivider = false): void => {
  getDialogueAPI(id, 1).then((data: unknown[]) => {
    const chatList: ChatListItem[] = [];
    let chatId = data?.[0]?.chatId || null;
    data?.forEach(chat => {
      const currentChatId = chat?.chatId;
      if (currentChatId !== chatId) {
        chatList.unshift({
          id: uuid(),
          type: 'divider',
        });
      }

      chatList.unshift({
        ...chat,
        id: chat?.id,
        type: 'answer',
        messageContent: JSON.parse(chat?.answer)?.messageContent || '',
        reasoningContent: JSON.parse(chat?.answer)?.reasoningContent || '',
        content: JSON.parse(chat?.answer)?.content || '',
        option: JSON.parse(chat?.answer)?.option,
      });
      chatList.unshift({
        ...chat,
        id: uuid(),
        type: 'ask',
        inputs: JSON.parse(chat?.question),
      });
      chatId = currentChatId;
    });
    if (shouldAddDivider && data.length !== 0) {
      chatList.push({
        id: uuid(),
        type: 'divider',
      });
    }
    set({
      chatList,
    });
  });
};
const handleMoveToPosition = (id: string, nodes: ReactFlowNode[]): void => {
  const currentNode = nodes.find((node: ReactFlowNode) => node.id === id);
  const zoom = 0.8;
  const xPos = currentNode?.position?.x || 0;
  const yPos = currentNode?.position?.y || 0;
  moveToPosition({ x: -xPos * zoom + 200, y: -yPos * zoom + 200, zoom });
};
const pushAskToChatList = (inputs, nodes, nodeId, get): void => {
  get().setChatList(chatList => {
    const newInputs = cloneDeep(inputs) || cloneDeep(get().startNodeParams);
    const askParams: ChatListItem = {
      id: uuid(),
      type: 'ask',
      inputs: newInputs,
    };
    get().chatInfoRef.question = newInputs;
    chatList.push(askParams);
    return [...chatList];
  });
  handleMoveToPosition(nodeId, nodes);
};
const pushAnswerToChatList = (get): unknown => {
  get().setChatList(chatList => {
    const answerParams: ChatListItem = {
      id: uuid(),
      type: 'answer',
      messageContent: '',
      content: '',
      reasoningContent: '',
    };
    chatList.push(answerParams);
    return [...chatList];
  });
};
const pushContentToAnswer = (key, content, get): void => {
  get()[key] = get()[key] + content;
};
const clearNodeStatus = (get): void => {
  if (get().userInput) {
    get().setUserInput('');
  }
  //@ts-ignore
  get().setStartNodeParams(startNodeParams =>
    startNodeParams?.map(input => ({
      ...input,
      errorMsg: input?.originErrorMsg,
      default: input?.fileType
        ? []
        : input?.type === 'object'
          ? '{}'
          : input?.type?.includes('array')
            ? '[]'
            : generateDefaultInput(input?.type),
    }))
  );
};
const handleSaveDialogue = (get, set): void => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  const params = {
    chatId: get().chatIdRef,
    type: 1,
    workflowId: currentFlow?.id,
    sid: get().chatInfoRef?.sid,
    questionItem: JSON.stringify(get().chatInfoRef?.question),
    answerItem: JSON.stringify(get().chatInfoRef?.answerItem),
    question: JSON.stringify(get().chatInfoRef?.question),
    answer: JSON.stringify(get().chatInfoRef?.answer),
  };
  saveDialogueAPI(params).then(
    () => currentFlow?.id && getDialogues(currentFlow.id, set)
  );
  get().chatInfoRef = cloneDeep(initChatInfo);
};
const handleAuditFailed = (data, get): void => {
  get().messageNodeTextQueue = '';
  get().endNodeReasoningTextQueue = '';
  get().endNodeTextQueue = '';
  get().setChatList(chatList => {
    get().chatInfoRef.answer = {
      messageContent: '',
      reasoningContent: '',
      content: data?.message,
    };
    chatList[chatList.length - 1].messageContent = '';
    chatList[chatList.length - 1].reasoningContent = '';
    chatList[chatList.length - 1].content = data?.message;
    return [...chatList];
  });
  // 节点错误仍展示原审查提示，运行终态只由中心固定终帧结束。
};
const handleInterrupt = ({
  data,
  nodes,
  edges,
  nodeId,
  nodeStatus,
  responseResult,
  get,
}): void => {
  const content = data?.['event_data']?.value?.content;
  handleNodeStatusChange({
    nodes,
    edges,
    nodeId,
    nodeStatus,
    responseResult,
    get,
  });
  pushContentToAnswer('endNodeTextQueue', content, get);
  get().wsMessageStatus = 'end';
  get().setInterruptChat({
    interrupt: true,
    eventId: data?.['event_data']?.event_id || '',
    nodeId: nodeId || '',
    option:
      data?.['event_data']?.value?.option?.filter(
        item => item.id !== 'default'
      ) || null,
    type: data?.['event_data']?.value?.type || '',
    needReply: data?.['event_data']?.need_reply || false,
    checkpointRevision: get().debugContext?.checkpoint?.revision,
    checkpointState: get().debugContext?.checkpoint?.state,
    replyKey: get().debugContext?.checkpoint?.replyKey,
  });
  get().chatInfoRef.answer.option = data?.['event_data']?.value?.option?.filter(
    item => item.id !== 'default'
  );
};
const handleFlowStop = (data, get): void => {
  if (data.code !== 0) {
    pushContentToAnswer('endNodeTextQueue', data?.message, get);
  }
  handleMessageEnd(data, get);
};
const extractNodeInfo = (data): unknown => {
  const flowResult = data.choices?.[0]?.['finish_reason'];
  const node = data?.['workflow_step']?.node;
  const nodeId = node?.id;
  const nodeStatus = node?.['finish_reason'];
  const content = data.choices?.[0]?.delta?.content;
  const responseResult = {
    timeCost: node?.['executed_time'],
    tokenCost: node?.usage?.['total_tokens'],
    inputs: node?.inputs,
    outputs: node?.outputs,
    errorOutputs: node?.['error_outputs'],
    rawOutput: node?.ext?.['raw_output'],
    nodeAnswerContent: content,
    reasoningContent: data?.choices?.[0]?.delta?.['reasoning_content'] || '',
    status: data?.code === 0 ? 'success' : 'failed',
    failedReason: data?.message,
    answerMode: node?.id?.startsWith('message')
      ? 1
      : node?.ext?.['answer_mode'],
  };
  return {
    flowResult,
    nodeId,
    nodeStatus,
    responseResult,
  };
};
const handleAnswerContent = (nodeId, responseResult, get): void => {
  if (nodeId?.startsWith('node-end') && responseResult?.reasoningContent) {
    pushContentToAnswer(
      'endNodeReasoningTextQueue',
      responseResult?.reasoningContent,
      get
    );
  }
  if (nodeId?.startsWith('message')) {
    pushContentToAnswer(
      'messageNodeTextQueue',
      responseResult?.nodeAnswerContent,
      get
    );
  }
  if (nodeId?.startsWith('node-end')) {
    pushContentToAnswer(
      'endNodeTextQueue',
      responseResult?.nodeAnswerContent,
      get
    );
  }
};
const updateAnswerItem = (nodeId, responseResult, get): void => {
  if (nodeId?.startsWith('node-end') || nodeId?.startsWith('message')) {
    get().chatInfoRef.answerItem =
      get().chatInfoRef.answerItem + responseResult?.nodeAnswerContent;
  }
};
const handleRunningNode = (currentNode, responseResult, get): void => {
  currentNode.data.status = 'running';
  const beforeContent = currentNode?.data?.debuggerResult?.done
    ? ''
    : (currentNode?.data?.debuggerResult?.answerContent ?? '');
  const beforeReasoningContent = currentNode?.data?.debuggerResult?.done
    ? ''
    : (currentNode?.data?.debuggerResult?.reasoningContent ?? '');
  currentNode.data.debuggerResult = {
    answerMode: responseResult?.answerMode,
    answerContent: beforeContent + responseResult?.nodeAnswerContent,
    reasoningContent: beforeReasoningContent + responseResult?.reasoningContent,
    done: false,
  };
};
const handleFinishedNode = (nodeId, currentNode, responseResult, get): void => {
  const beforeContent = currentNode?.data?.debuggerResult?.answerContent ?? '';
  const beforeReasoningContent =
    currentNode?.data?.debuggerResult?.reasoningContent ?? '';
  currentNode.data.debuggerResult = {
    timeCost: responseResult?.timeCost || undefined,
    tokenCost: responseResult?.timeCost?.totalTokens || undefined,
    input: generateInputsAndOutputsOrder(
      currentNode,
      responseResult.inputs,
      'inputs'
    ),
    rawOutput: responseResult?.rawOutput,
    output: generateInputsAndOutputsOrder(
      currentNode,
      responseResult.outputs,
      'outputs'
    ),
    errorOutputs: responseResult?.errorOutputs,
    failedReason: '',
    answerContent: beforeContent + responseResult?.nodeAnswerContent,
    reasoningContent: beforeReasoningContent + responseResult?.reasoningContent,
    answerMode: responseResult?.answerMode,
    done: true,
  };
  if (responseResult.status === 'success') {
    currentNode.data.status = 'success';
  } else {
    currentNode.data.status = 'failed';
    currentNode.data.debuggerResult.failedReason = responseResult?.failedReason;
  }
  if (nodeId?.startsWith('node-end') && responseResult?.answerMode === 0) {
    pushContentToAnswer(
      'endNodeTextQueue',
      JSON.stringify(responseResult?.outputs),
      get
    );
    get().chatInfoRef.answerItem = JSON.stringify(responseResult?.outputs);
  }
  if (nodeId?.startsWith('message')) {
    pushContentToAnswer('messageNodeTextQueue', '\n', get);
  }
};
const handleNodeStatusChange = ({
  nodes,
  edges,
  nodeId,
  nodeStatus,
  responseResult,
  get,
}): void => {
  const setEdges = useFlowStore.getState().setEdges;
  const setNode = useFlowStore.getState().setNode;
  const currentNode = nodes.find(node => node.id === nodeId);
  const autonomousMode = useFlowsManager.getState().autonomousMode;
  handleAnswerContent(nodeId, responseResult, get);
  updateAnswerItem(nodeId, responseResult, get);
  if (nodeStatus === 'ing' || nodeStatus === 'interrupt') {
    handleRunningNode(currentNode, responseResult, get);
  } else {
    handleFinishedNode(nodeId, currentNode, responseResult, get);
  }
  if (get().preRunningNodeIds?.length > 0) {
    const sourceNodesId = edges
      ?.filter(edge => edge?.target === currentNode?.id)
      ?.map(edge => edge?.source);
    const set2 = new Set(sourceNodesId);
    const intersectionIds = get().preRunningNodeIds.filter(value =>
      set2.has(value)
    );
    setEdges(edges => {
      edges.forEach(edge => {
        if (
          edge.target === currentNode?.id &&
          intersectionIds?.includes(edge.source)
        ) {
          edge.animated = true;
          edge.style = {
            stroke: '#275EFF',
            strokeWidth: 2,
            strokeDasharray: '5 5',
          };
        }
      });
      return cloneDeep(edges);
    });
  }
  setNode(nodeId, cloneDeep(currentNode));
  get().preRunningNodeIds.push(currentNode?.id);
  //跟随模式下需要根据节点移动画布
  if (currentNode?.id?.startsWith('node-start')) {
    get().currentFollowNodeId = currentNode?.id;
  }
  if (!autonomousMode) {
    const shouldMoveNode = edges?.some(
      edge =>
        edge?.source === get().currentFollowNodeId &&
        edge?.target === currentNode?.id
    );
    if (shouldMoveNode && currentNode?.id) {
      handleMoveToPosition(currentNode?.id, nodes);
      get().currentFollowNodeId = currentNode?.id;
    }
  }
};
const handleMessage = (
  nodes: ReactFlowNode[],
  edges: ReactFlowEdge[],
  e: MessageEvent,
  get,
  set
): void => {
  if (!e.data || !isJSON(e.data)) return;
  const data: WebSocketMessageData = JSON.parse(e.data);
  const { flowResult, nodeId, nodeStatus, responseResult } =
    extractNodeInfo(data);
  get().chatInfoRef.sid = data?.id;
  if (data?.code === 21103) {
    handleAuditFailed(data, get);
    return;
  } else if (flowResult === null && nodeId !== 'flow_obj') {
    handleNodeStatusChange({
      nodes,
      edges,
      nodeId,
      nodeStatus: nodeStatus === null ? 'ing' : nodeStatus,
      responseResult,
      get,
    });
  } else if (flowResult === 'interrupt') {
    handleInterrupt({
      data,
      nodes,
      edges,
      nodeId,
      nodeStatus,
      responseResult,
      get,
    });
  } else if (flowResult === 'stop') {
    handleFlowStop(data, get);
  }
};
const handleRunningNodeStatus = (): void => {
  const setNodes = useFlowStore.getState().setNodes;
  setNodes(nodes => {
    nodes.forEach(node => {
      if (node?.data?.status === 'running') {
        node.data.debuggerResult.cancelReason = i18n.t(
          'workflow.nodes.chatDebugger.workflowTerminated'
        );
        node.data.status = 'cancel';
      }
    });
    return cloneDeep(nodes);
  });
};
const handleSynchronizeDataToXfyun = (): void => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  const botId = isJSON(currentFlow?.ext)
    ? JSON.parse(currentFlow?.ext)?.botId
    : '';
  const params = {
    botId,
  };
  getInputsType(params);
};
const handleMessageEnd = (data: WebSocketMessageData, get): void => {
  const setShowNodeList = useFlowsManager.getState().setShowNodeList;
  const setFlowResult = useFlowsManager.getState().setFlowResult;
  const setCanvasesDisabled = useFlowsManager.getState().setCanvasesDisabled;
  const historyVersion = useFlowsManager.getState().historyVersion;
  const setEdges = useFlowStore.getState().setEdges;
  const flowResult: FlowResultType = {
    status: data.code === 0 ? 'success' : 'failed',
    timeCost: (data?.executedTime || 0).toString(),
    totalTokens: (data?.usage?.['total_tokens'] || 0).toString(),
  };
  get().wsMessageStatus = 'end';
  setShowNodeList(true);
  setFlowResult(flowResult);
  setEdges(edges =>
    edges?.map(edge => ({
      ...edge,
      animated: false,
      style: {
        stroke: '#275EFF',
        strokeWidth: 2,
      },
    }))
  );
  !historyVersion && setCanvasesDisabled(false);
  get().setInterruptChat({ ...initInterruptChat });
  handleRunningNodeStatus();
};
const handleResumeChat = (content, get, set): void => {
  const context: WorkflowDebugContext | undefined = get().debugContext;
  let body: string;
  try {
    if (!context) throw new Error('缺少同次调试上下文');
    body = context.reply(get().interruptChat?.eventId, content ? 'resume' : 'ignore', content);
  } catch { debugFailure(); return; }
  const nodes = useFlowStore.getState().nodes;
  const edges = useFlowStore.getState().edges;
  set({
    wsMessageStatus: 'start',
    debuggering: true,
  });
  pushAskToChatList(
    [
      {
        name: 'AGENT_USER_INPUT',
        type: 'string',
        default:
          content || i18n.t('workflow.nodes.chatDebugger.userIgnoredQuestion'),
        description: i18n.t(
          'workflow.nodes.chatDebugger.userCurrentRoundInput'
        ),
        required: true,
        validationSchema: null,
        errorMsg: '',
        originErrorMsg: '',
      },
    ],
    nodes,
    get().interruptChat?.nodeId,
    get
  );
  pushAnswerToChatList(get);
  void readDebugStream(context!, '/workflow/resume', body, nodes, edges, get, set).catch(debugFailure);
  clearNodeStatus(get);
};
const runDebugger = (obj: { nodes: ReactFlowNode[]; edges: ReactFlowEdge[]; get: () => any;
  set: (value: Record<string, unknown>) => void; enters?: any; regen?: boolean;
  receipt: Readonly<DebugReceipt>; scopeStamp: string }): void => {
  const { nodes, edges, get, set, enters, regen = false, receipt, scopeStamp } = obj;
  const inputs = {};
  const enterlist = enters ?? get().startNodeParams;
  enterlist.forEach(params => {
    if (
      params.type === 'object' ||
      (!params.fileType && params.type.includes('array'))
    ) {
      inputs[params.name] =
        isJSON(params.default as string) &&
        JSON.parse(params.default as string);
    } else if (params.fileType && params.type === 'string') {
      inputs[params.name] = params.default?.[0]?.url;
    } else if (params.fileType && params.type === 'array-string') {
      inputs[params.name] = params.default?.map(item => item?.url);
    } else {
      inputs[params.name] = params.default;
    }
  });
  const params = {
    flow_id: receipt.flowId,
    inputs: inputs,
    chatId: get().chatIdRef,
    regen,
  };
  const originalChat = receipt.mode === 'comparison'
    ? { ...params, version: receipt.comparisonId, promptDebugger: true } : params;
  const context = new WorkflowDebugContext(receipt, scopeStamp, debugScope, uuid(), originalChat, uuid);
  get().debugContext?.detach();
  set({ debugContext: context, controllerRef: context.controller });
  watchDebugContext(context, get, set);
  void readDebugStream(context, '/workflow/chat', context.chatBody, nodes, edges, get, set).catch(debugFailure);
  clearNodeStatus(get);
};
const advancedConfig = (): unknown => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  if (currentFlow?.advancedConfig && isJSON(currentFlow?.advancedConfig)) {
    const parsedConfig = JSON.parse(currentFlow?.advancedConfig);
    return {
      suggestedQuestionsAfterAnswer: {
        enabled: parsedConfig?.suggestedQuestionsAfterAnswer?.enabled ?? true,
      },
    };
  } else {
    return {
      suggestedQuestionsAfterAnswer: {
        enabled: true,
      },
    };
  }
};
const handleRunDebugger = ({
  nodes,
  edges,
  get,
  set,
  inputs,
  regen = false,
}): void => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  const setCanPublish = useFlowsManager.getState().setCanPublish;
  const setShowNodeList = useFlowsManager.getState().setShowNodeList;
  const setFlowResult = useFlowsManager.getState().setFlowResult;
  const historyVersion = useFlowsManager.getState().historyVersion;
  const setCanvasesDisabled = useFlowsManager.getState().setCanvasesDisabled;
  const scopeStamp = debugScope();
  if (!scopeStamp) { debugFailure(); get().setDebuggering(false); return; }
  const buildKey = uuid();
  const buildController = new AbortController();
  const previousContext: WorkflowDebugContext | undefined = get().debugContext;
  const previousController: AbortController | undefined = get().controllerRef;
  set({ debugContext: undefined, debugBuildKey: buildKey, controllerRef: buildController });
  previousContext?.detach();
  previousController?.abort();
  const stillCurrent = (): boolean => get().debugBuildKey === buildKey && !buildController.signal.aborted && debugScope() === scopeStamp;
  const changed = (): void => { if (!stillCurrent()) buildController.abort(); };
  const subscriptions = [useSpaceStore.subscribe(changed), useFlowsManager.subscribe(changed)];
  const detachBuild = (): void => buildController.abort();
  window.addEventListener('pagehide', detachBuild);
  window.addEventListener('storage', changed);
  const cleanupBuild = (): void => {
    subscriptions.forEach(unsubscribe => unsubscribe());
    window.removeEventListener('pagehide', detachBuild); window.removeEventListener('storage', changed);
    if (buildController.signal.aborted && get().debugBuildKey === buildKey &&
      get().controllerRef === buildController && !get().debugContext) releaseDebugView(set);
  };
  buildController.signal.addEventListener('abort', cleanupBuild, { once: true });
  if (
    advancedConfig()?.suggestedQuestionsAfterAnswer?.enabled &&
    get().startNodeParams?.length === 1
  ) {
    get().setSuggestLoading(true);
    nextQuestionAdvice({
      question: inputs?.[0]?.default || get().startNodeParams?.[0]?.default,
    })
      .then(data => {
        get().setSuggestProblem(() => data);
      })
      .finally(() => get().setSuggestLoading(false));
  } else {
    get().setSuggestProblem(() => []);
  }
  get().buildPassRef = false;
  let params = {};
  let api: ((params: unknown, buildKey?: string) => Promise<unknown>) | null = null;
  if (historyVersion) {
    get().versionId = uuid();
    params = {
      flowId: currentFlow?.flowId,
      name: currentFlow?.name,
      data: {
        nodes: nodes?.map(({ nodeType, ...reset }) => ({
          ...reset,
          data: {
            ...reset?.data,
            updatable: false,
          },
        })),
        edges: edges?.map(({ style, animated, ...reset }) => reset),
      },
      version: get().versionId,
    };
    api = addComparisons;
  } else {
    get().versionId = '';
    params = {
      id: currentFlow?.id,
      flowId: currentFlow?.flowId,
      name: currentFlow?.name,
      description: currentFlow?.description,
      data: {
        nodes: nodes?.map(({ nodeType, ...reset }) => {
          const node = {
            ...reset,
            data: {
              ...reset?.data,
              updatable: false,
            },
          };
          Reflect.deleteProperty(node.data, 'debuggerResult');
          return node;
        }),
        edges: edges?.map(({ style, animated, ...reset }) => reset),
      },
    };
    api = buildFlowAPI;
  }
  const comparisonId = historyVersion ? get().versionId : null;
  api(params, buildKey).then(data => {
    if (!stillCurrent()) return;
    const receipt = readDebugReceipt(data, { workflowId: currentFlow?.id, flowId: currentFlow?.flowId,
      spaceId: useSpaceStore.getState().spaceId, mode: historyVersion ? 'comparison' : 'ordinary', comparisonId, buildRequestKey: buildKey });
    setCanPublish(true);
    setShowNodeList(false);
    set({
      preRunningNodeIds: [],
      buildPassRef: true,
      userWheel: false,
    });
    setFlowResult({
      status: 'running',
      timeCost: '',
      totalTokens: '',
    });
    const nodeId = nodes?.find(node => node?.nodeType === 'node-start')?.id;
    pushAskToChatList(inputs, nodes, nodeId, get);
    !historyVersion && setCanvasesDisabled(true);
    pushAnswerToChatList(get);
    runDebugger({
      nodes,
      edges,
      get,
      set,
      enters: inputs,
      regen,
      receipt,
      scopeStamp,
    });
    //同步数据到开放平台
    handleSynchronizeDataToXfyun();
  }).catch(() => {
    if (!stillCurrent()) return;
    debugFailure(); get().setDebuggering(false); !historyVersion && setCanvasesDisabled(false);
    setFlowResult({ status: '', timeCost: '', totalTokens: '' });
  }).finally(cleanupBuild);
};
const clearData = (setOpen, get): void => {
  get().debugContext?.detach();
  get().controllerRef?.abort();
  get().debugContext = undefined;
  const setFlowResult = useFlowsManager.getState().setFlowResult;
  const setShowNodeList = useFlowsManager.getState().setShowNodeList;
  const setCanvasesDisabled = useFlowsManager.getState().setCanvasesDisabled;
  get().preRunningNodeIds = [];
  get().setStartNodeParams([]);
  if (get().userInput) {
    get().setUserInput('');
  }
  setOpen(false);
  if (get().debuggering) {
    setFlowResult({
      status: '',
      timeCost: '',
      totalTokens: '',
    });
  }
  setShowNodeList(true);
  setCanvasesDisabled(!!useFlowsManager.getState().historyVersion);
};

const canRunDebugger = (get): boolean => {
  if (!get().debuggering && get().interruptChat?.type === 'option')
    return false;
  if (
    !get().debuggering &&
    get().startNodeParams?.length > 1 &&
    get().startNodeParams.every((params: StartNodeType) => {
      if (params?.required) {
        if (params.errorMsg) {
          return false;
        } else if (params.fileType) {
          return params?.default?.length > 0;
        } else if (params.type === 'object' || params.type.includes('array')) {
          return isJSON(params?.default as string);
        } else if (params.type === 'string') {
          return (params?.default as string)?.trim();
        } else if (params.type === 'boolean') {
          return typeof params?.default === 'boolean';
        } else {
          return typeof params?.default !== 'string';
        }
      } else if (params.fileType) {
        return params?.default?.every(item => !item?.loading);
      } else {
        return true;
      }
    })
  ) {
    return true;
  }
  if (
    !get().debuggering &&
    get().startNodeParams?.length === 1 &&
    get().userInput?.trim()
  ) {
    return true;
  }
  return false;
};

const handleEnterKey = (
  e: React.KeyboardEvent<HTMLInputElement>,
  get,
  set
): void => {
  e.stopPropagation();
  if (
    e.nativeEvent.keyCode === 13 &&
    !e.nativeEvent.shiftKey &&
    canRunDebugger(get)
  ) {
    e.preventDefault();
    if (get().interruptChat?.interrupt) {
      handleResumeChat(get().userInput, get, set);
    } else {
      const { nodes, edges } = resetNodesAndEdges(get);
      handleRunDebugger({
        nodes,
        edges,
        get,
        set,
        inputs: [
          {
            name: 'AGENT_USER_INPUT',
            type: 'string',
            default: get().userInput,
            description: i18n.t(
              'workflow.nodes.chatDebugger.userCurrentRoundInput'
            ),
            required: true,
            validationSchema: null,
            errorMsg: '',
            originErrorMsg: '',
          },
        ],
      });
    }
  } else if (e.nativeEvent.keyCode === 13 && !e.nativeEvent.shiftKey) {
    e.preventDefault();
  } else if (e.key === 'Tab') {
    get().setUserInput(get().userInput + '\t');
    get().setStartNodeParams(startNodeParams => {
      startNodeParams[0].default = startNodeParams[0].default + '\t';
      return [...startNodeParams];
    });
    e.preventDefault();
  }
};

const resetNodesAndEdges = (
  get
): {
  nodes: ReactFlowNode[];
  edges: ReactFlowEdge[];
} => {
  const nodes = useFlowStore.getState().nodes;
  const edges = useFlowStore.getState().edges;
  const setNodes = useFlowStore.getState().setNodes;
  get().wsMessageStatus = 'start';
  get().setDebuggering(true);
  const newNodes = cloneDeep(nodes);
  newNodes.forEach(node => {
    node.data.status = '';
    node.data.debuggerResult = {};
  });
  setNodes(newNodes);
  return {
    nodes: newNodes,
    edges: edges,
  };
};

const handleStopConversation = (get): void => {
  const context: WorkflowDebugContext | undefined = get().debugContext;
  if (!context?.alive() || !context.runId) { debugFailure(); return; }
  const abortReply = !!get().interruptChat?.interrupt;
  let body: string;
  try {
    body = abortReply ? context.reply(get().interruptChat.eventId, 'abort', '') : context.cancelBody();
  } catch { debugFailure(); return; }
  // 停止确认使用独立短请求，不继承即将关闭的流signal；确认不是终态。
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  const release = context.addCleanup(() => { clearTimeout(timer); controller.abort(); });
  fetch(getFixedUrl(abortReply ? '/workflow/resume' : '/workflow/cancel'), {
    method: 'POST', headers: { ...debugHeaders(), ...(abortReply ? { 'X-PaiFlow-Reply-Only': 'true' } : {}) },
    body, signal: controller.signal,
  }).then(async response => {
    if (!context.alive()) return;
    if (!response.ok || response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'application/json') throw new Error('停止未确认');
    context.acknowledge(await response.json(), abortReply);
    if (abortReply) get().setInterruptChat({ ...initInterruptChat });
    // 用原chat请求和原key读取已确认的同Run，不把ACK当已停或完成。
    const { nodes, edges } = useFlowStore.getState();
    void readDebugStream(context, '/workflow/chat', context.chatBody, nodes, edges, get, undefined).catch(debugFailure);
  }).catch(() => { if (context.alive()) debugFailure(); }).finally(() => { clearTimeout(timer); release(); });
};

const deleteAllChat = (get): void => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  const historyVersion = useFlowsManager.getState().historyVersion;
  const setCanvasesDisabled = useFlowsManager.getState().setCanvasesDisabled;
  workflowDialogClear(currentFlow?.id, 1).then(() => {
    get().chatIdRef = uuid().replace(/-/g, '');
    get().setDeleteAllModal(false);
    get().setChatList([]);
    get().setInterruptChat({ ...initInterruptChat });
    !historyVersion && setCanvasesDisabled(false);
  });
};

const handleWorkflowDeleteComparisons = (get): void => {
  const currentFlow = useFlowsManager.getState().currentFlow;
  if (!get().versionId) return;
  const parmas = {
    flowId: currentFlow?.flowId,
    version: get().versionId,
  };
  workflowDeleteComparisons(parmas);
};

const setChatList = (change: ChatChange<ChatListItem[]>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().chatList) : change;
  set({
    chatList: newChange,
  });
};

const setStartNodeParams = (change: ChatChange<StartNodeType[]>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().startNodeParams) : change;
  set({
    startNodeParams: newChange,
  });
};

const setInterruptChat = (change: ChatChange<InterruptChatType>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().interruptChat) : change;
  set({
    interruptChat: newChange,
  });
};

const setSuggestLoading = (change: ChatChange<boolean>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().suggestLoading) : change;
  set({
    suggestLoading: newChange,
  });
};

const setSuggestProblem = (change: ChatChange<string[]>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().suggestProblem) : change;
  set({
    suggestProblem: newChange,
  });
};

const setUserWheel = (change: ChatChange<boolean>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().userWheel) : change;
  set({
    userWheel: newChange,
  });
};

const setDebuggering = (change: ChatChange<boolean>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().debuggering) : change;
  set({
    debuggering: newChange,
  });
};

const setDeleteAllModal = (change: ChatChange<boolean>, get, set): void => {
  const newChange =
    typeof change === 'function' ? change(get().deleteAllModal) : change;
  set({
    deleteAllModal: newChange,
  });
};

const setWsMessageStatus = (status: string, set): void => {
  set({
    wsMessageStatus: status,
  });
};

const setUserInput = (value: string, set): void => {
  set({
    userInput: value,
  });
};

const setQueue = (number, get, set): void => {
  const key = get().messageNodeTextQueue
    ? 'messageNodeTextQueue'
    : get().endNodeReasoningTextQueue
      ? 'endNodeReasoningTextQueue'
      : 'endNodeTextQueue';
  set({
    [key]: get()[key].slice(number),
  });
};

const getTextQueueContent = (get): string => {
  return (
    get().messageNodeTextQueue ||
    get().endNodeReasoningTextQueue ||
    get().endNodeTextQueue
  );
};

const getChatKey = (get): string => {
  return get().messageNodeTextQueue
    ? 'messageContent'
    : get().endNodeReasoningTextQueue
      ? 'reasoningContent'
      : 'content';
};

const isChatEnd = (get): boolean => {
  return (
    !get().messageNodeTextQueue &&
    !get().endNodeReasoningTextQueue &&
    !get().endNodeTextQueue &&
    get().wsMessageStatus === 'end'
  );
};

export {
  getDialogues,
  clearNodeStatus,
  handleSaveDialogue,
  handleResumeChat,
  handleRunDebugger,
  clearData,
  handleEnterKey,
  handleStopConversation,
  deleteAllChat,
  handleWorkflowDeleteComparisons,
  resetNodesAndEdges,
  setChatList,
  setStartNodeParams,
  setInterruptChat,
  setSuggestLoading,
  setSuggestProblem,
  setUserWheel,
  setDebuggering,
  setDeleteAllModal,
  canRunDebugger,
  setWsMessageStatus,
  setQueue,
  setUserInput,
  getTextQueueContent,
  isChatEnd,
  getChatKey,
};
