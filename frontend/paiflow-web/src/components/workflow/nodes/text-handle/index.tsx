import React, { useMemo, useCallback, memo, useState } from 'react';
import {
  FLowTree,
  FLowCollapse,
  FlowTemplateEditor,
} from '@/components/workflow/ui';
import { Button, Input, Select } from 'antd';
import { cloneDeep } from 'lodash';
import { v4 as uuid } from 'uuid';
import useFlowsManager from '@/components/workflow/store/use-flows-manager';
import Inputs from '@/components/workflow/nodes/components/inputs';
import { useTranslation } from 'react-i18next';
import { useNodeCommon } from '@/components/workflow/hooks/use-node-common';
import type { TFunction } from 'i18next';
import type { ChangeTextNodeParameter, TextHandleDetailProps, TextNodeParameters, TextNodeParameterKey } from '@/components/workflow/types';

import inputAddIcon from '@/assets/imgs/workflow/input-add-icon.png';
import formSelect from '@/assets/imgs/main/icon_nav_dropdown.svg';

// ===================== 子组件 =====================
interface ModeSelectorProps {
  id: string;
  nodeParam: TextNodeParameters;
  handleChangeNodeParam: ChangeTextNodeParameter;
  updateNodeRef: (id: string) => void;
  t: TFunction;
}

const ModeSelector = ({
  id,
  nodeParam,
  handleChangeNodeParam,
  updateNodeRef,
  t,
}: ModeSelectorProps): React.ReactElement => (
  <FLowCollapse
    label={<div className="text-base font-medium">处理方式</div>}
    content={
      <div className="rounded-md px-[18px] pb-3 pointer-events-auto">
        <div className="flex items-center gap-2 bg-[#E7EAF3] p-1 rounded-md">
          <div
            className={`flex-1 rounded-md text-center p-1 ${nodeParam?.mode === 0 || nodeParam?.mode === undefined ? 'bg-[#fff]' : ''}`}
            onClick={() => {
              handleChangeNodeParam('mode', 0);
              updateNodeRef(id);
            }}
          >
            {t('workflow.nodes.textJoinerNode.stringConcatenation')}
          </div>
          <div
            className={`flex-1 rounded-md text-center p-1 ${nodeParam?.mode === 1 ? 'bg-[#fff]' : ''}`}
            onClick={() => {
              handleChangeNodeParam('mode', 1);
              updateNodeRef(id);
            }}
          >
            {t('workflow.nodes.textJoinerNode.stringSplitting')}
          </div>
        </div>
      </div>
    }
  />
);

interface RuleSectionProps extends TextHandleDetailProps {
  nodeParam: TextNodeParameters;
  handleChangeNodeParam: (key: 'prompt', value: string) => void;
  delayCheckNode: (id: string) => void;
  t: TFunction;
}

const RuleSection = ({
  id,
  data,
  nodeParam,
  handleChangeNodeParam,
  delayCheckNode,
  t,
}: RuleSectionProps): React.ReactElement | null =>
  nodeParam?.mode === 0 ? (
    <FLowCollapse
      label={
        <div className="text-base font-medium">
          {t('workflow.nodes.textJoinerNode.rule')}
        </div>
      }
      content={
        <div className="rounded-md px-[18px] pb-3 pointer-events-auto">
          <FlowTemplateEditor
            id={id}
            data={data}
            value={nodeParam?.prompt}
            onChange={value => handleChangeNodeParam('prompt', value)}
            onBlur={() => delayCheckNode(id)}
            placeholder={t('workflow.nodes.textJoinerNode.joinRulePlaceholder')}
          />
          <p className="text-xs text-[#F74E43]">
            {data.nodeParam.templateErrMsg}
          </p>
        </div>
      }
    />
  ) : null;

const SeparatorSection = ({
  id,
  nodeParam,
  handleChangeNodeParam,
}: Pick<ModeSelectorProps, 'id' | 'nodeParam' | 'handleChangeNodeParam'>): React.ReactElement | null => {
  const { t } = useTranslation();
  const addTextNodeConfig = useFlowsManager(state => state.addTextNodeConfig);
  const removeTextNodeConfig = useFlowsManager(
    state => state.removeTextNodeConfig
  );
  const textNodeConfigList = useFlowsManager(state => state.textNodeConfigList);
  const currentStore = useFlowsManager(state => state.getCurrentStore());
  const delayCheckNode = currentStore(state => state.delayCheckNode);
  const [showSeparatorAddInput, setShowSeparatorAddInput] = useState(false);
  const [separatorValue, setSeparatorValue] = useState('');
  const [open, setOpen] = useState(false);

  const handleAddSeparator = useCallback(() => {
    addTextNodeConfig({ separator: separatorValue }).then(() => {
      setSeparatorValue('');
      setShowSeparatorAddInput(false);
    });
  }, [separatorValue]);

  if (nodeParam?.mode !== 1) return null;

  return (
    <FLowCollapse
      label={
        <div className="text-base font-medium">
          {t('workflow.nodes.textJoinerNode.separator')}
        </div>
      }
      content={
        <div className="rounded-md px-[18px] pb-3 pointer-events-auto">
          <Select
            placeholder={t('workflow.nodes.textJoinerNode.selectSeparator')}
            className="flow-select nodrag w-full"
            open={open}
            onDropdownVisibleChange={visible => setOpen(visible)}
            suffixIcon={<img src={formSelect} className="w-4 h-4" />}
            value={nodeParam?.separator}
            onBlur={() => delayCheckNode(id)}
            dropdownRender={() => (
              <div className="mt-1 px-3">
                <div className="max-h-[300px] overflow-auto">
                  {textNodeConfigList?.map((item, index) => (
                    <div
                      key={index}
                      className="w-full flex item-center justify-between group cursor-pointer py-1 px-3 hover:bg-[#E6F4FF] hover:text-[#275EFF]"
                      onClick={() => {
                        handleChangeNodeParam('separator', item?.separator);
                        setOpen(false);
                      }}
                    >
                      <span>{item?.comment || item?.separator}</span>
                      {item?.uid !== -1 && (
                        <span
                          className="invisible group-hover:visible text-xs text-[#666]"
                          onClick={e => {
                            e.stopPropagation();
                            removeTextNodeConfig(item?.id).then(list => {
                              if (
                                !list.some(
                                  i => i.separator === nodeParam?.separator
                                )
                              ) {
                                handleChangeNodeParam('separator', '');
                              }
                            });
                          }}
                        >
                          {t('workflow.nodes.toolNode.delete')}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
                {!showSeparatorAddInput && (
                  <div
                    className="w-full rounded border border-[#275EFF] flex items-center justify-center gap-2 mt-3 text-[#275EFF] cursor-pointer"
                    onClick={() => setShowSeparatorAddInput(true)}
                  >
                    <img
                      src={inputAddIcon}
                      className="w-[10px] h-[10px]"
                      alt=""
                    />
                    <div>
                      {t('workflow.nodes.textJoinerNode.customSeparator')}
                    </div>
                  </div>
                )}
                {showSeparatorAddInput && (
                  <div className="w-full flex items-center gap-2.5 mt-3">
                    <Input
                      value={separatorValue}
                      onChange={e => setSeparatorValue(e?.target?.value)}
                      className="flex-1"
                      maxLength={20}
                      showCount
                      onKeyDown={e => e.stopPropagation()}
                    />
                    <Button
                      type="text"
                      className="origin-btn px-[28px] h-[30px]"
                      onClick={() => {
                        setSeparatorValue('');
                        setShowSeparatorAddInput(false);
                      }}
                    >
                      {t('common.cancel')}
                    </Button>
                    <Button
                      type="primary"
                      className="px-[28px]"
                      onClick={handleAddSeparator}
                    >
                      {t('common.confirm')}
                    </Button>
                  </div>
                )}
              </div>
            )}
            options={textNodeConfigList}
            fieldNames={{ label: 'comment', value: 'separator' }}
          />
          <p className="text-xs text-[#F74E43]">{nodeParam.separatorErrMsg}</p>
        </div>
      }
    />
  );
};

const OutputTree = ({ nodeParam }: Pick<ModeSelectorProps, 'nodeParam'>): React.ReactElement => {
  const renderTitle = useCallback((name: string, type: string) => {
    return (
      <div className="flex items-center gap-2">
        <span>{name}</span>
        <div className="bg-[#F0F0F0] py-1 px-2.5 rounded">{type}</div>
      </div>
    );
  }, []);

  const treeData = useMemo(
    () => [
      {
        title: renderTitle(
          'output',
          nodeParam?.mode === 1 ? 'Array<String>' : 'String'
        ),
        key: '0-0',
      },
    ],
    [nodeParam]
  );

  return (
    <FLowCollapse
      label={<div className="text-base font-medium">输出</div>}
      content={
        <div className="px-[18px]">
          <FLowTree
            className="flow-output-tree no-ant-tree-switcher"
            treeData={treeData}
          />
        </div>
      }
    />
  );
};

// ===================== 主组件 =====================
export const TextHandleDetail = memo(({ id, data }: TextHandleDetailProps): React.ReactElement => {
  const { nodeParam } = useNodeCommon({
    id,
    data,
  });
  const { t } = useTranslation();
  const getCurrentStore = useFlowsManager(state => state.getCurrentStore);
  const currentStore = getCurrentStore();
  const setNode = currentStore(state => state.setNode);
  const delayCheckNode = currentStore(state => state.delayCheckNode);
  const updateNodeRef = currentStore(state => state.updateNodeRef);
  const canPublishSetNot = useFlowsManager(state => state.canPublishSetNot);
  const autoSaveCurrentFlow = useFlowsManager(
    state => state.autoSaveCurrentFlow
  );

  const handleChangeNodeParam = useCallback(
    <Key extends TextNodeParameterKey>(key: Key, value: TextNodeParameters[Key]) => {
      setNode(id, old => {
        const modeOutput = old.data.outputs?.[0];
        if (key === 'mode' && (value === 0 || value === 1)) {
          if (!modeOutput?.schema)
            throw new TypeError('文本处理模式缺少原输出结构');
          old.data.nodeParam[key] = value;
          modeOutput.schema.type = value === 0 ? 'string' : 'array-string';
          if (value === 1) old.data.inputs = [
              {
                id: uuid(),
                name: 'input',
                schema: { type: 'string', value: { type: 'ref', content: {} } },
              },
            ];
        } else old.data.nodeParam[key] = value;
        return { ...cloneDeep(old) };
      });
      autoSaveCurrentFlow();
      canPublishSetNot();
    },
    [id, setNode, autoSaveCurrentFlow, canPublishSetNot]
  );

  return (
    <div id={id}>
      <div className="p-[14px] pb-[6px]">
        <div className="bg-[#fff] flex flex-col gap-2.5 pointer-events-auto">
          <ModeSelector
            id={id}
            nodeParam={nodeParam}
            handleChangeNodeParam={handleChangeNodeParam}
            updateNodeRef={updateNodeRef}
            t={t}
          />
          <Inputs id={id} data={data}>
            <div className="text-base font-medium">
              {t('workflow.nodes.textJoinerNode.input')}
            </div>
          </Inputs>
          <RuleSection
            id={id}
            data={data}
            nodeParam={nodeParam}
            handleChangeNodeParam={handleChangeNodeParam}
            delayCheckNode={delayCheckNode}
            t={t}
          />
          <SeparatorSection
            id={id}
            nodeParam={nodeParam}
            handleChangeNodeParam={handleChangeNodeParam}
          />
          <OutputTree nodeParam={nodeParam} />
        </div>
      </div>
    </div>
  );
});
