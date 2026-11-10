import React, { useState } from "react";
import { useDispatch, useSelector } from 'react-redux';
import WorkloadSelector from "./workloadSelector";
import BenchmarksqlConfig from "./BenchmarksqlConfig";
import styles from "./WorkloadConfig.module.css";
import YcsbConfig from "./YcsbConfig";
import SmallbankConfig from "./SmallbankConfig";
import { markWorkloadSaved, setWorkload } from "../../store/modules/workloadSlice";

function normalizeWorkloadName(name) {
  const value = String(name || '').toLowerCase();
  if (value === 'benchmarksql') return 'benchmarkSQL';
  if (value === 'ycsb') return 'ycsb';
  if (value === 'smallbank') return 'smallbank';
  if (value === 'tpcc') return 'tpcc';
  return name;
}

function formatWorkloadLabel(name) {
  if (name === 'benchmarkSQL') return 'TPCC';
  if (name === 'tpcc') return 'TPCC';
  if (name === 'smallbank') return 'SmallBank';
  return name;
}

const WorkloadConfig = () => {
    const dispatch = useDispatch();
    const { workload, availableWorkloads } = useSelector(state => state.workload);
    const [workloadTypeState ,setWorkloadType ] = useState(workload)

    React.useEffect(() => {
        setWorkloadType(workload);
    }, [workload]);

        React.useEffect(() => {
            dispatch(markWorkloadSaved());
        }, [dispatch]);

    const options = (availableWorkloads || []).map((item) => {
      const normalized = normalizeWorkloadName(item);
      return {
        value: normalized,
        label: formatWorkloadLabel(normalized),
      };
    });

    const handleWorkloadChange = (e) => {
        const {value} = e.target
        setWorkloadType(value);
        dispatch(setWorkload(value));
    }
    const renderConfig = () =>{
        switch (workloadTypeState) {
            case 'benchmarkSQL':
            case 'tpcc':
                return <BenchmarksqlConfig />
            case 'ycsb':
                return <YcsbConfig/>
            case 'smallbank':
                return <SmallbankConfig />
            default:
                break;
        }
    }
    return (
        <div className={styles.panel}>
            <div>
                <WorkloadSelector onChange={handleWorkloadChange} options={options} value={workloadTypeState}/>
                {renderConfig()}
            </div>
        </div>
    )
}
export default WorkloadConfig;
