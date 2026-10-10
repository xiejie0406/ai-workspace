import React, { useEffect, useState, useRef, memo } from 'react';
import { Tooltip, Slider, Switch } from 'antd';
import { useMemoizedFn } from 'ahooks';
import { FlowInputNumber } from '@/components/workflow/ui';
import { useTranslation } from 'react-i18next';
import type { NodeDataType, NodeParameters } from '@/components/workflow/types';
import type { UseNodeFuncReturn, WorkflowModelSummary } from '@/components/workflow/types/hooks';
import type { ModelInfo as PlatformModelInfo, ModelConfigParam } from '@/types/model';

export interface ModelField extends Partial<Pick<ModelConfigParam, 'name' | 'desc' | 'precision'>> {
  key: string;
  constraintType?: string;
  constraintContent?: Array<{ name?: number }>;
  default?: unknown;
  [key: string]: unknown;
}
export type ModelInfo = WorkflowModelSummary & Partial<Pick<PlatformModelInfo,
  'serviceId' | 'name' | 'domain' | 'patchId' | 'url' | 'isThink' | 'multiMode' | 'icon' | 'tag'>> & {
  id?: string | number;
  llmSource?: string | number;
  shelfStatus?: number;
  shelfOffTime?: string | number | null;
};
export function modelRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export function modelNumber(value: unknown): number | undefined {
  if (typeof value !== 'number' && typeof value !== 'string') return undefined;
  if (value === '') return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}
export function readModelInfo(model: WorkflowModelSummary): ModelInfo {
  const scalar = (value: unknown): string | number | undefined =>
    typeof value === 'string' || typeof value === 'number' ? value : undefined;
  const string = (value: unknown): string | undefined => typeof value === 'string' ? value : undefined;
  const bool = (value: unknown): boolean | undefined => typeof value === 'boolean' ? value : undefined;
  return { ...model, id: scalar(model.id), llmSource: scalar(model.llmSource),
    serviceId: string(model.serviceId), name: string(model.name), domain: string(model.domain),
    patchId: string(model.patchId), url: string(model.url), icon: string(model.icon),
    isThink: bool(model.isThink), multiMode: bool(model.multiMode),
    tag: Array.isArray(model.tag) ? model.tag.filter((tag): tag is string => typeof tag === 'string') : undefined,
    shelfStatus: typeof model.shelfStatus === 'number' ? model.shelfStatus : undefined,
    shelfOffTime: model.shelfOffTime === null ? null : scalar(model.shelfOffTime) };
}
export function readModelFields(value: unknown): ModelField[] {
  if (!Array.isArray(value)) throw new TypeError('模型参数配置必须为数组');
  return value.map(field => {
    if (!modelRecord(field) || typeof field.key !== 'string') throw new TypeError('模型参数缺少有效key');
    return { ...field, key: field.key,
      name: typeof field.name === 'string' ? field.name : undefined,
      desc: typeof field.desc === 'string' ? field.desc : undefined,
      constraintType: typeof field.constraintType === 'string' ? field.constraintType : undefined,
      precision: modelNumber(field.precision),
      constraintContent: Array.isArray(field.constraintContent) ? field.constraintContent.map(bound => ({
        ...(modelRecord(bound) ? bound : {}), name: modelRecord(bound) ? modelNumber(bound.name) : undefined,
      })) : undefined };
  });
}
export function serviceModelFields(config: unknown, serviceId?: string): ModelField[] {
  if (!modelRecord(config) || !modelRecord(config.serviceBlock)) return [];
  const blocks = config.serviceBlock;
  const fields = (key: string): unknown => {
    const block = blocks[key];
    return Array.isArray(block) && modelRecord(block[0]) ? block[0].fields : undefined;
  };
  return readModelFields((serviceId ? fields(serviceId) : undefined) ?? fields('@@serviceId@@') ?? []);
}
export function modelParameterKey(key: string): string {
  return ({ max_tokens: 'maxTokens', top_k: 'topK', search_disable: 'searchDisable' } as Record<string, string>)[key] ?? key;
}
type ChangeNodeParameter = UseNodeFuncReturn['handleChangeNodeParam'];
type ChangeModelParameter = (data: NodeDataType, item: ModelField, value: unknown) => void;
interface ParamProps {
  item: ModelField;
  nodeParam: NodeParameters;
  currentSelectModel?: ModelInfo;
  handleChangeNodeParam: ChangeNodeParameter;
  handleDifferentModel: ChangeModelParameter;
}
interface ModelParamsProps {
  setShowModelParmas: React.Dispatch<React.SetStateAction<boolean>>;
  currentSelectModel?: ModelInfo;
  nodeParam: NodeParameters;
  handleChangeNodeParam: ChangeNodeParameter;
}

import debuggerIcon from '@/assets/imgs/workflow/debugger-icon.png';
import close from '@/assets/imgs/workflow/modal-close.png';
import questionMark from '@/assets/imgs/common/questionmark.png';

// ----------------- hooks -----------------
function useClickOutside(
  ref: React.RefObject<HTMLDivElement>,
  onClose: () => void
): void | (() => void) {
  useEffect(() => {
    function handleClick(e: MouseEvent): void {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.body.addEventListener('click', handleClick);
    return (): void => document.body.removeEventListener('click', handleClick);
  }, [ref, onClose]);
}

function useConfigs(currentSelectModel: ModelInfo | undefined, setConfigs: React.Dispatch<React.SetStateAction<ModelField[]>>): void {
  useEffect(() => {
    if (!currentSelectModel) return;

    try {
      if (currentSelectModel?.llmSource === 2) {
        const configs = serviceModelFields(currentSelectModel.config, currentSelectModel.serviceId)
          .map(item => ({ ...item, key: modelParameterKey(item.key) }));
        setConfigs(configs);
      } else {
        const configs = readModelFields(JSON.parse(typeof currentSelectModel.config === 'string' ? currentSelectModel.config : '[]')).map(
          item => ({
            ...item,
            desc: item?.name,
            name: item?.key,
          })
        );
        setConfigs(configs);
      }
    } catch {
      return;
    }
  }, [currentSelectModel]);
}

// ----------------- 子组件 -----------------
function ParamSwitch({
  item,
  nodeParam,
  currentSelectModel,
  handleChangeNodeParam,
  handleDifferentModel,
}: ParamProps): React.ReactElement {
  return (
    <Switch
      className="list-switch config-switch"
      checked={
        currentSelectModel?.llmSource === 0
          ? Boolean(modelRecord(nodeParam.extraParams) && nodeParam.extraParams[item.key])
          : !nodeParam[item.key]
      }
      onChange={val =>
        handleChangeNodeParam(
          (data, v) => handleDifferentModel(data, item, v),
          currentSelectModel?.llmSource === 0 ? val : !val
        )
      }
    />
  );
}

function ParamRange({
  item,
  nodeParam,
  handleChangeNodeParam,
  handleDifferentModel,
}: Omit<ParamProps, 'currentSelectModel'>): React.ReactElement {
  const extraParams = modelRecord(nodeParam.extraParams) ? nodeParam.extraParams : undefined;
  const rawValue = nodeParam[item.key] ?? extraParams?.[item.key];
  const value = rawValue === null ? null : modelNumber(rawValue);

  return (
    <div className="w-full flex items-center justify-between">
      <Slider
        min={item?.constraintContent?.[0]?.name}
        max={item?.constraintContent?.[1]?.name}
        step={item?.precision || 1}
        value={value ?? undefined}
        className="flex-1 config-slider nodrag"
        onChange={(val: number | null) =>
          handleChangeNodeParam(
            (data, v) => handleDifferentModel(data, item, v),
            val
          )
        }
      />
      <FlowInputNumber
        className="global-inputnumber-center ml-[18px] pt-1.5 pl-0.5 w-[60px] text-center nodrag"
        value={value}
        onChange={(val: number | null) =>
          handleChangeNodeParam(
            (data, v) => handleDifferentModel(data, item, v),
            val
          )
        }
        onBlur={() => {
          if (
            modelRecord(nodeParam.extraParams) && nodeParam.extraParams[item.key] === null
          ) {
            handleChangeNodeParam(
              data => {
                if (modelRecord(data.nodeParam.extraParams)) data.nodeParam.extraParams[item.key] = item.default;
              },
              item.default
            );
          }
          if (nodeParam?.[item.key] === null) {
            handleChangeNodeParam(
              data => (data.nodeParam[item.key] = item.default),
              item.default
            );
          }
        }}
        step={item?.precision || 1}
        min={item?.constraintContent?.[0]?.name}
        max={item?.constraintContent?.[1]?.name}
        controls={false}
      />
    </div>
  );
}

function ParamItem({
  item,
  nodeParam,
  currentSelectModel,
  handleChangeNodeParam,
  handleDifferentModel,
}: ParamProps): React.ReactElement {
  return (
    <div>
      <div className="flex items-center gap-1 justify-between">
        <div className="flex items-center gap-1">
          <span>{item.name}</span>
          {item.desc && (
            <Tooltip
              title={item.desc}
              overlayClassName="black-tooltip config-secret"
            >
              <img src={questionMark} width={16} className="ml-1" alt="" />
            </Tooltip>
          )}
        </div>
        {item.constraintType === 'switch' && (
          <ParamSwitch
            {...{
              item,
              nodeParam,
              currentSelectModel,
              handleChangeNodeParam,
              handleDifferentModel,
            }}
          />
        )}
      </div>
      {item.constraintType === 'range' && (
        <ParamRange
          {...{ item, nodeParam, handleChangeNodeParam, handleDifferentModel }}
        />
      )}
    </div>
  );
}

// ----------------- 主组件 -----------------
function ModelParams({
  setShowModelParmas,
  currentSelectModel,
  nodeParam,
  handleChangeNodeParam,
}: ModelParamsProps): React.ReactElement {
  const { t } = useTranslation();
  const paramsRef = useRef<HTMLDivElement | null>(null);
  const [configs, setConfigs] = useState<ModelField[]>([]);

  useConfigs(currentSelectModel, setConfigs);
  useClickOutside(paramsRef, () => setShowModelParmas(false));

  const handleDifferentModel = useMemoizedFn<ChangeModelParameter>((data, item, value) => {
    if (currentSelectModel?.llmSource === 0) {
      Reflect.deleteProperty(data.nodeParam, item.key);
      data.nodeParam.extraParams = {
        ...(modelRecord(data.nodeParam.extraParams) ? data.nodeParam.extraParams : {}),
        [item.key]: value,
      };
    } else {
      data.nodeParam[item.key] = value;
    }
  });

  return (
    <div
      ref={paramsRef}
      className="absolute right-[-3px] top-8 border border-[#f5f7fc] bg-[#fff] rounded-lg p-4"
      style={{
        zIndex: 100,
        width: 'calc(100% - 4px)',
        boxShadow: '0px 4px 10px 0px rgba(0, 0, 0, 0.3)',
      }}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <img src={debuggerIcon} className="w-3 h-3" alt="" />
          <span className="font-medium text-base">
            {t('workflow.nodes.modelSelect.modelParamsSettings')}
          </span>
        </div>
        <img
          src={close}
          className="w-3 h-3 cursor-pointer"
          alt=""
          onClick={e => {
            e.stopPropagation();
            setShowModelParmas(false);
          }}
        />
      </div>
      <div className="flex flex-col gap-2 w-full text-second font-medium mt-4">
        {configs
          ?.filter(item =>
            ['range', 'switch'].includes(item.constraintType ?? '')
          )
          ?.map((item, index) => (
            <ParamItem
              key={index}
              {...{
                item,
                nodeParam,
                currentSelectModel,
                handleChangeNodeParam,
                handleDifferentModel,
              }}
            />
          ))}
      </div>
    </div>
  );
}

export default memo(ModelParams);
