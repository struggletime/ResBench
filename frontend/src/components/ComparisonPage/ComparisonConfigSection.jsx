import React, { useState } from 'react';
import { useLanguage } from "../../i18n";
import { FAULT_DEFINITIONS, displayFaultName } from '../../config/faultConfig';
import ConfigDetailModal from './ConfigDetailModal';
import styles from './ComparisonConfigSection.module.css';

const ComparisonConfigSection = ({ selectedRecords }) => {
  const { translate, lang } = useLanguage();
  const [modalType, setModalType] = useState(null); // 'workload' | 'fault'
  if (selectedRecords.length === 0) return null;

  const firstRecord = selectedRecords[0];

  // 渲染单个不利配置项（美化版）
  const renderFaultItem = (fault, index) => {
    const def = Object.values(FAULT_DEFINITIONS).find(d => d.nameKey === fault.nameKey);

    if (!def) {
      return (
        <div key={index} className={styles.faultCard}>
          <span className={styles.faultIndex}>{index + 1}</span>
          <div className={styles.faultContent}>
            <span className={styles.faultName}>{translate('ComparisonConfig.unknownFault')}</span>
            <div className={styles.faultParams}>
              <code>{fault.nameKey}</code>
            </div>
          </div>
        </div>
      );
    }

    // 构建参数描述：使用翻译后的参数名 + 值 + 单位
    const paramList = def.params.map(param => {
      const value = fault.params?.[param.paramName] ?? param.defaultValue;
      const label = translate(`param.label.${param.paramName}`) || param.paramName;
      const unit = param.unit ? ` ${param.unit}` : '';
      return `${label}=${value}${unit}`;
    });

    return (
      <div key={index} className={styles.faultCard}>
        <span className={styles.faultIndex}>{index + 1}</span>
        <div className={styles.faultContent}>
          <span className={styles.faultName}>{displayFaultName(def, translate, lang)}</span>
          <div className={styles.faultParams}>
            {paramList.map((paramStr, idx) => (
              <div key={idx} className={styles.paramLine}>
                {paramStr}
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className={styles.container}>
      <div className={styles.section}>
        <h3 className={styles.sectionTitle}>
          {translate('comparison.commonConfig')}
        </h3>

        {/* Workload 配置卡片 */}
        <div
          className={styles.card}
          style={{ cursor: 'pointer' }}
          onClick={() => setModalType('workload')}
        >
          <h4 className={styles.cardTitle}>
            {translate('comparison.workloadConfig')}
            <span style={{ float: 'right', fontSize: 12, color: '#1890ff', fontWeight: 400 }}>
              {translate('comparison.clickToDetail') || '点击查看详情 ›'}
            </span>
          </h4>
          <div className={styles.configGrid}>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.workload')}：
              </span>
              <span className={styles.value}>{firstRecord.workloadType}</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.warehouses')}：
              </span>
              <span className={styles.value}>{firstRecord.warehouses}</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.concurrency')}：
              </span>
              <span className={styles.value}>{firstRecord.concurrency}</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.readWriteRatio')}：
              </span>
              <span className={styles.value}>{firstRecord.readWriteRatio}</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.duration')}：
              </span>
              <span className={styles.value}>{firstRecord.duration}s</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.threads')}：
              </span>
              <span className={styles.value}>{firstRecord.threads}</span>
            </div>
            <div className={styles.field}>
              <span className={styles.label}>
                {translate('comparison.runMode')}：
              </span>
              <span className={styles.value}>{firstRecord.runMode}</span>
            </div>
          </div>
        </div>

        {/* 不利配置卡片 */}
        <div
          className={styles.card}
          style={{ cursor: 'pointer' }}
          onClick={() => setModalType('fault')}
        >
          <h4 className={styles.cardTitle}>
            {translate('comparison.faultConfig')}
            <span style={{ float: 'right', fontSize: 12, color: '#1890ff', fontWeight: 400 }}>
              {translate('comparison.clickToDetail') || '点击查看详情 ›'}
            </span>
          </h4>
          {firstRecord.faults && firstRecord.faults.length > 0 ? (
            <div className={styles.faultList}>
              {firstRecord.faults.map((fault, index) => renderFaultItem(fault, index))}
            </div>
          ) : (
            <p className={styles.noFault}>
              {translate('comparison.noFault')}
            </p>
          )}
        </div>
      </div>

      {modalType && (
        <ConfigDetailModal
          title={modalType === 'workload'
            ? (translate('comparison.workloadDetail') || 'Workload 配置详情')
            : (translate('comparison.faultDetail') || '不利配置详情')}
          selectedRecords={selectedRecords}
          onClose={() => setModalType(null)}
        />
      )}
    </div>
  );
};

export default ComparisonConfigSection;
// // src/components/ComparisonConfigSection/ComparisonConfigSection.jsx
// import React from 'react';
// import { useLanguage } from "../../i18n";
// import { FAULT_DEFINITIONS } from '../../config/faultConfig';
// import styles from './ComparisonConfigSection.module.css';

// const ComparisonConfigSection = ({ selectedRecords, dimension }) => {
//   const { translate } = useLanguage();
//   if (selectedRecords.length === 0) return null;

//   const firstRecord = selectedRecords[0];

//   // 渲染单个不利配置项（美化版）
//   const renderFaultItem = (fault, index) => {
//     const def = Object.values(FAULT_DEFINITIONS).find(d => d.nameKey === fault.nameKey);
    
//     if (!def) {
//       return (
//         <div key={index} className={styles.faultCard}>
//           <span className={styles.faultIndex}>{index + 1}</span>
//           <div className={styles.faultContent}>
//             <span className={styles.faultName}>未知故障</span>
//             <div className={styles.faultParams}>
//               <code>{fault.nameKey}</code>
//             </div>
//           </div>
//         </div>
//       );
//     }

//     // 构建参数描述：使用翻译后的参数名 + 值 + 单位
//     const paramList = def.params.map(param => {
//       const value = fault.params?.[param.paramName] ?? param.defaultValue;
//       const label = translate(`param.label.${param.paramName}`) || param.paramName;
//       const unit = param.unit ? ` ${param.unit}` : '';
//       return `${label}=${value}${unit}`;
//     });

//     return (
//       <div key={index} className={styles.faultCard}>
//         <span className={styles.faultIndex}>{index + 1}</span>
//         <div className={styles.faultContent}>
//           <span className={styles.faultName}>{displayFaultName(def, translate)}</span>
//           <div className={styles.faultParams}>
//             {paramList.map((paramStr, idx) => (
//               <div key={idx} className={styles.paramLine}>
//                 {paramStr}
//               </div>
//             ))}
//           </div>
//         </div>
//       </div>
//     );
//   };

//   return (
//     <div className={styles.container}>
//       <div className={styles.section}>
//         <h3 className={styles.sectionTitle}>公共配置</h3>

//         {/* Workload 配置卡片 */}
//         <div className={styles.card}>
//           <h4 className={styles.cardTitle}>Workload 配置</h4>
//           <div className={styles.configGrid}>
//             <div className={styles.field}>
//               <span className={styles.label}>Workload：</span>
//               <span className={styles.value}>{firstRecord.workloadType}</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>仓数：</span>
//               <span className={styles.value}>{firstRecord.warehouses}</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>并发数：</span>
//               <span className={styles.value}>{firstRecord.concurrency}</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>读写比：</span>
//               <span className={styles.value}>{firstRecord.readWriteRatio}</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>持续时间：</span>
//               <span className={styles.value}>{firstRecord.duration}s</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>线程数：</span>
//               <span className={styles.value}>{firstRecord.threads}</span>
//             </div>
//             <div className={styles.field}>
//               <span className={styles.label}>RunMode：</span>
//               <span className={styles.value}>{firstRecord.runMode}</span>
//             </div>
//           </div>
//         </div>

//         {/* 不利配置卡片 */}
//         <div className={styles.card}>
//           <h4 className={styles.cardTitle}>不利配置</h4>
//           {firstRecord.faults && firstRecord.faults.length > 0 ? (
//             <div className={styles.faultList}>
//               {firstRecord.faults.map((fault, index) => renderFaultItem(fault, index))}
//             </div>
//           ) : (
//             <p className={styles.noFault}>（无不利）</p>
//           )}
//         </div>
//       </div>

//       {/* 差异配置：仅非 database 维度展示 */}
//       {dimension !== 'database' && (
//         // ...（此处可保留 Workload / 不利差异表，按需实现）
//         {}
//       )}
//     </div>
//   );
// };

// export default ComparisonConfigSection;