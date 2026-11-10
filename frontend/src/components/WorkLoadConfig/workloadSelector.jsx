import React from "react";
import styles from "./WorkloadConfig.module.css";
import { useLanguage } from "../../i18n";
const WorkloadSelector = ({ onChange, options = [], value }) => {
    const {translate} = useLanguage()
    const defaultWorkloadType = [
        {value:"smallbank",label:'SmallBank'},
        {value:"benchmarkSQL",label:'TPCC'},
        {value:"ycsb",label:'ycsb'},
        {value:"self-defined",label:'self-defined'},
    ]
    const workloadType = options.length > 0 ? options : defaultWorkloadType;
    return (
        <div id="workloadSelector" className={styles.formGroup} >
            <label id="workloadSelector">{translate('Workload.workload')}</label>
            <select name="workloadSelector" onChange={onChange} value={value}>
            {
                workloadType.map(
                    (type) => (
                        <option key={type.value} value={type.value}>{type.label}</option>
                    )
                )
            }
            </select>
        </div>
    )
}
export default WorkloadSelector;
