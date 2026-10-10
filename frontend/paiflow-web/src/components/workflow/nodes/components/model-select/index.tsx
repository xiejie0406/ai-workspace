import React, { useState, useMemo, useRef, memo } from 'react';
import { useMemoizedFn } from 'ahooks';
import { cloneDeep } from 'lodash';
import { Tooltip } from 'antd';
import { v4 as uuid } from 'uuid';
import { useTranslation } from 'react-i18next';
import { FlowSelect } from '@/components/workflow/ui';
import useFlowsManager from '@/components/workflow/store/use-flows-manager';
import ModelParams, { readModelInfo, readModelFields, serviceModelFields, modelParameterKey, modelRecord } from '../model-params';
import type { ModelInfo } from '../model-params';
import type { NodeDataType, NodeParameters, InputsProps } from '@/components/workflow/types';
import type { WorkflowModelSummary } from '@/components/workflow/types/hooks';
import type { TFunction } from 'i18next';
import useUserStore from '@/store/user-store';
import {
  getCustomModelConfigDetail,
  getModelConfigDetail,
} from '@/services/common';
import {
  checkedNodeOutputData,
  generateOrUpdateObject,
} from '@/components/workflow/utils/reactflowUtils';
import { isJSON } from '@/utils';
import { useNodeCommon } from '@/components/workflow/hooks/use-node-common';
import dayjs from 'dayjs';

import debuggerIcon from '@/assets/imgs/workflow/debugger-icon.png';
import inputErrorMsg from '@/assets/imgs/plugin/input_error_msg.svg';

// 模型标签
const ModelTags = ({ tags = [] }: { tags?: string[] }): React.ReactElement | null => {
  if (!tags?.length) return null;
  return (
    <div className="text-sm flex items-center gap-2">
      {tags?.slice(0, 2).map((item, index) => (
        <span
          key={index}
          className="rounded text-xss bg-[#ecefff] px-2 max-w-[80px] text-overflow"
          title={item}
        >
          {item}
        </span>
      ))}
      {tags?.length > 2 && (
        <Tooltip
          title={
            <div className="flex flex-wrap">
              {tags?.map((item, index) => (
                <span
                  key={index}
                  className="rounded text-xss bg-[#ecefff] mb-1 mr-1 px-2 py-1"
                  title={item}
                >
                  {item}
                </span>
              ))}
            </div>
          }
          overlayClassName="white-tooltip"
        >
          <span className="rounded text-xss bg-[#ecefff] px-2 text-[333] text-sm">
            +{tags?.length - 2}
          </span>
        </Tooltip>
      )}
    </div>
  );
};

// ========== 提取逻辑函数 ==========
const getTooltipTitle = (model: ModelInfo, nodeParam: NodeParameters, t: TFunction): string => {
  if (nodeParam?.modelEnabled === false && model?.llmId === nodeParam?.llmId) {
    return t('workflow.nodes.modelSelect.modelOffShelf');
  }
  if (model?.shelfStatus === 1) {
    return t('workflow.nodes.modelSelect.modelOffShelfTip', {
      time: dayjs(model?.shelfOffTime).format('YYYY年M月D日H时m分s秒'),
    });
  }
  return '';
};

interface ModelItemProps { model: ModelInfo; nodeParam: NodeParameters; t: TFunction }
const ModelStatusBlock = ({ model, nodeParam, t }: ModelItemProps): React.ReactElement | null => {
  const isOffShelf =
    model?.shelfStatus === 1 ||
    (nodeParam?.modelEnabled === false && model?.llmId === nodeParam?.llmId);

  if (!isOffShelf) return null;

  return (
    <div className="flex items-center gap-1">
      <img src={inputErrorMsg} className="w-[14px] h-[14px]" alt="" />
      <p className="text-[#F74E43] text-xs">
        {model?.shelfStatus === 1
          ? t('workflow.nodes.modelSelect.willOffShelf')
          : t('workflow.nodes.modelSelect.offShelf')}
      </p>
    </div>
  );
};

// ========== 主渲染 ==========
const ModelItem = ({ model, nodeParam, t }: ModelItemProps): React.ReactElement => (
  <Tooltip
    title={getTooltipTitle(model, nodeParam, t)}
    overlayClassName="black-tooltip"
  >
    <div className="w-full flex items-start justify-between overflow-hidden">
      <div className="flex items-start gap-2 flex-1 overflow-hidden">
        <div className="flex items-center gap-2">
          <img src={model.icon} className="w-[20px] h-[20px] flex-shrink-0" />
          <span
            className="text-xs"
            style={{
              color:
                nodeParam?.modelEnabled === false &&
                model?.llmId === nodeParam?.llmId
                  ? '#F74E43'
                  : '',
            }}
          >
            {model.name || (typeof nodeParam.modelName === 'string' ? nodeParam.modelName : '')}
          </span>
          <ModelStatusBlock model={model} nodeParam={nodeParam} t={t} />
        </div>
        <ModelTags tags={model?.tag} />
      </div>
    </div>
  </Tooltip>
);

const useModelSelect = (
  id: string,
  models: WorkflowModelSummary[]
): { handleModelChange: (data: NodeDataType, value: string | number) => void } => {
  const selectionGeneration = useRef(0);
  const { currentNode } = useNodeCommon({
    id,
  });
  const { t } = useTranslation();
  const user = useUserStore(state => state.user);
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const autoSaveCurrentFlow = useFlowsManager(
    state => state.autoSaveCurrentFlow
  );
  const updateNodeRef = currentStore(state => state.updateNodeRef);
  const setNode = currentStore(state => state.setNode);
  const handleResetModelParams = useMemoizedFn((currentSelectModel: ModelInfo, generation: number) => {
    if (currentSelectModel?.llmSource === 0) {
      getCustomModelConfigDetail(
        String(currentSelectModel.id),
        String(currentSelectModel.llmSource)
      ).then(data => {
        if (selectionGeneration.current !== generation) return;
        const config = readModelFields(data).filter(item =>
          item.constraintType === 'range' || item.constraintType === 'switch');
        let applied = false;
        setNode(id, old => {
          if (old.data.nodeParam.llmId !== currentSelectModel.llmId || old.data.nodeParam.modelId !== currentSelectModel.id) return old;
          const extraParams: Record<string, unknown> = {};
          config.forEach(item => {
            extraParams[item?.key] = item?.default;
            Reflect.deleteProperty(old.data.nodeParam, item.key);
          });
          old.data.nodeParam.extraParams = extraParams;
          applied = true;
          return {
            ...cloneDeep(old),
          };
        });
        if (applied) autoSaveCurrentFlow();
      });
    } else {
      getModelConfigDetail(
        String(currentSelectModel.llmId),
        String(currentSelectModel.llmSource)
      ).then(modelDetail => {
        if (selectionGeneration.current !== generation) return;
        const configs = serviceModelFields(modelRecord(modelDetail) ? modelDetail.config : undefined, currentSelectModel.serviceId).filter(
          item =>
            item.constraintType === 'range' || item.constraintType === 'switch'
        );
        let applied = false;
        setNode(id, old => {
          if (old.data.nodeParam.llmId !== currentSelectModel.llmId || old.data.nodeParam.modelId !== currentSelectModel.id) return old;
          configs.forEach(item => {
            old.data.nodeParam[modelParameterKey(item.key)] = item.default;
          });
          applied = true;
          return {
            ...cloneDeep(old),
          };
        });
        if (applied) autoSaveCurrentFlow();
      });
    }
  });
  const handleSparkLLMOutputs = useMemoizedFn((data: NodeDataType, value: ModelInfo) => {
    if (
      (data.nodeParam.serviceId === 'xdeepseekr1' ||
        data?.nodeParam?.isThink) &&
      !value?.isThink
    ) {
      data.outputs = data?.outputs?.filter(
        item => item.customParameterType !== 'deepseekr1'
      );
    }

    if (
      !data?.nodeParam?.isThink &&
      (value.serviceId === 'xdeepseekr1' || value?.isThink)
    ) {
      data.outputs = [
        {
          id: uuid(),
          customParameterType: 'deepseekr1',
          name: 'REASONING_CONTENT',
          nameErrMsg: '',
          schema: {
            default: t('workflow.nodes.modelSelect.modelThinkingProcess'),
            type: 'string',
          },
        },
        ...(data.outputs ?? []),
      ];
    }
  });

  const handleSparkLLMInputs = useMemoizedFn((data: NodeDataType, value: ModelInfo) => {
    data.inputs ??= [];
    if (value.serviceId === 'image_understanding' || value?.multiMode) {
      data.inputs.unshift({
        id: uuid(),
        customParameterType: 'image_understanding',
        name: 'SYSTEM_IMAGE',
        schema: {
          type: 'string',
          value: { content: {}, type: 'ref' },
        },
      });
    }
    if (
      data.nodeParam.serviceId === 'image_understanding' ||
      data?.nodeParam?.multiMode
    ) {
      data.inputs.shift();
    }
  });

  const handleRetryConfig = useMemoizedFn((data: NodeDataType) => {
    if (data?.retryConfig?.errorStrategy !== 1) return;

    if (!checkedNodeOutputData(data.outputs ?? [], currentNode)) {
      data.retryConfig.customOutput = JSON.stringify({ output: '' }, null, 2);
      data.nodeParam.setAnswerContentErrMsg =
        '输出中变量名校验不通过,自动生成JSON失败';
    } else {
      data.retryConfig.customOutput = JSON.stringify(
        generateOrUpdateObject(
          data.outputs ?? [],
          isJSON(data.retryConfig.customOutput ?? '')
            ? JSON.parse(data.retryConfig.customOutput ?? '')
            : null
        ),
        null,
        2
      );
      data.nodeParam.setAnswerContentErrMsg = '';
    }
  });

  const updateNodeParams = useMemoizedFn((data: NodeDataType, value: ModelInfo) => {
    data.nodeParam.uid = user?.uid?.toString();
    data.nodeParam.llmId = value.llmId;
    data.nodeParam.domain = value.domain;
    data.nodeParam.serviceId = value.serviceId;
    data.nodeParam.patchId = value.patchId;
    data.nodeParam.url = value.url;
    data.nodeParam.modelId = value.id;
    data.nodeParam.isThink = value.isThink;
    data.nodeParam.multiMode = value.multiMode;
    data.nodeParam.modelName = value.name;
    data.nodeParam.modelEnabled = true;
    data.nodeParam.llmIdErrMsg = '';

    if (value.llmSource === 0) {
      data.nodeParam.source = 'openai';
    } else {
      Reflect.deleteProperty(data.nodeParam, 'source');
      Reflect.deleteProperty(data.nodeParam, 'extraParams');
    }
  });

  const handleModelChange = useMemoizedFn((data: NodeDataType, value: string | number) => {
    const selectedModel = models.find(model => model.llmId === value);
    if (!selectedModel) return;
    const currentModel = readModelInfo(selectedModel);
    const generation = ++selectionGeneration.current;
    if (id?.startsWith('spark-llm')) {
      handleSparkLLMOutputs(data, currentModel);
      handleSparkLLMInputs(data, currentModel);
      handleRetryConfig(data);
    }
    if (data.nodeParam.isThink !== currentModel.isThink) {
      updateNodeRef(id);
    }
    updateNodeParams(data, currentModel);
    setNode(id, old => {
      return {
        ...cloneDeep({
          ...old,
          data,
        }),
      };
    });
    handleResetModelParams(currentModel, generation);
  });

  return {
    handleModelChange,
  };
};

function index({ id, data }: Pick<InputsProps, 'id' | 'data'>): React.ReactElement {
  const { handleChangeNodeParam, nodeParam, models: modelSummaries } = useNodeCommon({
    id,
    data,
  });
  const models = useMemo(() => modelSummaries.map(readModelInfo), [modelSummaries]);
  const { handleModelChange } = useModelSelect(id, models);
  const { t } = useTranslation();
  const canvasesDisabled = useFlowsManager(state => state.canvasesDisabled);
  const [showModelParmas, setShowModelParmas] = useState(false);

  const currentSelectModel = useMemo(() => {
    return models.find(model => model.llmId === nodeParam?.llmId);
  }, [nodeParam, models]);

  return (
    <div className="rounded-md relative">
      <div className="flex items-center gap-2">
        <div className="flex-1">
          <FlowSelect
            popupClassName="overscroll-contain flow-model-select-dropdown"
            getPopupContainer={(triggerNode: HTMLElement) => triggerNode.parentElement ?? triggerNode}
            value={currentSelectModel ? nodeParam.llmId : typeof nodeParam.modelName === 'string' ? nodeParam.modelName : undefined}
            onChange={(value: string | number) => handleModelChange(data, value)}
          >
            {models.map(model => (
              <FlowSelect.Option key={model.llmId} value={model.llmId}>
                <ModelItem model={model} nodeParam={nodeParam} t={t} />
              </FlowSelect.Option>
            ))}
          </FlowSelect>
        </div>
        {!canvasesDisabled && (
          <div
            className="relative p-1.5 mb-0.5 border border-[#f5f7fc] shadow-sm rounded-lg cursor-pointer bg-[#fff]"
            onClick={e => {
              e.stopPropagation();
              setShowModelParmas(true);
            }}
          >
            <img src={debuggerIcon} className="w-4 h-4" alt="" />
          </div>
        )}
        {showModelParmas && (
          <ModelParams
            setShowModelParmas={setShowModelParmas}
            currentSelectModel={currentSelectModel}
            nodeParam={nodeParam}
            handleChangeNodeParam={handleChangeNodeParam}
          />
        )}
      </div>
      {typeof nodeParam.llmIdErrMsg === 'string' && nodeParam.llmIdErrMsg && (
        <p className="text-xs text-[#F74E43]">{nodeParam?.llmIdErrMsg}</p>
      )}
    </div>
  );
}

export default memo(index);
