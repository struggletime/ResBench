import React, { useState, useEffect } from "react";
import { useDispatch, useSelector } from "react-redux";
import { setWorkloadDirty, updateBenchmarkSQLConfig } from "../../store/modules/workloadSlice";
import { verifyTpccWarehouses } from "../../api/suiteApi";
import styles from "./WorkloadConfig.module.css";
import { useLanguage } from "../../i18n";

const BenchmarksqlConfig = () => {
  const dispatch = useDispatch();
  const dirty = useSelector((state) => state.workload.dirty);
  const benchmarkSQLConfig = useSelector(state => state.workload.benchmarkSQLConfig);
  const [localConfig, setLocalConfig] = useState({ ...benchmarkSQLConfig });
  const { translate } = useLanguage();

  // 按钮状态: 'default' | 'success' | 'error'
  const [buttonState, setButtonState] = useState("default");
  // Toast 状态
  const [toast, setToast] = useState({ show: false, message: "", type: "success" });
  // 「检查数据库实际仓数」：结果常驻显示（不用 3 秒 toast —— 实际数字要留得住）
  const [verifyLoading, setVerifyLoading] = useState(false);
  const [verifyResult, setVerifyResult] = useState(null);
  // 连接信息与「数据库配置」面板是同一份
  const databaseState = useSelector((state) => state.database);

  useEffect(() => {
    setLocalConfig({ ...benchmarkSQLConfig });
  }, [benchmarkSQLConfig]);

  // 换了库 / 改了连接信息，之前那次检查的结果就不算数了，清掉免得张冠李戴
  useEffect(() => {
    setVerifyResult(null);
  }, [
    databaseState.type,
    databaseState.deploymentType,
    databaseState.standalone,
    databaseState.distributed,
  ]);

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setLocalConfig(prev => ({ ...prev, [name]: value }));
    dispatch(setWorkloadDirty(true));
  };

  const showToast = (message, type = "success") => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: "", type }), 3000);
  };

  const resetButton = () => setButtonState("default");

  // 校验函数
  const validateConfig = (config) => {
    const errors = [];

    const integerFields = ["warehouses", "concurrency", "duration"];
    for (const field of integerFields) {
      const val = config[field]?.trim();
      if (!val) {
        errors.push(translate(`Workload.benchmarksql.${field}`) + translate("Workload.benchmarksql.isRequired"));
      } else if (!/^\d+$/.test(val) || parseInt(val, 10) <= 0) {
        errors.push(translate(`Workload.benchmarksql.${field}`) + translate("Workload.benchmarksql.mustBePositiveInteger"));
      }
    }

    return errors;
  };

  const handleSave = () => {
    const errors = validateConfig(localConfig);

    if (errors.length > 0) {
      // 校验失败
      setButtonState("error");
      showToast(errors[0], "error"); // 只显示第一个错误
      setTimeout(resetButton, 2000);
      return;
    }

    // 校验通过：保存到 Redux（纯前端）
    dispatch(updateBenchmarkSQLConfig(localConfig));
    setButtonState("success");
    showToast(translate("Workload.benchmarksql.saveSuccess") || "配置已保存！", "success");
    setTimeout(resetButton, 2000);
  };

  const getButtonText = () => {
    if (buttonState === "error") return "✗ Save Failed";
    if (!dirty) return translate("Workload.saved") || "已保存";
    return translate("Workload.benchmarksql.save") || "Save";
  };

  // 检查数据库里实际的仓数。
  // 仓数读 localConfig（用户当前填的、可能还没保存的值 —— redux 要等点「保存」才更新），
  // 连接信息读 redux 的数据库配置（与「测试连接」用的是同一份）。
  const handleVerifyWarehouses = async () => {
    const raw = String(localConfig.warehouses ?? "").trim();
    if (!/^\d+$/.test(raw) || parseInt(raw, 10) <= 0) {
      setVerifyResult({
        status: "UNKNOWN",
        message: translate("Workload.benchmarksql.verifyNeedWarehouses"),
      });
      return;
    }
    if (!databaseState?.type) {
      setVerifyResult({
        status: "UNKNOWN",
        message: translate("Workload.benchmarksql.verifyNeedDbConfig"),
      });
      return;
    }
    setVerifyLoading(true);
    try {
      const data = await verifyTpccWarehouses({
        dbType: databaseState.type,
        deploymentType: databaseState.deploymentType,
        standalone: databaseState.standalone,
        distributed: databaseState.distributed,
        params: { warehouses: raw },
      });
      // success=false 表示"无法判定"（连不上/没权限/超时），不是"尚未建仓"
      setVerifyResult({
        status: data?.success ? data.conclusion : "UNKNOWN",
        data,
        message: data?.message,
      });
    } catch (error) {
      setVerifyResult({ status: "UNKNOWN", message: error.message });
    } finally {
      setVerifyLoading(false);
    }
  };

  const verifyClass = (status) => {
    if (status === "MATCH") return styles.verifyResultSuccess;
    if (status === "ABSENT") return styles.verifyResultWarn;
    if (status === "MISMATCH" || status === "INCOMPLETE") return styles.verifyResultError;
    return styles.verifyResultUnknown;
  };

  const verifyHeadline = (status) => {
    switch (status) {
      case "MATCH":
        return translate("Workload.benchmarksql.verify.match");
      case "MISMATCH":
        return translate("Workload.benchmarksql.verify.mismatch");
      case "INCOMPLETE":
        return translate("Workload.benchmarksql.verify.incomplete");
      case "ABSENT":
        return translate("Workload.benchmarksql.verify.absent");
      default:
        return translate("Workload.benchmarksql.verify.unknown");
    }
  };

  return (
    <div className={styles.container}>
      {/* Toast */}
      {toast.show && (
        <div className={`${styles.toast} ${styles[toast.type]}`}>
          {toast.message}
        </div>
      )}

      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="warehouses">{translate('Workload.benchmarksql.warehouses')}</label>
          <input
            type="text"
            id="warehouses"
            name="warehouses"
            value={localConfig.warehouses || ""}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="concurrency">{translate('Workload.benchmarksql.concurrency')}</label>
          <input
            type="text"
            id="concurrency"
            name="concurrency"
            value={localConfig.concurrency || ""}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
      </div>

      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="duration">{translate('Workload.benchmarksql.duration')}</label>
          <input
            type="text"
            id="duration"
            name="duration"
            value={localConfig.duration || ""}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
      </div>

      	      <div className={styles.reloadRow}>
	        <label className={styles.toggle}>
	          <input
	            type="checkbox"
	            name="reload"
	            checked={localConfig.reload || false}
	            onChange={(e) => {
	              const newVal = e.target.checked;
	              setLocalConfig(prev => ({ ...prev, reload: newVal }));
	              dispatch(setWorkloadDirty(true));
	            }}
	          />
	          <span className={styles.slider} />
	        </label>
	        <span
	          className={styles.reloadLabel}
	          onClick={() => {
	            const newVal = !localConfig.reload;
	            setLocalConfig(prev => ({ ...prev, reload: newVal }));
	            dispatch(setWorkloadDirty(true));
	          }}
	        >
	          {translate('Workload.benchmarksql.reload')}
	        </span>
	      </div>

	      <div className={styles.btnContainer}>
        <button
          className={`${styles.btnVerify} ${verifyLoading ? styles.btnLoading : ""}`}
          onClick={handleVerifyWarehouses}
          disabled={verifyLoading}
        >
          {verifyLoading
            ? translate("Workload.benchmarksql.verifyLoading")
            : translate("Workload.benchmarksql.verifyWarehouses")}
        </button>
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

      {/* 仓数检查结果：常驻显示 —— 结论按 verdict 上色，说明文案由后端给 */}
      {verifyResult && (
        <div className={`${styles.verifyResultRow} ${verifyClass(verifyResult.status)}`}>
          <span className={styles.verifyConclusion}>
            {translate("Workload.benchmarksql.verifyConclusion")}：
            {verifyHeadline(verifyResult.status)}
          </span>
          <span>
            {translate("Workload.benchmarksql.verifyActual")}：
            {verifyResult.data?.actualWarehouses ?? "—"}
          </span>
          <span>
            {translate("Workload.benchmarksql.verifyConfigured")}：
            {verifyResult.data?.configuredWarehouses ?? "—"}
          </span>
          {verifyResult.message && (
            <span className={styles.verifyDetail}>{verifyResult.message}</span>
          )}
          <span className={styles.verifySnapshot}>
            {verifyResult.data?.runInProgress
              ? translate("Workload.benchmarksql.verifySnapshotRunning")
              : translate("Workload.benchmarksql.verifySnapshot")}
          </span>
        </div>
      )}
    </div>
  );
};

export default BenchmarksqlConfig;
// import React,{useState} from "react";
// import { useDispatch,useSelector } from "react-redux";
// import { updateBenchmarkSQLConfig } from "../../store/modules/workloadSlice";
// import "./workloadConfig.css"
// import { useLanguage } from "../../i18n";
// const BenchmarksqlConfig = () =>{
//     const dispatch = useDispatch()
//     const benchmarkSQLConfig = useSelector(state => state.workload.benchmarkSQLConfig)
//     console.log(benchmarkSQLConfig)
//     const [benchmarkSQLConfigState ,setBenchmarkSQLConfigState] = useState(benchmarkSQLConfig);
    
//     const {translate} = useLanguage()
//     const handleInputChange = (e) => {
//         const {value} = e.target
//         console.log(value);
//         setBenchmarkSQLConfigState(value);
//     }
//     const handleBtnClick = () => {
//         console.log("save workload config")
//         dispatch(updateBenchmarkSQLConfig({benchmarkSQLConfigState}))
//     }
//     return (
//         <div>
//         <div className="flex-container">
//             <div className="form-group">
//                 <label htmlFor="Warehouses">{translate(    'Workload.benchmarksql.warehouses')}</label>
//                 <input 
//                     type="text" 
//                     id="Warehouses" 
//                     name="Warehouses"
//                     value={benchmarkSQLConfigState.warehouses}
//                     onChange={handleInputChange}
//                 />
//             </div>
//             <div  className="form-group">
//                 <label htmlFor="Concurrency">{translate('Workload.benchmarksql.concurrency')}</label>
//                 <input 
//                     type="text" 
//                     id="Concurrency" 
//                     name="Concurrency"
//                     value={benchmarkSQLConfigState.concurrency}
//                     onChange={handleInputChange}
//                 />
//             </div>
//         </div>
//         <div className="flex-container">
//             <div className="form-group">
//                 <label htmlFor="Read/Write Ratio">{translate('Workload.benchmarksql.Read/Write Ratio')}</label>
//                 <input 
//                     type="text" 
//                     id="Read/Write Ratio" 
//                     name="Read/Write Ratio"
//                     value={benchmarkSQLConfigState.rwRatio}
//                     onChange={handleInputChange}
//                 />
//             </div>
//             <div className="form-group">
//                 <label htmlFor="Duration">{translate('Workload.benchmarksql.duration')}</label>
//                 <input 
//                     type="text" 
//                     id="Duration" 
//                     name="Duration"
//                     value={benchmarkSQLConfigState.duration}
//                     onChange={handleInputChange}
//                 />
//             </div>
//         </div>
//         <div className="flex-container">
//             <div className="form-group">
//                 <label htmlFor="Threads">{translate('Workload.benchmarksql.threads')}</label>
//                 <input 
//                     type="text" 
//                     id="Threads" 
//                     name="Threads"
//                     value={benchmarkSQLConfigState.threads}
//                     onChange={handleInputChange}
//                 />
//             </div>
//             <div  className="form-group">
//                 <label htmlFor="Warehouses">{translate('Workload.benchmarksql.mode')}</label>
//                 <select name="Treads" id="Threads" onChange={handleInputChange}>
//                     <option value="warmup">{translate('Workload.benchmarksql.mode.warmup')}</option>
//                     <option value="test">{translate('Workload.benchmarksql.mode.test')}</option>
//                 </select>
//             </div>
//         </div>
//             <div className="btn-container">
//                 <button className="btn-save" onClick={handleBtnClick}>
//                 {translate('Workload.benchmarksql.save')}
//                 </button>
//             </div>
//         </div>
//     )
// }
// export default BenchmarksqlConfig