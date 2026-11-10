import { useState, useEffect, useMemo, useCallback } from 'react';
import { Joyride, STATUS } from 'react-joyride';
import { useDispatch } from 'react-redux';
import { setSubTab } from '../../store/modules/tabSlice';
import { useLanguage } from '../../i18n';
import styles from './GuidedTour.module.css';

const TOUR_STORAGE_KEY = 'has_seen_tour';

const GuidedTour = () => {
  const { lang, translate } = useLanguage();
  const dispatch = useDispatch();
  const [run, setRun] = useState(false);
  const [tourKey, setTourKey] = useState(0); // Add a key to force Joyride remount

  // 步骤索引到页面的映射
  const stepPageMap = useMemo(() => ({
    0: null,           // 欢迎页 - 不需要导航
    1: 'database',
    2: 'workload',
    3: 'fault',
    4: 'history',
    5: 'history',
    6: 'history',
    7: 'compare',
    8: 'compare',
  }), []);

  // Expose startTour globally
  useEffect(() => {
    window.__startGuidedTour = () => {
      const hasSeenTour = localStorage.getItem(TOUR_STORAGE_KEY);
      if (hasSeenTour) {
        return;
      }
      setTourKey(prev => prev + 1); // Force Joyride to reset its internal stepIndex
      setRun(true);
    };

    return () => {
      delete window.__startGuidedTour;
    };
  }, []);

  const steps = useMemo(
    () => [
      {
        target: 'body',
        placement: 'center',
        title: translate('tour.step.welcome.title'),
        content: translate('tour.step.welcome.content'),
        disableBeacon: true,
      },
      {
        target: '[data-tour="database"]',
        title: translate('tour.step.database.title'),
        content: translate('tour.step.database.content'),
        placement: 'right',
      },
      {
        target: '[data-tour="workload"]',
        title: translate('tour.step.workload.title'),
        content: translate('tour.step.workload.content'),
        placement: 'right',
      },
      {
        target: '[data-tour="fault"]',
        title: translate('tour.step.fault.title'),
        content: translate('tour.step.fault.content'),
        placement: 'right',
      },
      {
        target: '[data-tour="history"]',
        title: translate('tour.step.history.title'),
        content: translate('tour.step.history.content'),
        placement: 'right',
      },
      {
        target: '[data-tour="history-search"]',
        title: translate('tour.step.history.search.title'),
        content: translate('tour.step.history.search.content'),
        placement: 'bottom',
      },
      {
        target: '[data-tour="history-score-panel"]',
        title: translate('tour.step.history.report.title'),
        content: translate('tour.step.history.report.content'),
        placement: 'top',
      },
      {
        target: '[data-tour="compare"]',
        title: translate('tour.step.compare.title'),
        content: translate('tour.step.compare.content'),
        placement: 'right',
      },
      {
        target: '[data-tour="compare-add-btn"]',
        title: translate('tour.step.compare.add.title'),
        content: translate('tour.step.compare.add.content'),
        placement: 'left',
      },
    ],
    [lang]
  );

  const handleJoyrideCallback = useCallback((data) => {
    const { status, action } = data;
    // 忽略点击 overlay 的操作，防止引导被打断
    if (action === 'overlay') {
      return;
    }

    if ([STATUS.FINISHED, STATUS.SKIPPED].includes(status)) {
      setRun(false);
      localStorage.setItem(TOUR_STORAGE_KEY, 'true');
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
          primaryColor: '#2563eb',
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

export default GuidedTour;
