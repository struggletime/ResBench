import { useState, useEffect, useMemo, useCallback } from 'react';
import { Joyride, STATUS } from 'react-joyride';
import { useDispatch } from 'react-redux';
import { setSubTab } from '../../store/modules/tabSlice';
import { useLanguage } from '../../i18n';
import styles from './GuidedTour.module.css';

const FAULT_TOUR_STORAGE_KEY = 'has_seen_fault_tour';

const FaultInjectionTour = () => {
  const { lang, translate } = useLanguage();
  const dispatch = useDispatch();
  const [run, setRun] = useState(false);
  const [tourKey, setTourKey] = useState(0);

  // 步骤索引到页面的映射 — 始终停留在 fault 页面
  const stepPageMap = useMemo(() => ({
    0: null,           // 欢迎页
    1: 'fault',        // 编排模式
    2: 'fault',        // 不利类型
    3: 'fault',        // 不利卡片
    4: 'fault',        // 添加按钮
    5: 'fault',        // 不利列表
    6: 'fault',        // 参数编辑
    7: 'fault',        // 开始测试
    8: 'fault',        // 实时性能图
  }), []);

  // Expose startFaultTour globally
  useEffect(() => {
    window.__startFaultInjectionTour = () => {
      // 先导航到 fault 页面
      dispatch(setSubTab('fault'));
      setTourKey(prev => prev + 1);
      setRun(true);
    };

    return () => {
      delete window.__startFaultInjectionTour;
    };
  }, [dispatch]);

  const steps = useMemo(
    () => [
      {
        target: 'body',
        placement: 'center',
        title: translate('faultTour.step.welcome.title'),
        content: translate('faultTour.step.welcome.content'),
        disableBeacon: true,
      },
      {
        target: '[data-tour="fault-mode"]',
        title: translate('faultTour.step.mode.title'),
        content: translate('faultTour.step.mode.content'),
        placement: 'bottom',
      },
      {
        target: '[data-tour="fault-type-selector"]',
        title: translate('faultTour.step.type.title'),
        content: translate('faultTour.step.type.content'),
        placement: 'bottom',
      },
      {
        target: '[data-tour="fault-cards"]',
        title: translate('faultTour.step.cards.title'),
        content: translate('faultTour.step.cards.content'),
        placement: 'bottom',
      },
      {
        target: '[data-tour="fault-add-action"]',
        title: translate('faultTour.step.add.title'),
        content: translate('faultTour.step.add.content'),
        placement: 'top',
      },
      {
        target: '[data-tour="fault-list"]',
        title: translate('faultTour.step.list.title'),
        content: translate('faultTour.step.list.content'),
        placement: 'top',
      },
      {
        target: '[data-tour="fault-list"]',
        title: translate('faultTour.step.param.title'),
        content: translate('faultTour.step.param.content'),
        placement: 'top',
      },
      {
        target: '[data-tour="fault-actions"]',
        title: translate('faultTour.step.actions.title'),
        content: translate('faultTour.step.actions.content'),
        placement: 'top',
      },
      {
        target: '[data-tour="fault-chart"]',
        title: translate('faultTour.step.chart.title'),
        content: translate('faultTour.step.chart.content'),
        placement: 'top',
      },
    ],
    [lang]
  );

  const handleJoyrideCallback = useCallback((data) => {
    const { status, action } = data;
    if (action === 'overlay') {
      return;
    }

    if ([STATUS.FINISHED, STATUS.SKIPPED].includes(status)) {
      setRun(false);
      localStorage.setItem(FAULT_TOUR_STORAGE_KEY, 'true');
    }
  }, []);

  const Tooltip = useCallback(({ continuous, index, step, backProps, closeProps, primaryProps, skipProps, tooltipProps, isLastStep, size }) => (
    <div className={styles.tooltipWrapper} {...tooltipProps}>
      <button className={styles.btnClose} {...closeProps}>
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <path d="M1 1L13 13M13 1L1 13" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      </button>
      {step.title && <div className={styles.tooltipTitle}>{step.title}</div>}
      <div className={styles.tooltipContent}>{step.content}</div>
      <div className={styles.tooltipFooter}>
        <div className={styles.tooltipProgress}>{index + 1} / {size}</div>
        <div className={styles.tooltipButtons}>
          {index > 0 && (
            <button
              className={styles.btnBack}
              {...backProps}
              onClick={(e) => {
                e.stopPropagation();
                const targetPage = stepPageMap[index - 1];
                if (targetPage) {
                  dispatch(setSubTab(targetPage));
                }
                backProps.onClick?.(e);
              }}
            >
              {translate('tour.button.back')}
            </button>
          )}
          {continuous && !isLastStep && (
            <button className={styles.btnSkip} {...skipProps}>
              {translate('tour.button.skip')}
            </button>
          )}
          <button
            className={styles.btnNext}
            {...primaryProps}
            onClick={(e) => {
              e.stopPropagation();
              if (!isLastStep) {
                const targetPage = stepPageMap[index + 1];
                if (targetPage) {
                  dispatch(setSubTab(targetPage));
                }
              }
              primaryProps.onClick?.(e);
            }}
          >
            {isLastStep ? translate('tour.button.last') : translate('tour.button.next')}
          </button>
        </div>
      </div>
    </div>
  ), [lang, dispatch, stepPageMap]);

  return (
    <Joyride
      key={tourKey}
      run={run}
      steps={steps}
      continuous
      showSkipButton
      showProgress
      scrollToFirstStep
      disableScrolling={false}
      disableOverlayClose
      locale={{
        back: translate('tour.button.back'),
        close: translate('tour.button.close'),
        last: translate('tour.button.last'),
        next: translate('tour.button.next'),
        skip: translate('tour.button.skip'),
      }}
      callback={handleJoyrideCallback}
      tooltipComponent={Tooltip}
      styles={{
        options: {
          primaryColor: '#1890ff',
          textColor: '#1f2937',
          backgroundColor: '#ffffff',
          overlayColor: 'rgba(15, 23, 42, 0.45)',
          arrowColor: '#ffffff',
          zIndex: 10000,
        },
        spotlight: {
          borderRadius: '8px',
        },
        overlay: {
          cursor: 'default',
        },
      }}
      floaterProps={{
        styles: {
          floater: {
            filter: 'drop-shadow(0 10px 30px rgba(15, 23, 42, 0.12))',
          },
        },
      }}
    />
  );
};

export default FaultInjectionTour;
