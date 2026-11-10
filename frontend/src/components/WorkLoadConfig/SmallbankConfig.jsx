import React, { useState, useEffect } from "react";
import { useDispatch, useSelector } from "react-redux";
import { updateSmallbankConfig } from "../../store/modules/workloadSlice";
import styles from "./WorkloadConfig.module.css";
import { useLanguage } from "../../i18n";

const SmallbankConfig = () => {
  const dispatch = useDispatch();
  const smallbankConfig = useSelector((state) => state.workload.smallbankConfig);
  const [localConfig, setLocalConfig] = useState({ ...smallbankConfig });
  const { translate } = useLanguage();

  useEffect(() => {
    setLocalConfig({ ...smallbankConfig });
  }, [smallbankConfig]);

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setLocalConfig((prev) => {
      const next = { ...prev, [name]: value };
      dispatch(updateSmallbankConfig(next));
      return next;
    });
  };

  // 计算权重总和
  const calculateWeightSum = (config) => {
    const weights = [
      config.amalgamateWeight,
      config.balanceWeight,
      config.depositCheckingWeight,
      config.sendPaymentWeight,
      config.transactSavingsWeight,
      config.writeCheckWeight,
    ];
    return weights.reduce((sum, weight) => sum + (parseInt(weight, 10) || 0), 0);
  };

  // 计算当前权重总和
  const weightSum = calculateWeightSum(localConfig);
  const weightSumPercentage = Math.min(weightSum, 100);

  return (
    <div className={styles.container}>
      {/* 基础配置 */}
      <div className={styles.flexContainer}>
        <div className={styles.formGroup}>
          <label htmlFor="accountsNum">{translate("Workload.smallbank.accountsNum")}</label>
          <input
            type="text"
            id="accountsNum"
            name="accountsNum"
            value={localConfig.accountsNum || ""}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="terminals">{translate("Workload.smallbank.terminals")}</label>
          <input
            type="text"
            id="terminals"
            name="terminals"
            value={localConfig.terminals || ""}
            onChange={handleInputChange}
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="duration">{translate("Workload.smallbank.duration")}</label>
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

      {/* 事务权重配置区域 */}
      <div className={styles.transactionWeightsSection}>
        <div className={styles.sectionHeader}>
          <span className={styles.sectionTitle}>{translate("Workload.smallbank.transactionWeights")}</span>
          <span className={styles.sectionHint}>{translate("Workload.smallbank.weightSumHint")}</span>
        </div>

        <div className={styles.weightsGrid}>
          <div className={styles.formGroup}>
            <label htmlFor="amalgamateWeight">
              {translate("Workload.smallbank.amalgamateWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.amalgamateDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="amalgamateWeight"
                name="amalgamateWeight"
                value={localConfig.amalgamateWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="balanceWeight">
              {translate("Workload.smallbank.balanceWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.balanceDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="balanceWeight"
                name="balanceWeight"
                value={localConfig.balanceWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="depositCheckingWeight">
              {translate("Workload.smallbank.depositCheckingWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.depositCheckingDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="depositCheckingWeight"
                name="depositCheckingWeight"
                value={localConfig.depositCheckingWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="sendPaymentWeight">
              {translate("Workload.smallbank.sendPaymentWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.sendPaymentDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="sendPaymentWeight"
                name="sendPaymentWeight"
                value={localConfig.sendPaymentWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="transactSavingsWeight">
              {translate("Workload.smallbank.transactSavingsWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.transactSavingsDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="transactSavingsWeight"
                name="transactSavingsWeight"
                value={localConfig.transactSavingsWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="writeCheckWeight">
              {translate("Workload.smallbank.writeCheckWeight")}
              <span className={styles.transactionDesc}>{translate("Workload.smallbank.writeCheckDesc")}</span>
            </label>
            <div className={styles.weightInputWrapper}>
              <input
                type="text"
                id="writeCheckWeight"
                name="writeCheckWeight"
                value={localConfig.writeCheckWeight || ""}
                onChange={handleInputChange}
                className={styles.input}
              />
              <span className={styles.weightUnit}>%</span>
            </div>
          </div>
        </div>

        {/* 权重总和进度条 */}
        <div className={styles.weightSumContainer}>
          <div className={styles.weightSumLabel}>
            <span>{translate("Workload.smallbank.weightSumLabel")}</span>
            <span className={weightSum === 100 ? styles.weightSumValid : styles.weightSumInvalid}>
              {weightSum}/100 {weightSum === 100 ? "✓" : "✗"}
            </span>
          </div>
          <div className={styles.weightSumBar}>
            <div
              className={`${styles.weightSumProgress} ${weightSum === 100 ? styles.weightSumProgressValid : ""}`}
              style={{ width: `${weightSumPercentage}%` }}
            />
          </div>
        </div>
      </div>

    </div>
  );
};

export default SmallbankConfig;
