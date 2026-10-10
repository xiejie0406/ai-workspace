import React, { useRef, useState, useMemo } from 'react';
import { useClickAway } from 'ahooks';
import { FlowInputNumber } from '@/components/workflow/ui';
import { Tooltip, Switch, Slider } from 'antd';
import { v4 as uuid } from 'uuid';
import useFlowsManager from '@/components/workflow/store/use-flows-manager';
import { useTranslation } from 'react-i18next';
import type { NodeDataType, NodeParameters } from '@/components/workflow/types';
import type { UseNodeFuncReturn } from '@/components/workflow/types/hooks';

interface AnswerOption {
  id: string;
  name: string;
  type: number;
  content?: string;
  content_type?: string;
  [key: string]: unknown;
}
interface QuestionAnswerSettingsParameters extends NodeParameters {
  answerType?: string;
  needReply?: boolean;
  timeout?: number | null;
  optionAnswer?: AnswerOption[];
  directAnswer?: { maxRetryCounts?: number | null; [key: string]: unknown };
}
interface AnswerSettingsProps {
  data: NodeDataType<QuestionAnswerSettingsParameters>;
  handleChangeNodeParam: UseNodeFuncReturn['handleChangeNodeParam'];
}
function updateRetryCount(data: NodeDataType, value: number | null): void {
  const params = data.nodeParam as QuestionAnswerSettingsParameters;
  (params.directAnswer ??= {}).maxRetryCounts = value;
}

import answerSettings from '@/assets/imgs/workflow/answer-settings.svg';
import close from '@/assets/imgs/workflow/modal-close.png';
import answerSettingsParams from '@/assets/imgs/workflow/answer-settings-params.svg';

const UserMustAnswer = ({
  data,
  handleChangeNodeParam,
}: AnswerSettingsProps): React.ReactElement => {
  const { t } = useTranslation();
  const getCurrentStore = useFlowsManager(state => state.getCurrentStore);
  const currentStore = getCurrentStore();
  const setEdges = currentStore(state => state.setEdges);
  const removeNodeRef = currentStore(state => state.removeNodeRef);
  const defaultOptionIds = useMemo(() => new Set(
    data.nodeParam.answerType === 'option'
      ? (data.nodeParam.optionAnswer ?? []).filter(item => item.type === 1 && typeof item.id === 'string' && item.id.length > 0).map(item => item.id)
      : []
  ), [data.nodeParam.answerType, data.nodeParam.optionAnswer]);

  return (
    <div className="w-full flex items-center justify-between">
      <div className="flex items-center gap-1">
        <span>{t('workflow.nodes.questionAnswerNode.userMustAnswer')}</span>
        <Tooltip
          title={t('workflow.nodes.questionAnswerNode.userMustAnswerTip')}
          overlayClassName="black-tooltip"
        >
          <img src={answerSettingsParams} className="w-3 h-3" alt="" />
        </Tooltip>
      </div>
      <Switch
        className="list-switch config-switch"
        checked={data?.nodeParam?.needReply}
        onChange={value => {
          if (value && defaultOptionIds.size > 0) {
            setEdges(edges => {
              const removed = edges.filter(edge => edge.sourceHandle != null && defaultOptionIds.has(edge.sourceHandle));
              if (removed.length === 0) return edges;
              const remaining = edges.filter(edge => edge.sourceHandle == null || !defaultOptionIds.has(edge.sourceHandle));
              const visited = new Set<string>();
              removed.forEach(edge => {
                const pair = JSON.stringify([edge.source, edge.target]);
                if (visited.has(pair) || remaining.some(item => item.source === edge.source && item.target === edge.target)) return;
                visited.add(pair);
                // 同一引用清理helper使用最终图，并带回被删除边以定位受影响后继。
                removeNodeRef(edge.source, edge.target, [edge, ...remaining]);
              });
              return remaining;
            });
          }
          handleChangeNodeParam((data, value) => {
            const params = data.nodeParam as QuestionAnswerSettingsParameters;
            params.needReply = value;
            if (params.answerType !== 'option') return;
            if (!value) {
              const options = (params.optionAnswer ??= []);
              if (options.some(item => item.type === 1)) return;
              options.push({
                id: `option-one-of::${uuid()}`,
                name: 'default',
                type: 1,
                content: '',
                content_type: 'string',
              });
            } else {
              params.optionAnswer =
                params.optionAnswer?.filter(item => item.type === 2);
            }
          }, value);
        }}
      />
    </div>
  );
};

const ConversationTimeout = ({
  data,
  handleChangeNodeParam,
}: AnswerSettingsProps): React.ReactElement => {
  const { t } = useTranslation();
  return (
    <div className="w-full flex items-center gap-3">
      <div className="flex items-center gap-1 w-[128px]">
        <span>
          {t('workflow.nodes.questionAnswerNode.conversationTimeout')}
        </span>
        <Tooltip
          title={t('workflow.nodes.questionAnswerNode.conversationTimeoutTip')}
          overlayClassName="black-tooltip"
        >
          <img src={answerSettingsParams} className="w-3 h-3" alt="" />
        </Tooltip>
      </div>
      <Slider
        min={2}
        max={5}
        step={1}
        value={data.nodeParam.timeout ?? undefined}
        className="flex-1 config-slider nodrag"
        onChange={value =>
          handleChangeNodeParam(
            (data, value) => (data.nodeParam.timeout = value),
            value
          )
        }
      />
      <div className="flex items-center gap-2.5">
        <FlowInputNumber
          value={data?.nodeParam?.timeout}
          onChange={(value: number | null) =>
            handleChangeNodeParam(
              (data, value) => (data.nodeParam.timeout = value),
              value
            )
          }
          onBlur={() => {
            if (data?.nodeParam?.timeout === null) {
              handleChangeNodeParam(
                (data, value) => (data.nodeParam.timeout = value),
                3
              );
            }
          }}
          min={2}
          max={5}
          precision={0}
          className="nodrag w-[40px] input-number-bg-white"
          controls={false}
        />
        <span className="text-xss font-medium text-[#7F7F7F]">
          {t('workflow.nodes.questionAnswerNode.minute')}
        </span>
      </div>
    </div>
  );
};

const MaxRetrySettings = ({
  data,
  handleChangeNodeParam,
}: AnswerSettingsProps): React.ReactElement | null => {
  const { t } = useTranslation();
  if (data?.nodeParam?.answerType == 'direct') {
    return null;
  }

  return (
    <div className="w-full flex items-center gap-3">
      <div className="flex items-center gap-1 w-[128px]">
        <span>{t('workflow.nodes.questionAnswerNode.maxRetrySettings')}</span>
        <Tooltip
          title={t('workflow.nodes.questionAnswerNode.maxRetrySettingsTip')}
          overlayClassName="black-tooltip"
        >
          <img src={answerSettingsParams} className="w-3 h-3" alt="" />
        </Tooltip>
      </div>
      <Slider
        min={2}
        max={5}
        step={1}
        value={data.nodeParam.directAnswer?.maxRetryCounts ?? undefined}
        className="flex-1 config-slider nodrag"
        onChange={value =>
          handleChangeNodeParam(
            updateRetryCount,
            value
          )
        }
      />
      <div className="flex items-center gap-2.5">
        <FlowInputNumber
          value={data?.nodeParam?.directAnswer?.maxRetryCounts}
          onChange={(value: number | null) =>
            handleChangeNodeParam(
              updateRetryCount,
              value
            )
          }
          onBlur={() => {
            if (data?.nodeParam?.directAnswer?.maxRetryCounts === null) {
              handleChangeNodeParam(
                updateRetryCount,
                3
              );
            }
          }}
          min={2}
          max={5}
          precision={0}
          className="nodrag w-[40px] input-number-bg-white"
          controls={false}
        />
        <span className="text-xss font-medium text-[#7F7F7F]">
          {t('workflow.nodes.questionAnswerNode.times')}
        </span>
      </div>
    </div>
  );
};

function Index({ data, handleChangeNodeParam }: AnswerSettingsProps): React.ReactElement {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false); // 默认关闭
  const ref = useRef<HTMLDivElement | null>(null);

  useClickAway(() => {
    setVisible(false);
  }, ref);

  return (
    <div className="relative" ref={ref}>
      <img
        src={answerSettings}
        className="w-[13px] h-[13px] cursor-pointer"
        alt=""
        onClick={e => {
          e.stopPropagation(); // 阻止冒泡
          setVisible(!visible);
        }}
      />
      {visible && (
        <div
          className="absolute rounded-lg bg-[#fff] z-50 w-[400px] p-5 right-0 top-[21px]"
          style={{ boxShadow: '0px 2px 4px 0px rgba(46, 51, 68, 0.2)' }}
          onClick={e => {
            e.stopPropagation();
          }}
        >
          <div className="w-full flex items-center justify-between">
            <span className="font-medium text-base">
              {t('workflow.nodes.questionAnswerNode.answerSettings')}
            </span>
            <img
              src={close}
              className="w-3 h-3 cursor-pointer"
              alt=""
              onClick={e => {
                e.stopPropagation();
                setVisible(false);
              }}
            />
          </div>
          <div className="mt-8 flex flex-col gap-5">
            <UserMustAnswer
              data={data}
              handleChangeNodeParam={handleChangeNodeParam}
            />
            <ConversationTimeout
              data={data}
              handleChangeNodeParam={handleChangeNodeParam}
            />
            <MaxRetrySettings
              data={data}
              handleChangeNodeParam={handleChangeNodeParam}
            />
          </div>
        </div>
      )}
    </div>
  );
}

export default Index;
