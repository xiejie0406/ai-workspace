import React, { useMemo, useState } from 'react';
import { cloneDeep } from 'lodash';
import { useMemoizedFn } from 'ahooks';
import { Tooltip, Checkbox } from 'antd';
import useFlowsManager from '@/components/workflow/store/use-flows-manager';
import {
  renderType,
  findPathById,
  deleteFieldByPath,
  checkedNodeOutputData,
  generateOrUpdateObject,
  removeItemById,
  findItemById,
  isBaseType,
  generateReferences,
  isObject,
  workflowValueToText,
  isWorkflowDisplayValue,
} from '@/components/workflow/utils/reactflowUtils';
import {
  FlowNodeInput,
  FlowTypeCascader,
  FlowNodeTextArea,
} from '@/components/workflow/ui';
import { v4 as uuid } from 'uuid';
import { isJSON } from '@/utils';
import { originOutputTypeList } from '@/components/workflow/constant';
import { useTranslation } from 'react-i18next';
import {
  AgentNodeOneClickUpdate,
  ToolNodeOneClickUpdate,
  FlowNodeOneClickUpdate,
  RpaNodeOneClickUpdate,
} from '@/components/workflow/hooks/use-one-click-update';
import {
  NodeCommonProps,
  NodeDataType,
  InputItem,
  OutputItem,
  PropertyItem,
  UseNodeCommonReturn,
  UseNodeInfoReturn,
  UseNodeFuncReturn,
  UseNodeOutputRenderReturn,
  UseNodeModelsReturn,
  UseNodeHandleReturn,
  UseNodeInputRenderReturn,
  WorkflowModelSummary,
} from '@/components/workflow/types/hooks';
import type { OutputType } from '@/components/workflow/types';

import addItemIcon from '@/assets/imgs/workflow/add-item-icon.png';
import remove from '@/assets/imgs/workflow/input-remove-icon.png';

const useNodeInfo = ({ id, data }: NodeCommonProps): UseNodeInfoReturn => {
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const showIterativeModal = useFlowsManager(state => state.showIterativeModal);
  const nodeList = useFlowsManager(state => state.nodeList);
  const nodes = currentStore(state => state.nodes);
  const edges = currentStore(state => state.edges);

  const nodeType = useMemo(() => {
    return id?.split('::')[0] || '';
  }, [id]);
  // 判断是否为开始节点
  const isStartNode = useMemo(() => {
    return nodeType === 'node-start';
  }, [nodeType]);

  const isIteratorStart = useMemo(() => {
    return nodeType === 'iteration-node-start';
  }, [nodeType]);

  const isEndNode = useMemo(() => {
    return nodeType === 'node-end';
  }, [nodeType]);

  const isIteratorEnd = useMemo(() => {
    return nodeType === 'iteration-node-end';
  }, [nodeType]);

  const isKnowledgeNode = useMemo(() => {
    return nodeType === 'knowledge-base';
  }, [nodeType]);

  const isQuestionAnswerNode = useMemo(() => {
    return nodeType === 'question-answer';
  }, [nodeType]);

  const isDecisionMakingNode = useMemo(() => {
    return nodeType === 'decision-making';
  }, [nodeType]);

  const isIfElseNode = useMemo(() => {
    return nodeType === 'if-else';
  }, [nodeType]);

  const isIteratorNode = useMemo(() => {
    return nodeType === 'iteration';
  }, [nodeType]);

  const isIteratorChildNode = useMemo(() => {
    return !showIterativeModal && data?.parentId;
  }, [showIterativeModal, data?.parentId]);

  const isAgentNode = useMemo(() => {
    return nodeType === 'agent';
  }, [nodeType]);

  const isStartOrEndNode = useMemo(() => {
    return nodeType === 'node-start' || nodeType === 'node-end';
  }, [nodeType]);

  const isCodeNode = useMemo(() => {
    return nodeType === 'ifly-code';
  }, [nodeType]);

  const isDataBaseNode = useMemo(() => {
    return nodeType === 'database';
  }, [nodeType]);

  const showInputs = useMemo(() => {
    return (
      (data?.inputs?.length ?? 0) > 0 &&
      !isIfElseNode &&
      (data?.nodeParam?.mode === 0 || data?.nodeParam?.mode === undefined)
    );
  }, [data, isIfElseNode]);

  const showOutputs = useMemo(() => {
    return (data?.outputs?.length ?? 0) > 0;
  }, [data?.outputs, id]);

  const showExceptionFlow = useMemo(() => {
    return (
      data?.retryConfig?.shouldRetry && data?.retryConfig?.errorStrategy === 2
    );
  }, [data?.retryConfig?.shouldRetry, data?.retryConfig?.errorStrategy]);

  const references = useMemo(() => {
    return generateReferences(nodes, edges, id);
  }, [id, nodes, edges]);
  const isFixedInputsNode = useMemo(() => {
    return ['plugin', 'flow', 'rpa'].includes(nodeType);
  }, [nodeType]);
  const inputs = useMemo(() => {
    return data?.inputs || [];
  }, [data?.inputs]);

  const outputs = useMemo(() => {
    return data?.outputs || [];
  }, [data?.outputs]);

  const showNodeOperation = useMemo(() => {
    return !isStartNode && !isEndNode;
  }, [isStartNode, isEndNode]);

  const currentNode = useMemo(() => {
    return nodes?.find(item => item?.id === id);
  }, [nodes, id]);

  // 节点参数
  const nodeParam = useMemo(() => {
    return data?.nodeParam || {};
  }, [data?.nodeParam]);

  const nodeIcon = useMemo(() => {
    let nodeFinallyType = '';
    if (nodeType === 'iteration-node-start') {
      nodeFinallyType = 'node-start';
    } else if (nodeType === 'iteration-node-end') {
      nodeFinallyType = 'node-end';
    } else {
      nodeFinallyType = nodeType;
    }
    const currentNode = nodeList
      ?.flatMap(item => isObject(item) && Array.isArray(item.nodes) ? item.nodes : [])
      ?.find((item: unknown) => isObject(item) && item.idType === nodeFinallyType);
    if (!isObject(currentNode) || !isObject(currentNode.data)) return undefined;
    return typeof currentNode.data.icon === 'string' ? currentNode.data.icon : undefined;
  }, [nodeList, nodeType]);

  const nodeDesciption = useMemo(() => {
    //工具节点需要特判一下，使用工具本身的描述
    if (nodeType === 'plugin') {
      return data?.nodeParam?.toolDescription;
    }
    if (nodeType === 'rpa') {
      return data?.nodeParam?.rpaDescription;
    }
    const currentNode = nodeList
      ?.flatMap(item => isObject(item) && Array.isArray(item.nodes) ? item.nodes : [])
      ?.find((item: unknown) => isObject(item) && item.idType === nodeType);
    if (!isObject(currentNode)) return undefined;
    const description = currentNode.description || (isObject(currentNode.data) ? currentNode.data.description : undefined);
    return typeof description === 'string' ? description : undefined;
  }, [nodeList, data, nodeType]);
  const isRpaNode = useMemo(() => {
    return nodeType === 'rpa' || nodeType === 'rpa';
  }, [nodeType]);

  return {
    nodeType,
    isStartNode,
    isIteratorStart,
    isEndNode,
    isIteratorEnd,
    isKnowledgeNode,
    isQuestionAnswerNode,
    isDecisionMakingNode,
    isIfElseNode,
    isIteratorNode,
    isIteratorChildNode,
    isAgentNode,
    isStartOrEndNode,
    isCodeNode,
    isDataBaseNode,
    showInputs,
    showOutputs,
    showExceptionFlow,
    references,
    isFixedInputsNode,
    inputs,
    outputs,
    showNodeOperation,
    currentNode,
    nodeParam,
    nodeIcon,
    nodeDesciption,
    isRpaNode,
  };
};

const useNodeFunc = ({ id, data }: NodeCommonProps): UseNodeFuncReturn => {
  const { isIteratorNode, nodeType } = useNodeInfo({ id, data });
  const setNodeInfoEditDrawerlInfo = useFlowsManager(
    state => state.setNodeInfoEditDrawerlInfo
  );
  const setChatDebuggerResult = useFlowsManager(
    state => state.setChatDebuggerResult
  );
  const setVersionManagement = useFlowsManager(
    state => state.setVersionManagement
  );
  const setAdvancedConfiguration = useFlowsManager(
    state => state.setAdvancedConfiguration
  );
  const setOpenOperationResult = useFlowsManager(
    state => state.setOpenOperationResult
  );
  const autoSaveCurrentFlow = useFlowsManager(
    state => state.autoSaveCurrentFlow
  );
  const canPublishSetNot = useFlowsManager(state => state.canPublishSetNot);
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const checkNode = currentStore(state => state.checkNode);
  const setNode = currentStore(state => state.setNode);
  const updateNodeRef = currentStore(state => state.updateNodeRef);
  const takeSnapshot = currentStore(state => state.takeSnapshot);
  const deleteNodeRef = currentStore(state => state.deleteNodeRef);
  const handleNodeClick = useMemoizedFn(() => {
    setNodeInfoEditDrawerlInfo({
      open: true,
      nodeId: id,
    });
    setChatDebuggerResult(false);
    setVersionManagement(false);
    setAdvancedConfiguration(false);
    setOpenOperationResult(false);
  });
  // ahooks 的 PickFunction 会擦除泛型；实现始终将同一次 value 原样传给对应回调。
  const handleChangeNodeParam = useMemoizedFn(
    <T,>(fn: (data: NodeDataType, value: T) => void, value: T) => {
      setNode(id, old => {
        fn(old.data, value);
        return {
          ...cloneDeep(old),
        };
      });
      autoSaveCurrentFlow();
      canPublishSetNot();
      checkNode(id);
    }
  ) as UseNodeFuncReturn['handleChangeNodeParam'];
  const handleChangeOutputParam = useMemoizedFn(
    <T,>(
      outputId: string,
      fn: (data: OutputItem, value: T, nodeData: NodeDataType) => void,
      value: T
    ): void => {
      setNode(id, old => {
        const before = cloneDeep(old);
        const currentOutput = findItemById(old.data.outputs, outputId);
        if (currentOutput) {
          fn(currentOutput, value, old?.data);
          handleIteratorEndChange('replace', outputId, value, before);
        }
        return {
          ...cloneDeep(old),
        };
      });
      updateNodeRef(id);
      canPublishSetNot();
    }
  ) as UseNodeFuncReturn['handleChangeOutputParam'];

  const handleIteratorEndChange = useMemoizedFn(
    (
      type: 'add' | 'remove' | 'replace',
      outputId: string,
      value?: unknown,
      currentNode?: { data: NodeDataType }
    ) => {
      if (isIteratorNode) {
        const liveNodes = currentStore.getState().nodes;
        const parent = liveNodes.find(node => node.id === id);
        const output = (currentNode?.data?.outputs || parent?.data?.outputs)
          ?.find((item: OutputItem) => item.id === outputId);
        if (!output) return;
        const currentIteratorInput = {
          id: output.id,
          name: output.name,
          schema: {
            type: '',
            value: {
              type: 'ref',
              content: {},
            },
          },
        };
        const iteratorStartEnd = liveNodes.find(
          node => node?.data?.parentId === id && 'nodeType' in node && node.nodeType === 'iteration-node-end'
        );
        if (!iteratorStartEnd) return;
        setNode(iteratorStartEnd?.id, old => {
          const inputs: InputItem[] = old.data.inputs || [];
          if (type === 'add') {
            old.data.inputs = [...inputs, currentIteratorInput];
          } else {
            // 原引擎按名称汇总内部 End 结果；旧画布两侧 ID 独立生成，不能按位置关联。
            const sameId = inputs.find(input => input.id === output.id);
            const sameName = inputs.filter(input => input.name === output.name);
            const currentInput = sameId || (sameName.length === 1 ? sameName[0] : undefined);
            if (!currentInput) return old;
            if (type === 'remove') {
              old.data.inputs = inputs.filter(input => input.id !== currentInput.id);
            } else {
              const updatedOutput: OutputItem | undefined = parent?.data?.outputs
                ?.find((item: OutputItem) => item.id === outputId);
              if (updatedOutput) currentInput.name = updatedOutput.name;
            }
          }
          return cloneDeep(old);
        });
      }
    }
  );
  const handleAddOutputLine = useMemoizedFn(() => {
    takeSnapshot();
    const outputId = uuid();
    setNode(id, old => {
      (old.data.outputs ||= []).push({
        id: outputId,
        name: '',
        schema: {
          type: isIteratorNode ? 'array-string' : 'string',
          default: '',
        },
        required: false,
      });
      return {
        ...cloneDeep(old),
      };
    });
    handleIteratorEndChange('add', outputId);
    canPublishSetNot();
  });
  const handleRemoveOutputLine = useMemoizedFn((outputId: string) => {
    const parent = currentStore.getState().nodes.find(node => node.id === id);
    if (!parent || !findItemById(parent.data.outputs, outputId)) return;
    const before = cloneDeep(parent);
    takeSnapshot();
    setNode(id, old => {
      const path = findPathById(old.data.outputs, outputId);
      if (path && isJSON(old?.data?.retryConfig?.customOutput)) {
        const updatedObj = deleteFieldByPath(
          cloneDeep(JSON.parse(old?.data?.retryConfig?.customOutput)),
          path
        );
        old.data.retryConfig.customOutput = JSON.stringify(updatedObj, null, 2);
      }
      old.data.outputs = removeItemById(old.data.outputs, outputId);
      return {
        ...cloneDeep(old),
      };
    });
    deleteNodeRef(id, outputId);
    canPublishSetNot();
    handleIteratorEndChange('remove', outputId, undefined, before);
  });
  const isFixedOutputComponentFunc = useMemoizedFn((output: OutputItem) => {
    return nodeType === 'database' && !output?.isChild;
  });
  return {
    handleNodeClick,
    handleChangeNodeParam,
    handleChangeOutputParam,
    handleIteratorEndChange,
    handleAddOutputLine,
    handleRemoveOutputLine,
    isFixedOutputComponentFunc,
  };
};

type OutputRenderProps = NodeCommonProps & { output: OutputItem };

const OutputNameInput = ({ id, data, output }: OutputRenderProps): React.ReactElement => {
  const { handleChangeOutputParam } = useNodeFunc({ id, data });
  const { handleCustomOutputGenerate } = useNodeOutputRender({ id, data });
  const { isFixedOutputComponentFunc } = useNodeFunc({ id, data });
  const isFixedOutputComponent = isFixedOutputComponentFunc(output);
  if (isFixedOutputComponent) {
    return <div>{output.name}</div>;
  }
  const handleChange = useMemoizedFn((value: string) => {
    if (!output.id) return;
    handleChangeOutputParam(
      output.id,
      (data, value) => (data.name = value),
      value
    );
  });

  const handleBlur = useMemoizedFn(() => {
    handleCustomOutputGenerate();
  });

  return (
    <FlowNodeInput
      nodeId={id}
      disabled={
        output?.deleteDisabled || output?.customParameterType === 'deepseekr1'
      }
      maxLength={30}
      className="w-full"
      value={output.name}
      onChange={handleChange}
      onBlur={handleBlur}
    />
  );
};

// 类型选择器
const OutputTypeSelector = ({ id, data, output }: OutputRenderProps): React.ReactElement => {
  const { handleChangeOutputParam } = useNodeFunc({ id, data });
  const { outputTypeList } = useNodeOutputRender({ id, data });
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const delayUpdateNodeRef = currentStore(state => state.delayUpdateNodeRef);
  const { isFixedOutputComponentFunc } = useNodeFunc({ id, data });
  const isFixedOutputComponent = isFixedOutputComponentFunc(output);
  if (isFixedOutputComponent) {
    return <div>{renderType(output)}</div>;
  }
  const handleTypeChange = useMemoizedFn((value: unknown) => {
    if (!output.id || !Array.isArray(value) || typeof value[0] !== 'string') return;
    const kind = value[0];
    const type = ['file', 'fileList'].includes(kind) ? value[1] : kind;
    if (typeof type !== 'string') return;
    handleChangeOutputParam(
      output.id,
      (data, value) => {
        if (kind === 'file') {
          data.fileType = 'file';
          data.schema = { type: 'string' };
          data.allowedFileType = [value];
        } else if (kind === 'fileList') {
          data.fileType = 'file';
          data.schema = { type: 'array-string' };
          data.allowedFileType = [value.replace(/.*<(.+?)>.*/, '$1')];
        } else if (data?.schema?.type) {
          data.schema.type = type;
          delete data.fileType;
          delete data.allowedFileType;
        } else {
          data.type = type;
          delete data.fileType;
          delete data.allowedFileType;
        }

        if (isBaseType(type)) {
          if (data?.schema?.type) {
            data.schema.properties = [];
          } else {
            data.properties = [];
          }
        }
      },
      type
    );

    delayUpdateNodeRef(id);
  });

  return (
    <FlowTypeCascader
      value={
        output.fileType === 'file'
          ? output?.schema?.type === 'string'
            ? ['file', output?.allowedFileType?.[0]]
            : ['fileList', `Array<${output?.allowedFileType?.[0]}>`]
          : output?.schema?.type || output.type
      }
      disabled={
        output?.deleteDisabled || output?.customParameterType === 'deepseekr1'
      }
      options={outputTypeList}
      onChange={handleTypeChange}
    />
  );
};

// 描述/类型输入
const OutputDescription = ({ id, data, output }: OutputRenderProps): React.ReactElement => {
  const { renderTypeInput } = useNodeInputRender({ id, data });
  const { isFixedOutputComponentFunc } = useNodeFunc({ id, data });
  const isFixedOutputComponent = isFixedOutputComponentFunc(output);
  if (isFixedOutputComponent) {
    const value = output?.schema?.default || output?.default;
    if (!isWorkflowDisplayValue(value)) throw new TypeError('输出描述不是可显示的文本或数组');
    return <div>{value}</div>;
  }
  return (
    <div
      className={`flex flex-col flex-1 h-full ${
        output?.deleteDisabled || output?.customParameterType === 'deepseekr1'
          ? 'disabled-flow-textarea'
          : ''
      }`}
    >
      {renderTypeInput(output)}
    </div>
  );
};

// 错误提示
const OutputErrors = ({ output }: Pick<OutputRenderProps, 'output'>): React.ReactElement => (
  <div className="flex items-center gap-3 text-xs text-[#F74E43]">
    <div className="flex flex-col w-1/4">{output?.nameErrMsg}</div>
    <div className="flex flex-col w-1/4"></div>
    <div className="flex flex-col flex-1">
      {output?.schema?.value?.contentErrMsg}
    </div>
  </div>
);

const useNodeOutputRender = ({ id, data }: NodeCommonProps): UseNodeOutputRenderReturn => {
  const { outputs, currentNode, isStartNode, isIteratorNode } = useNodeInfo({
    id,
    data,
  });
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const delayUpdateNodeRef = currentStore(state => state.delayUpdateNodeRef);
  const setNode = currentStore(state => state.setNode);
  const autoSaveCurrentFlow = useFlowsManager(
    state => state.autoSaveCurrentFlow
  );
  const canPublishSetNot = useFlowsManager(state => state.canPublishSetNot);

  const handleCustomOutputGenerate = useMemoizedFn(() => {
    delayUpdateNodeRef(id);
    setTimeout(() => {
      if (!checkedNodeOutputData(outputs, currentNode)) {
        setNode(id, old => {
          old.data.nodeParam.setAnswerContentErrMsg =
            '输出中变量名校验不通过,自动生成JSON失败';
          return {
            ...cloneDeep(old),
          };
        });
        return;
      }
      setNode(id, old => {
        if (old?.data?.retryConfig) {
          const newSetAnswerContent = JSON.stringify(
            generateOrUpdateObject(
              old?.data.outputs,
              isJSON(old?.data?.retryConfig?.customOutput)
                ? JSON.parse(old?.data?.retryConfig?.customOutput)
                : null
            ),
            null,
            2
          );
          old.data.retryConfig.customOutput = newSetAnswerContent;
          old.data.nodeParam.setAnswerContentErrMsg = '';
        }
        return {
          ...cloneDeep(old),
        };
      });
      autoSaveCurrentFlow();
      canPublishSetNot();
    }, 500);
  });

  const renderOutputComponent = useMemoizedFn(
    (output: OutputItem): React.ReactElement => {
      const type = output?.schema?.type || output?.type;
      return (
        <div className="w-full flex flex-col gap-1">
          <div className="flex items-start gap-3">
            <div className="flex flex-col w-1/4 flex-shrink-0">
              <OutputNameInput
                {...{
                  id,
                  data,
                  output,
                }}
              />
            </div>

            <div className="flex flex-col w-1/4">
              <OutputTypeSelector
                {...{
                  id,
                  data,
                  output,
                }}
              />
            </div>

            <div className="flex flex-col flex-1">
              <OutputDescription {...{ id, data, output }} />
            </div>

            <OutputActions
              {...{
                id,
                data,
                output,
                type,
              }}
            />
          </div>

          <OutputErrors output={output} />
        </div>
      );
    }
  );

  const outputTypeList = useMemo(() => {
    if (isStartNode) return originOutputTypeList;
    if (isIteratorNode)
      return [
        ...originOutputTypeList.slice(5),
        {
          label: 'Array<Array>',
          value: 'array-array',
        },
      ];
    return originOutputTypeList.filter(
      item => item?.value !== 'file' && item?.value !== 'fileList'
    );
  }, [originOutputTypeList, isStartNode, isIteratorNode]);

  return {
    handleCustomOutputGenerate,
    renderOutputComponent,
    outputTypeList,
  };
};

const useNodeModels = ({ id, data }: NodeCommonProps): UseNodeModelsReturn => {
  const agentModels = useFlowsManager(state => state.agentModels);
  const sparkLlmModels = useFlowsManager(state => state.sparkLlmModels);
  const questionAnswerModels = useFlowsManager(
    state => state.questionAnswerModels
  );
  const decisionMakingModels = useFlowsManager(
    state => state.decisionMakingModels
  );
  const extractorParameterModels = useFlowsManager(
    state => state.extractorParameterModels
  );
  const models = useMemo(() => {
    const source = (() => {
    if (id?.startsWith('agent')) {
      return agentModels;
    }
    if (id?.startsWith('spark-llm')) {
      return sparkLlmModels;
    }
    if (id?.startsWith('question-answer')) {
      return questionAnswerModels;
    }
    if (id?.startsWith('decision-making')) {
      return decisionMakingModels;
    }
    if (id?.startsWith('extractor-parameter')) {
      return extractorParameterModels;
    }
      return [];
    })();
    return source.filter((item: unknown): item is WorkflowModelSummary => isObject(item)
      && (item.llmId === undefined || typeof item.llmId === 'string' || typeof item.llmId === 'number'));
  }, [id, agentModels, sparkLlmModels, questionAnswerModels]);
  const model = useMemo(() => {
    return models?.find(item => item?.llmId === data?.nodeParam?.llmId);
  }, [data?.nodeParam?.llmId, models]);
  const isThinkModel = useMemo(() => {
    return data?.nodeParam?.isThink;
  }, [data?.nodeParam?.isThink]);
  return {
    models,
    model,
    isThinkModel,
  };
};

// 新增按钮
type RequiredChangeEvent = Parameters<NonNullable<React.ComponentProps<typeof Checkbox>['onChange']>>[0];

const AddButton = ({ type, handleAdd }: { type?: string; handleAdd: () => void }): React.ReactElement | null => {
  const { t } = useTranslation();
  const canvasesDisabled = useFlowsManager(state => state.canvasesDisabled);
  if (canvasesDisabled || (type !== 'object' && type !== 'array-object'))
    return null;

  return (
    <Tooltip
      title={t('workflow.nodes.common.addSubItem')}
      overlayClassName="black-tooltip config-secret"
    >
      <img
        src={addItemIcon}
        className="w-4 h-4 mt-1.5 cursor-pointer"
        onClick={handleAdd}
      />
    </Tooltip>
  );
};

// 必填勾选框
const RequiredCheckbox = ({
  isStartNode,
  output,
  handleRequiredChange,
}: { isStartNode: boolean; output: OutputItem; handleRequiredChange: (event: RequiredChangeEvent) => void }): React.ReactElement | null => {
  if (!isStartNode) return null;

  return (
    <div className="w-[50px] flex justify-center items-center mt-1.5">
      <Checkbox
        disabled={output?.deleteDisabled}
        checked={output.required}
        style={{ width: '16px', height: '16px', background: '#F9FAFB' }}
        onChange={handleRequiredChange}
      />
    </div>
  );
};

// 删除按钮
const RemoveButton = ({
  id,
  data,
  output,
  handleRemove,
}: OutputRenderProps & { handleRemove: () => void }): React.ReactElement | null => {
  const { outputs, isDataBaseNode } = useNodeInfo({ id, data });
  const canvasesDisabled = useFlowsManager(state => state.canvasesDisabled);

  if (canvasesDisabled) return null;

  if (isDataBaseNode && !output?.isChild) return null;

  if (outputs?.length <= 1) return null;

  const disabled =
    output?.deleteDisabled || output?.customParameterType === 'deepseekr1';

  return (
    <img
      src={remove}
      className="w-[16px] h-[17px] mt-1.5"
      style={{
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
      }}
      onClick={handleRemove}
      alt=""
    />
  );
};

// 主操作区组件
export const OutputActions = ({
  id,
  data,
  output,
  type,
}: OutputRenderProps & { type?: string }): React.ReactElement => {
  const { isStartNode } = useNodeInfo({ id, data });
  const { handleChangeOutputParam, handleRemoveOutputLine } = useNodeFunc({
    id,
    data,
  });
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const canPublishSetNot = useFlowsManager(state => state.canPublishSetNot);
  const takeSnapshot = currentStore(state => state.takeSnapshot);
  const setNode = currentStore(state => state.setNode);
  const checkNode = currentStore(state => state.checkNode);

  const handleAddItem = useMemoizedFn((output: OutputItem) => {
    const outputId = output.id;
    if (!outputId) return;
    takeSnapshot();
    setNode(id, old => {
      const currentOutput = findItemById(old.data.outputs, outputId);
      if (!currentOutput) return old;
      const propertyItem = {
        id: uuid(),
        name: '',
        type: 'string',
        default: '',
        required: false,
      };
      if (currentOutput?.schema) {
        if (currentOutput?.schema?.properties) {
          currentOutput.schema.properties.push(propertyItem);
        } else {
          currentOutput.schema.properties = [propertyItem];
        }
      } else {
        if (currentOutput?.properties) {
          currentOutput.properties.push(propertyItem);
        } else {
          currentOutput.properties = [propertyItem];
        }
      }
      return {
        ...cloneDeep(old),
      };
    });
    canPublishSetNot();
  });

  const handleAdd = useMemoizedFn(() => handleAddItem(output));

  const handleRequiredChange = useMemoizedFn((e: RequiredChangeEvent) => {
    if (!output.id) return;
    handleChangeOutputParam(
      output.id,
      (data, value) => (data.required = value),
      e.target.checked
    );
  });

  const handleRemove = useMemoizedFn(() => {
    if (
      output.id &&
      !output?.deleteDisabled &&
      output?.customParameterType !== 'deepseekr1'
    ) {
      handleRemoveOutputLine(output.id);
      checkNode(id);
    }
  });

  return (
    <>
      <AddButton {...{ type, handleAdd }} />
      <RequiredCheckbox {...{ isStartNode, output, handleRequiredChange }} />
      <RemoveButton {...{ id, data, output, handleRemove }} />
    </>
  );
};

const useNodeHandle = ({ id, data }: NodeCommonProps): UseNodeHandleReturn => {
  const { nodeType } = useNodeInfo({ id, data });
  const showIterativeModal = useFlowsManager.getState().showIterativeModal;
  // 判断是否可连接
  const isConnectable = useMemo(() => {
    return showIterativeModal || !data?.parentId;
  }, [data?.parentId, showIterativeModal]);

  const hasSourceHandle = useMemo(() => {
    if (nodeType === 'node-end' || nodeType === 'iteration-node-end') {
      return false;
    }
    if (nodeType === 'decision-making') {
      return false;
    }
    if (nodeType === 'if-else') {
      return false;
    }
    if (data?.nodeParam?.answerType === 'option') {
      return false;
    }
    return true;
  }, [nodeType, data]);

  const sourceHandleId = useMemo(() => {
    return data?.nodeParam?.handlingEdge;
  }, [data?.nodeParam?.handlingEdge]);

  const exceptionHandleId = useMemo(() => {
    return data?.nodeParam?.exceptionHandlingEdge;
  }, [data?.nodeParam?.exceptionHandlingEdge, id]);

  const hasTargetHandle = useMemo(() => {
    if (['node-start', 'iteration-node-start']?.includes(nodeType)) {
      return false;
    }
    return true;
  }, [nodeType]);

  return {
    isConnectable,
    sourceHandleId,
    exceptionHandleId,
    hasTargetHandle,
    hasSourceHandle,
  };
};

const titleRender = (nodeData: {
  name: string;
  schema?: { type?: string };
  type?: string;
}): React.ReactElement => {
  return (
    <div className="flex items-center gap-2">
      <span>{nodeData.name}</span>
      <div className="bg-[#F0F0F0] px-2.5 py-0.5 rounded text-xs">
        {renderType(nodeData)}
      </div>
    </div>
  );
};

const useNodeInputRender = ({ id, data }: NodeCommonProps): UseNodeInputRenderReturn => {
  const { t } = useTranslation();
  const { isIteratorNode } = useNodeInfo({ id, data });
  const { handleChangeOutputParam } = useNodeFunc({ id, data });
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const delayCheckNode = currentStore(state => state.delayCheckNode);
  const canPublishSetNot = useFlowsManager(state => state.canPublishSetNot);
  const autoSaveCurrentFlow = useFlowsManager(
    state => state.autoSaveCurrentFlow
  );
  const setNode = currentStore(state => state.setNode);
  const updateNodeRef = currentStore(state => state.updateNodeRef);
  const takeSnapshot = currentStore(state => state.takeSnapshot);
  const checkNode = currentStore(state => state.checkNode);
  const [focusTextareaId, setFocusTextareaId] = useState('');
  const renderTypeInput = useMemoizedFn((output: OutputItem) => {
    return (
      <FlowNodeTextArea
        disabled={output?.customParameterType === 'deepseekr1'}
        placeholder={t('workflow.nodes.common.variableDescriptionPlaceholder')}
        maxLength={1000}
        row={focusTextareaId === output?.id ? 3 : 1}
        style={{
          height: focusTextareaId === output?.id ? 100 : 30,
        }}
        value={workflowValueToText(output?.schema?.default || output?.default)}
        onChange={(value: string) => {
          if (!output.id) return;
          handleChangeOutputParam(
            output.id,
            (data, value) => {
              if (data?.schema?.type) {
                data.schema.default = value;
              } else {
                data.default = value;
              }
            },
            value
          );
        }}
        onBlur={() => {
          delayCheckNode(id);
          setFocusTextareaId('');
        }}
        onFocus={() => { if (output.id) setFocusTextareaId(output.id); }}
      />
    );
  });
  const handleChangeInputParam = useMemoizedFn(
    <T,>(
      inputId: string,
      fn: (data: InputItem, value: T) => void,
      value: T
    ) => {
      setNode(id, old => {
        const currentInput = old?.data?.inputs?.find(
          (item: InputItem) => item?.id === inputId
        );
        if (currentInput) {
          fn(currentInput, value);
        }
        if (isIteratorNode) {
          const outputs = old?.data?.inputs?.map((input: InputItem) => ({
            id: input?.id,
            name: input?.name,
            schema: {
              type: input?.schema?.type?.split('-')?.pop() ?? '',
              default: '',
            },
          }));
          const iteratorStartNode = currentStore.getState().nodes.find(
            node =>
              node?.data?.parentId === id &&
              'nodeType' in node && node.nodeType === 'iteration-node-start'
          );
          if (iteratorStartNode) {
            setNode(iteratorStartNode.id, old => {
              old.data.outputs = outputs;
              return cloneDeep(old);
            });
            updateNodeRef(iteratorStartNode.id);
          }
        }
        return {
          ...cloneDeep(old),
        };
      });
      autoSaveCurrentFlow();
      canPublishSetNot();
    }
  ) as UseNodeInputRenderReturn['handleChangeInputParam'];
  const handleAddInputLine = useMemoizedFn(() => {
    takeSnapshot();
    setNode(id, old => {
      old.data.inputs.push({
        id: uuid(),
        name: '',
        schema: {
          type: 'string',
          value: {
            type: 'ref',
            content: {},
          },
        },
      });
      return {
        ...cloneDeep(old),
      };
    });
    checkNode(id);
    canPublishSetNot();
  });
  const handleRemoveInputLine = useMemoizedFn((inputId: string) => {
    takeSnapshot();
    setNode(id, old => {
      old.data.inputs = old.data.inputs.filter((item: InputItem) => item.id !== inputId);
      return {
        ...cloneDeep(old),
      };
    });
    canPublishSetNot();
    checkNode(id);
  });
  const allowNoInputParams = useMemo(() => {
    return (
      ([
        'knowledge-base',
        'knowledge-pro-base',
        'iteration',
        'extractor-parameter',
      ].includes(data?.nodeType ?? '') &&
        (data?.outputs?.length ?? 0) > 1) ||
      ![
        'knowledge-base',
        'knowledge-pro-base',
        'iteration',
        'extractor-parameter',
      ]?.includes(data?.nodeType ?? '')
    );
  }, [data]);

  return {
    allowNoInputParams,
    renderTypeInput,
    handleChangeInputParam,
    handleAddInputLine,
    handleRemoveInputLine,
  };
};

export const useNodeCommon = ({
  id,
  data,
}: NodeCommonProps): UseNodeCommonReturn => {
  const nodeInfo = useNodeInfo({ id, data });
  const nodeFunc = useNodeFunc({ id, data });
  const nodeInputRender = useNodeInputRender({ id, data });
  const { renderOutputComponent } = useNodeOutputRender({ id, data });
  const nodeModels = useNodeModels({ id, data });
  const nodeHandle = useNodeHandle({ id, data });
  const nodeOutputRender = useNodeOutputRender({ id, data });
  const canvasesDisabled = useFlowsManager(state => state.canvasesDisabled);

  const addUniqueComponentToProperties = useMemoizedFn(
    (schemasArray: OutputType[]): OutputType[] => {
      function addAgeToProperties(propertiesArray: PropertyItem[]): void {
        propertiesArray.forEach(property => {
          property.key = property.id;
          property.isChild = true;
          property.title = renderOutputComponent(property);
          if (property.properties && Array.isArray(property.properties)) {
            addAgeToProperties(property.properties as PropertyItem[]);
          }
        });
      }

      return schemasArray.map(schema => {
        const newSchema = { ...schema };
        if (newSchema.schema && newSchema.schema.properties) {
          addAgeToProperties(newSchema.schema.properties);
        }
        newSchema.title = renderOutputComponent(newSchema);
        newSchema.key = newSchema.id;
        newSchema.properties = newSchema.schema.properties;
        return newSchema;
      });
    }
  );

  const renderTypeOneClickUpdate = (): React.ReactElement | null => {
    const { nodeType } = useNodeInfo({ id, data });
    if (nodeType === 'agent') {
      return <AgentNodeOneClickUpdate id={id} data={data} />;
    } else if (nodeType === 'plugin') {
      return <ToolNodeOneClickUpdate id={id} data={data} />;
    } else if (nodeType === 'flow') {
      return <FlowNodeOneClickUpdate id={id} data={data} />;
    } else if (nodeType === 'rpa') {
      return <RpaNodeOneClickUpdate id={id} data={data} />;
    }
    return null;
  };

  return {
    titleRender,
    addUniqueComponentToProperties,
    renderTypeOneClickUpdate,
    canvasesDisabled,
    ...nodeInfo,
    ...nodeFunc,
    ...nodeModels,
    ...nodeOutputRender,
    ...nodeHandle,
    ...nodeInputRender,
  };
};
