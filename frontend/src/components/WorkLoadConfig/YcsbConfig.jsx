import React, { useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import styles from "./WorkloadConfig.module.css";
import { useLanguage } from "../../i18n";
import { markWorkloadSaved, setWorkloadDirty, updateYcsbConfig } from "../../store/modules/workloadSlice";

const YcsbConfig = () => {
  const dispatch = useDispatch();
  const dirty = useSelector((state) => state.workload.dirty);
  const reduxConfig = useSelector((state) => state.workload.ycsbConfig);

  const { translate } = useLanguage();
  const [buttonState, setButtonState] = useState("default");
  const [toast, setToast] = useState({ show: false, message: "", type: "success" });

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    dispatch(updateYcsbConfig({ ...reduxConfig, [name]: value }));
    dispatch(setWorkloadDirty(true));
  };

  const showToast = (message, type = "success") => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: "", type }), 3000);
  };

  const resetButton = () => setButtonState("default");

  const validateConfig = (config) => {
    const errors = [];

    // 必填正整数
    const integerFields = ["recordCount", "threadCount"];
    for (const field of integerFields) {
      const val = config[field]?.trim();
      if (!val) {
        errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.benchmarksql.isRequired"));
      } else if (!/^\d+$/.test(val) || parseInt(val, 10) <= 0) {
        errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.benchmarksql.mustBePositiveInteger"));
      }
    }

    // duration 或 operationCount 至少一个
    const hasDuration = config.duration?.trim() && /^\d+$/.test(config.duration.trim()) && parseInt(config.duration.trim()) > 0;
    const hasOpCount = config.operationCount?.trim() && /^\d+$/.test(config.operationCount.trim()) && parseInt(config.operationCount.trim()) > 0;
    if (!hasDuration && !hasOpCount) {
      errors.push(translate("Workload.ycsb.durationOrOperationCountRequired"));
    }

    // 比例校验
    const propFields = ["readProportion", "updateProportion", "insertProportion", "scanProportion"];
    let total = 0;
    for (const field of propFields) {
      const val = config[field]?.trim();
      if (val === "") continue;
      const num = parseFloat(val);
      if (isNaN(num) || num < 0 || num > 1) {
        errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.ycsb.mustBeBetween0And1"));
        return errors; // 提前返回
      }
      total += num;
    }

    if (Math.abs(total - 1.0) > 0.01) {
      errors.push(translate("Workload.ycsb.proportionsMustSumToOne"));
    }

    return errors;
  };

  const handleSave = () => {
    const errors = validateConfig(reduxConfig);
    if (errors.length > 0) {
      setButtonState("error");
      showToast(errors[0], "error");
      setTimeout(resetButton, 2000);
      return;
    }

    setButtonState("success");
    dispatch(markWorkloadSaved());
    showToast(translate("Workload.ycsb.saveSuccess") || "Configuration saved!", "success");
    setTimeout(resetButton, 2000);
  };

  const getButtonText = () => {
    if (buttonState === "error") return "✗ Save Failed";
    if (!dirty) return translate("Workload.saved") || "已保存";
    return translate("Workload.ycsb.save") || "save";
  };

  return (
    <div className={styles.container}>
      {toast.show && (
        <div className={`${styles.toast} ${styles[toast.type]}`}>
          {toast.message}
        </div>
      )}

      {/* 第一行 */}
      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="recordCount">{translate('Workload.ycsb.recordCount')}</label>
          <input
            type="text"
            id="recordCount"
            name="recordCount"
            value={reduxConfig.recordCount}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="threadCount">{translate('Workload.ycsb.threadCount')}</label>
          <input
            type="text"
            id="threadCount"
            name="threadCount"
            value={reduxConfig.threadCount}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
      </div>

      {/* 第二行 */}
      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="duration">{translate('Workload.ycsb.duration')}</label>
          <input
            type="text"
            id="duration"
            name="duration"
            value={reduxConfig.duration}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.optional')}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="operationCount">{translate('Workload.ycsb.operationCount')}</label>
          <input
            type="text"
            id="operationCount"
            name="operationCount"
            value={reduxConfig.operationCount}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.optional')}
          />
        </div>
      </div>

      {/* 第三行：分布 */}
      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="requestDistribution">{translate('Workload.ycsb.requestDistribution')}</label>
          <select
            id="requestDistribution"
            name="requestDistribution"
            value={reduxConfig.requestDistribution}
            onChange={handleInputChange}
            className={styles.select}
          >
            <option value="uniform">uniform</option>
            <option value="zipfian">zipfian</option>
            <option value="latest">latest</option>
          </select>
        </div>
        <div className={styles.formGroup}></div> {/* 占位，保持两列布局 */}
      </div>

      {/* 第四行：比例 */}
      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="readProportion">{translate('Workload.ycsb.readProportion')}</label>
          <input
            type="text"
            id="readProportion"
            name="readProportion"
            value={reduxConfig.readProportion}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.example', { value: '1.0' })}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="updateProportion">{translate('Workload.ycsb.updateProportion')}</label>
          <input
            type="text"
            id="updateProportion"
            name="updateProportion"
            value={reduxConfig.updateProportion}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.example', { value: '0.05' })}
          />
        </div>
      </div>

      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="insertProportion">{translate('Workload.ycsb.insertProportion')}</label>
          <input
            type="text"
            id="insertProportion"
            name="insertProportion"
            value={reduxConfig.insertProportion}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.example', { value: '0.05' })}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="scanProportion">{translate('Workload.ycsb.scanProportion')}</label>
          <input
            type="text"
            id="scanProportion"
            name="scanProportion"
            value={reduxConfig.scanProportion}
            onChange={handleInputChange}
            className={styles.input}
            placeholder={translate('Workload.ycsb.example', { value: '0.1' })}
          />
        </div>
      </div>

      <div className={styles.btnContainer}>
        <button
          className={`${styles.btnSave} ${
            buttonState === "error"
              ? styles.btnError
              : ""
          }`}
          onClick={handleSave}
          disabled={!dirty}
        >
          {getButtonText()}
        </button>
      </div>
    </div>
  );
};

export default YcsbConfig;
// import React, { useState } from "react";
// import styles from "./WorkloadConfig.module.css";
// import { useLanguage } from "../../i18n";

// const YcsbConfig = () => {
//   const [reduxConfig, setLocalConfig] = useState({
//     workloadType: "B",
//     recordCount: "1000000",
//     threadCount: "50",
//     duration: "300",
//     operationCount: "",
//     requestDistribution: "uniform",
//     readProportion: "",
//     writeProportion: "",
//     insertProportion: "",
//     scanProportion: "",
//   });

//   const { translate } = useLanguage();
//   const [buttonState, setButtonState] = useState("default");
//   const [toast, setToast] = useState({ show: false, message: "", type: "success" });

//   const handleInputChange = (e) => {
//     const { name, value } = e.target;
//     setLocalConfig((prev) => ({ ...prev, [name]: value }));
//   };

//   const showToast = (message, type = "success") => {
//     setToast({ show: true, message, type });
//     setTimeout(() => setToast({ show: false, message: "", type }), 3000);
//   };

//   const resetButton = () => setButtonState("default");

//   const validateConfig = (config) => {
//     const errors = [];

//     const integerFields = ["recordCount", "threadCount"];
//     for (const field of integerFields) {
//       const val = config[field]?.trim();
//       if (!val) {
//         errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.benchmarksql.isRequired"));
//       } else if (!/^\d+$/.test(val) || parseInt(val, 10) <= 0) {
//         errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.benchmarksql.mustBePositiveInteger"));
//       }
//     }

//     const hasDuration = config.duration?.trim() && /^\d+$/.test(config.duration.trim()) && parseInt(config.duration.trim()) > 0;
//     const hasOpCount = config.operationCount?.trim() && /^\d+$/.test(config.operationCount.trim()) && parseInt(config.operationCount.trim()) > 0;
//     if (!hasDuration && !hasOpCount) {
//       errors.push(translate("Workload.ycsb.durationOrOperationCountRequired"));
//     }

//     const customFields = ["readProportion", "writeProportion", "insertProportion", "scanProportion"];
//     let total = 0;
//     let hasCustom = false;
//     for (const field of customFields) {
//       const val = config[field]?.trim();
//       if (val) {
//         hasCustom = true;
//         const num = parseFloat(val);
//         if (isNaN(num) || num < 0 || num > 1) {
//           errors.push(translate(`Workload.ycsb.${field}`) + translate("Workload.ycsb.mustBeBetween0And1"));
//         } else {
//           total += num;
//         }
//       }
//     }

//     if (hasCustom && Math.abs(total - 1.0) > 0.01) {
//       errors.push(translate("Workload.ycsb.proportionsMustSumToOne"));
//     }

//     if (!config.workloadType) {
//       errors.push(translate("Workload.ycsb.workloadType") + translate("Workload.benchmarksql.isRequired"));
//     }

//     return errors;
//   };

//   const handleSave = () => {
//     const errors = validateConfig(reduxConfig);

//     if (errors.length > 0) {
//       setButtonState("error");
//       showToast(errors[0], "error");
//       setTimeout(resetButton, 2000);
//       return;
//     }

//     setButtonState("success");
//     showToast(translate("Workload.ycsb.saveSuccess") || "Configuration saved!", "success");
//     setTimeout(resetButton, 2000);
//   };

//   const getButtonText = () => {
//     if (buttonState === "success") return "✓ Saved!";
//     if (buttonState === "error") return "✗ Save Failed";
//     return translate("Workload.ycsb.save") || "save";
//   };

//   return (
//     <div className={styles.container}>
//       {toast.show && (
//         <div className={`${styles.toast} ${styles[toast.type]}`}>
//           {toast.message}
//         </div>
//       )}

//       <div className={styles.flexContainer}>
//         <div className={styles.formGroup}>
//           <label htmlFor="workloadType">{translate('Workload.ycsb.workloadType')}</label>
//           <select
//             id="workloadType"
//             name="workloadType"
//             value={reduxConfig.workloadType}
//             onChange={handleInputChange}
//             className={styles.select}
//           >
//             <option value="A">Workload A</option>
//             <option value="B">Workload B</option>
//             <option value="C">Workload C</option>
//             <option value="D">Workload D</option>
//             <option value="E">Workload E</option>
//             <option value="F">Workload F</option>
//           </select>
//         </div>
//         <div className={styles.formGroup}>
//           <label htmlFor="recordCount">{translate('Workload.ycsb.recordCount')}</label>
//           <input
//             type="text"
//             id="recordCount"
//             name="recordCount"
//             value={reduxConfig.recordCount}
//             onChange={handleInputChange}
//             className={styles.input}
//           />
//         </div>
//       </div>

//       <div className={styles.flexContainer}>
//         <div className={styles.formGroup}>
//           <label htmlFor="threadCount">{translate('Workload.ycsb.threadCount')}</label>
//           <input
//             type="text"
//             id="threadCount"
//             name="threadCount"
//             value={reduxConfig.threadCount}
//             onChange={handleInputChange}
//             className={styles.input}
//           />
//         </div>
//         <div className={styles.formGroup}>
//           <label htmlFor="duration">{translate('Workload.ycsb.duration')}</label>
//           <input
//             type="text"
//             id="duration"
//             name="duration"
//             value={reduxConfig.duration}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="optional"
//           />
//         </div>
//       </div>

//       <div className={styles.flexContainer}>
//         <div className={styles.formGroup}>
//           <label htmlFor="operationCount">{translate('Workload.ycsb.operationCount')}</label>
//           <input
//             type="text"
//             id="operationCount"
//             name="operationCount"
//             value={reduxConfig.operationCount}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="optional"
//           />
//         </div>
//         <div className={styles.formGroup}>
//           <label htmlFor="requestDistribution">{translate('Workload.ycsb.requestDistribution')}</label>
//           <select
//             id="requestDistribution"
//             name="requestDistribution"
//             value={reduxConfig.requestDistribution}
//             onChange={handleInputChange}
//             className={styles.select}
//           >
//             <option value="uniform">uniform</option>
//             <option value="zipfian">zipfian</option>
//             <option value="latest">latest</option>
//           </select>
//         </div>
//       </div>

//       <div className={styles.flexContainer}>
//         <div className={styles.formGroup}>
//           <label htmlFor="readProportion">{translate('Workload.ycsb.readProportion')}</label>
//           <input
//             type="text"
//             id="readProportion"
//             name="readProportion"
//             value={reduxConfig.readProportion}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="e.g. 0.8"
//           />
//         </div>
//         <div className={styles.formGroup}>
//           <label htmlFor="writeProportion">{translate('Workload.ycsb.writeProportion')}</label>
//           <input
//             type="text"
//             id="writeProportion"
//             name="writeProportion"
//             value={reduxConfig.writeProportion}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="e.g. 0.2"
//           />
//         </div>
//       </div>

//       <div className={styles.flexContainer}>
//         <div className={styles.formGroup}>
//           <label htmlFor="insertProportion">{translate('Workload.ycsb.insertProportion')}</label>
//           <input
//             type="text"
//             id="insertProportion"
//             name="insertProportion"
//             value={reduxConfig.insertProportion}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="e.g. 0.05"
//           />
//         </div>
//         <div className={styles.formGroup}>
//           <label htmlFor="scanProportion">{translate('Workload.ycsb.scanProportion')}</label>
//           <input
//             type="text"
//             id="scanProportion"
//             name="scanProportion"
//             value={reduxConfig.scanProportion}
//             onChange={handleInputChange}
//             className={styles.input}
//             placeholder="e.g. 0.95"
//           />
//         </div>
//       </div>

//       <div className={styles.btnContainer}>
//         <button
//           className={`${styles.btnSave} ${
//             buttonState === "success"
//               ? styles.btnSuccess
//               : buttonState === "error"
//               ? styles.btnError
//               : ""
//           }`}
//           onClick={handleSave}
//         >
//           {getButtonText()}
//         </button>
//       </div>
//     </div>
//   );
// };

// export default YcsbConfig;