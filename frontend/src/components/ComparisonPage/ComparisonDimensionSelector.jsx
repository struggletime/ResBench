import React from 'react';
import styles from './ComparisonDimensionSelector.module.css';
import { useLanguage } from '../../i18n';

const ComparisonDimensionSelector = ({ dimension, onDimensionChange }) => {
  const { translate } = useLanguage();

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>
        {translate('comparison.dimension.selector.title')}
      </h3>
      <div className={styles.options}>
        <label className={styles.option}>
          <input
            type="radio"
            name="comparison-dimension"
            checked={dimension === 'database'}
            onChange={() => onDimensionChange('database')}
          />
          <span>{translate('comparison.dimension.database')}</span>
        </label>

        <label className={styles.option}>
          <input
            type="radio"
            name="comparison-dimension"
            checked={dimension === 'workload'}
            onChange={() => onDimensionChange('workload')}
          />
          <span>{translate('comparison.dimension.workload')}</span>
        </label>

        <label className={styles.option}>
          <input
            type="radio"
            name="comparison-dimension"
            checked={dimension === 'fault'}
            onChange={() => onDimensionChange('fault')}
          />
          <span>{translate('comparison.dimension.fault')}</span>
        </label>
      </div>
    </div>
  );
};

export default ComparisonDimensionSelector;