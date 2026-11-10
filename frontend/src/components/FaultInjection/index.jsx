import React, { useCallback, useEffect, useRef, useState } from "react";
import { DndProvider } from 'react-dnd';
import { HTML5Backend } from 'react-dnd-html5-backend';
import FaultSelector from "./FaultSelector";
import FaultCard from "./FaultCard";
import FaultList from "./FaultsList";
import FileTargetPicker from "./FileTargetPicker";
import { useDispatch, useSelector } from "react-redux";
import {
    updateSelectedFaults,
    updateParamsValue,
    updateSelectedFaultType,
    updateExpandedGroups,
    updateCurrentEditState,
    toggleGroupMode,
} from "../../store/modules/faultSlice";
import {
    finishRunState,
    setRunState,
    setManuallyStopped,
} from "../../store/modules/testRunSlice";
import { setMonitoringEnabled } from "../../store/modules/observabilitySlice";
import faultCardStyle from "./FaultCard.module.css";
import { useLanguage } from "../../i18n";
import { FAULT_DEFINITIONS ,FAULT_CATEGORIES,NAME_KEY_TO_TYPE, displayFaultName, pickI18n} from '../../config/faultConfig';
import RealTimeChart from "../RealTimeChart";
import { startTestByForm, stopTestSuite, fetchHistoryList, checkFaultOrchestration } from "../../api/suiteApi";
const FaultInject = () => {
    const dispatch = useDispatch()
    const {
        selectedFaults,
        isGroupMode,
        paramsValue: persistedParamsValue,
        selectedFaultType: persistedFaultType,
        expandedGroups: persistedExpandedGroups,
        currentEditIndex: persistedCurrentEditIndex,
        currentEditGroupId: persistedCurrentEditGroupId,
        currentEditFaultId: persistedCurrentEditFaultId,
    } = useSelector(state=>state.faults)
    const databaseState = useSelector(state => state.database)
    const workloadState = useSelector(state => state.workload)
    const { hasTestStarted, currentRunId, lastRunId, manuallyStopped } = useSelector(state => state.testRun)
    const observabilityEnabled = useSelector(state => state.observability.enabled)
    // 目前不支持两个同名的fault在state中
    // 用于批量添加
    const [selectedFaultsState,setSelectedFaultsState] = useState([])
    // 初始分类：优先恢复上次选择；若该分类已不存在（yaml 清空、目录驱动后 database 被移除）→ 第一个非空分类
    const [selectedFaultType,setSelectedFaultType] =useState(() => {
        const hasFaults = (c) => Array.isArray(FAULT_CATEGORIES[c]) && FAULT_CATEGORIES[c].length > 0;
        if (persistedFaultType && hasFaults(persistedFaultType)) return persistedFaultType;
        return Object.keys(FAULT_CATEGORIES).find(hasFaults) || 'CPU';
    })
    // 每一个对象都是{fault.id:{paramName,vaule}}
    // 例如：{fault.id: { delayMs: '500', durationSec: '10' },}
    const [paramsValue,setParamsVaule] = useState(persistedParamsValue || {})
    // 添加后的本地记录
    const [allFaultsState,setAllFaults] = useState(selectedFaults)
    // 表示正在操作的fault在allFaults中的下标
    const [currentEditIndex,setCurrentEditIndex] = useState(
        Number.isInteger(persistedCurrentEditIndex) ? persistedCurrentEditIndex : -1
    )
    const [currentEditGroupId, setCurrentEditGroupId] = useState(persistedCurrentEditGroupId ?? null)
    const [currentEditFaultId, setCurrentEditFaultId] = useState(persistedCurrentEditFaultId ?? null)
    // 新增：组合模式相关 state
    const [expandedGroups, setExpandedGroups] = useState(persistedExpandedGroups || []);
    // 故障编排合理性校验（Neo4j 知识图谱，只读）
    const [checkingOrchestration, setCheckingOrchestration] = useState(false);
    const [orchestrationCheck, setOrchestrationCheck] = useState(null);
    const handleFaultTypeClick = (faultName) =>{
        setSelectedFaultType(faultName);
    }
    // Toast 状态
    const [toast, setToast] = useState({ show: false, message: '', type: 'success' });

    const showToast = (message, type = 'success') => {
        setToast({ show: true, message, type });
        setTimeout(() => setToast({ show: false, message: '', type }), 3000);
    };

    // 测试状态提示：'running'=正在测试, 'waiting'=测试完成等待结果生成, 'ready'=结果已生成, 'stopped'=手动终止
    const [postRunHint, setPostRunHint] = useState(hasTestStarted ? 'running' : null);
    const postRunHintRef = useRef(null);
    const historyPollTimerRef = useRef(null);
    const prevHasTestStartedRef = useRef(hasTestStarted);

    // 开始轮询历史记录，检测当前 runId 的结果是否已生成
    const startHistoryPolling = useCallback((runId) => {
        if (!runId) return;
        const poll = async () => {
            try {
                const data = await fetchHistoryList({});
                const historyMap = data?.historyMap || {};
                let found = false;
                for (const historyNames of Object.values(historyMap)) {
                    if (Array.isArray(historyNames) && historyNames.some(name => String(name).includes(String(runId)))) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    setPostRunHint('ready');
                    if (historyPollTimerRef.current) {
                        clearTimeout(historyPollTimerRef.current);
                        historyPollTimerRef.current = null;
                    }
                } else if (postRunHintRef.current === 'waiting') {
                    historyPollTimerRef.current = setTimeout(poll, 5000);
                }
            } catch {
                if (postRunHintRef.current === 'waiting') {
                    historyPollTimerRef.current = setTimeout(poll, 5000);
                }
            }
        };
        poll();
    }, []);

    // 清理轮询
    useEffect(() => {
        return () => {
            if (historyPollTimerRef.current) {
                clearTimeout(historyPollTimerRef.current);
            }
        };
    }, []);

    // 同步 ref，确保轮询中能读到最新状态
    useEffect(() => {
        postRunHintRef.current = postRunHint;
    }, [postRunHint]);

    // 检测测试结束：hasTestStarted 从 true 变为 false
    useEffect(() => {
        const wasRunning = prevHasTestStartedRef.current;
        prevHasTestStartedRef.current = hasTestStarted;
        if (wasRunning && !hasTestStarted) {
            if (manuallyStopped) {
                setPostRunHint('stopped');
            } else {
                setPostRunHint('waiting');
                const finishedRunId = currentRunId || lastRunId;
                if (finishedRunId) {
                    startHistoryPolling(finishedRunId);
                }
            }
        }
    }, [hasTestStarted, manuallyStopped, currentRunId, lastRunId, startHistoryPolling]);

    // 组件挂载时，如果测试已经结束但仍有 runId，恢复提示状态
    useEffect(() => {
        if (!hasTestStarted && (currentRunId || lastRunId) && !postRunHint) {
            if (manuallyStopped) {
                setPostRunHint('stopped');
            } else {
                setPostRunHint('waiting');
                const finishedRunId = currentRunId || lastRunId;
                if (finishedRunId) {
                    startHistoryPolling(finishedRunId);
                }
            }
        }
    }, []); // only on mount

    // 组合模式切换
    const handleSetGroupMode = (nextMode) => {
        if (nextMode === isGroupMode) return;
        dispatch(toggleGroupMode());
        setSelectedFaultsState([]);
    };

    // 创建组合
    const handleCreateGroup = () => {
        if (selectedFaultsState.length < 2) {
            showToast(translate('faultinject.selectFaultsForGroup'), 'error');
            return;
        }

        const groupId = `group-${Date.now()}`;
        const colors = ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1'];
        const randomColor = colors[Math.floor(Math.random() * colors.length)];

        const newGroup = {
            type: 'group',
            id: groupId,
            name: `${translate('faultinject.groupDefaultName')} ${allFaultsState.filter(f => f.type === 'group').length + 1}`,
            color: randomColor,
            faults: selectedFaultsState.map((fault, idx) => ({
                ...fault,
                id: `${groupId}-fault-${idx}`,
            })),
        };

        // 初始化该组合的 paramsValue
        const newParams = {};
        newParams['__groupDuration__'] = '60';  // 组级别统一持续时间
        newGroup.faults.forEach(fault => {
            const params = getFaultParam(fault);
            newParams[fault.id] = {};
            params.forEach(param => {
                newParams[fault.id][param.paramName] = String(param.defaultValue || '');
            });
        });

        setAllFaults([...allFaultsState, newGroup]);
        setParamsVaule({
            ...paramsValue,
            [groupId]: newParams,
        });

        setSelectedFaultsState([]);
        showToast(translate('faultinject.createGroup') + ' ' + 'success', 'success');
    };

    // 展开/折叠组合
    const handleToggleExpand = (groupId) => {
        setExpandedGroups(prev =>
            prev.includes(groupId)
                ? prev.filter(id => id !== groupId)
                : [...prev, groupId]
        );
    };

    // 编排或参数变化后，上一次的校验结果立即失效，避免旧结论误导
    useEffect(() => {
        setOrchestrationCheck(null);
    }, [allFaultsState, paramsValue]);
    useEffect(() => {
        dispatch(updateSelectedFaults(allFaultsState));
    }, [allFaultsState, dispatch]);
    useEffect(() => {
        dispatch(updateParamsValue(paramsValue));
    }, [paramsValue, dispatch]);
    useEffect(() => {
        dispatch(updateSelectedFaultType(selectedFaultType));
    }, [selectedFaultType, dispatch]);
    useEffect(() => {
        dispatch(updateExpandedGroups(expandedGroups));
    }, [expandedGroups, dispatch]);
    useEffect(() => {
        dispatch(updateCurrentEditState({
            currentEditIndex,
            currentEditGroupId,
            currentEditFaultId,
        }));
    }, [currentEditIndex, currentEditGroupId, currentEditFaultId, dispatch]);
    useEffect(() => {
        if (currentEditIndex >= allFaultsState.length) {
            setCurrentEditIndex(-1);
        }
        if (currentEditGroupId) {
            const group = allFaultsState.find((item) => item.id === currentEditGroupId && item.type === 'group');
            if (!group) {
                setCurrentEditGroupId(null);
                setCurrentEditFaultId(null);
                return;
            }
            if (currentEditFaultId && !group.faults.some((fault) => fault.id === currentEditFaultId)) {
                setCurrentEditFaultId(null);
            }
        }
    }, [allFaultsState, currentEditIndex, currentEditGroupId, currentEditFaultId]);
    const {translate, lang} = useLanguage()
    const validateParamValue = (value, param) => {

        if (!param.required && (!value || value.trim() === '')) return true;
        if (!value || value.trim() === '') return false;

        const val = value.trim();

        if (param.type === 'number') {
            return !isNaN(parseFloat(val)) && parseFloat(val) >= 0;
        }

        return true; // string 只要非空就合法
    };

    const handleFaultCardClick = ({fault,isSelected}) =>{

        if (isSelected) {
            // 如果被选中，直接删除
            setSelectedFaultsState(selectedFaultsState.filter(item=>item.nameKey !== fault.nameKey))
        }
        else {
            // 如果没有被选中，添加
            const params = getFaultParam(fault)
            const values = {}
            for (let i = 0 ; i < params.length ;i++){
                values[params[i].paramName] = params[i].defaultValue
            }
            setSelectedFaultsState([...selectedFaultsState,fault])
            // setParamsVaule({...paramsValue,[fault.name]:values})
            // 被选中的哪个一定被添加到末尾，注意set是异步更新，实际上selectedFaultState并没有变化，所以只要使用之前的值就行
            // setCurrentEditIndex(selectedFaultsState.length-1)
            // setCurrentEditIndex(selectedFaultsState.length)
        }

    }
    const handleFaultListItemClick = (type, indexOrGroupId, faultId) => {
        if (type === 'single') {
            setCurrentEditIndex(indexOrGroupId);
            setCurrentEditGroupId(null);
            setCurrentEditFaultId(null);
        } else if (type === 'group') {
            // 点击组合卡片头部 → 编辑组级别参数（如组持续时间）
            setCurrentEditGroupId(indexOrGroupId);
            setCurrentEditFaultId(null);
            setCurrentEditIndex(-1);
        } else if (type === 'inner') {
            setCurrentEditGroupId(indexOrGroupId);
            setCurrentEditFaultId(faultId);
            setCurrentEditIndex(-1);
        }
    };

    const hanldeFaultListItemDelete = (type, indexOrGroupId, faultId) => {
        if (type === 'single') {
            const index = indexOrGroupId;
            if (currentEditIndex === index) {
                setCurrentEditIndex(-1);
            }
            if (currentEditIndex > index) {
                setCurrentEditIndex(currentEditIndex - 1);
            }
            const removeKey = (obj, keyToRemove) => {
                const { [keyToRemove]: _, ...rest } = obj;
                return rest;
            };
            const fault_id = allFaultsState[index].id;
            setAllFaults(allFaultsState.filter((_, i) => i !== index));
            setParamsVaule(removeKey(paramsValue, fault_id));
        } else if (type === 'inner') {
            const groupId = indexOrGroupId;
            const newFaults = allFaultsState.map(fault => {
                if (fault.id === groupId && fault.type === 'group') {
                    return {
                        ...fault,
                        faults: fault.faults.filter(f => f.id !== faultId),
                    };
                }
                return fault;
            });

            const newParams = { ...paramsValue };
            if (newParams[groupId]) {
                const { [faultId]: _, ...rest } = newParams[groupId];
                newParams[groupId] = rest;
            }

            setAllFaults(newFaults);
            setParamsVaule(newParams);

            if (currentEditGroupId === groupId && currentEditFaultId === faultId) {
                setCurrentEditGroupId(null);
                setCurrentEditFaultId(null);
            }
        }
    };

    // 删除整个组合
    const handleDeleteGroup = (groupId) => {
        const groupIndex = allFaultsState.findIndex(f => f.id === groupId);
        if (groupIndex === -1) return;

        // 如果正在编辑这个组合，清除编辑状态
        if (currentEditGroupId === groupId) {
            setCurrentEditGroupId(null);
            setCurrentEditFaultId(null);
        }

        // 更新currentEditIndex（如果删除的是它前面的组合）
        if (currentEditIndex > groupIndex) {
            setCurrentEditIndex(currentEditIndex - 1);
        }

        // 删除组合
        const newFaults = allFaultsState.filter(f => f.id !== groupId);

        // 删除paramsValue
        const { [groupId]: _, ...restParams } = paramsValue;
        setAllFaults(newFaults);
        setParamsVaule(restParams);
    };

    const validateAllFaults = () => {
        for (const fault of allFaultsState) {
            const type = NAME_KEY_TO_TYPE[fault.nameKey]; // ✅ 反向查找 type
            if (!type) {
            console.warn(`Unknown fault nameKey: ${fault.nameKey}`);
            continue;
            }

            const faultDef = FAULT_DEFINITIONS[type];
            if (!faultDef) continue;

            const params = faultDef.params;
            const values = paramsValue[fault.id] || {};

            for (const param of params) {
            const value = values[param.paramName] ?? param.defaultValue;
            if (!validateParamValue(value, param)) {
                return false;
            }
            }
        }
        return true;
    };

    // 构造与「开始测试」完全一致的 faults 数组（组合内的故障带 groupKey）
    const buildFaultsPayload = () => {
        const result = [];
        allFaultsState.forEach((fault) => {
            if (fault.type === 'group') {
                const groupParams = paramsValue[fault.id] || {};
                const groupDuration = groupParams['__groupDuration__'];
                fault.faults.forEach((innerFault) => {
                    const innerParams = { ...(groupParams[innerFault.id] || {}) };
                    // 组级别 duration 覆盖内部故障的 duration
                    if (groupDuration) {
                        innerParams['duration'] = groupDuration;
                    }
                    result.push({
                        nameKey: innerFault.nameKey,
                        groupKey: fault.id,
                        params: innerParams,
                    });
                });
                return;
            }
            result.push({
                nameKey: fault.nameKey,
                params: { ...(paramsValue[fault.id] || {}) },
            });
        });
        return result;
    };

    // Start 按钮点击
    const buildRequestBody = () => {
        const workloadParams = workloadState.workload === 'smallbank'
            ? workloadState.smallbankConfig
            : workloadState.benchmarkSQLConfig;

        // 容器名是**顶层字段**（跟 faults 平级，不在 database 里）：非空时引擎会把
        // chaosblade 类故障套一层 docker exec 在容器内执行；留空就**根本不带这个键**，
        // 请求体跟以前逐字节一致（= 故障打在宿主机）。
        const containerName = (databaseState.containerName || '').trim();

        const body = {
            dbType: databaseState.type,
            workload: {
                name: workloadState.workload,
                params: workloadParams,
            },
            database: {
                deploymentType: databaseState.deploymentType,
                standalone: databaseState.standalone,
                primaryStandby: {
                    nodes: databaseState['primary-standby']?.nodes || [],
                },
                distributed: databaseState.distributed,
                embedded: databaseState.embedded,
                // 数据库配置参数（缓冲池 / 最大连接数…），来自「数据库配置」里点过「应用配置」的生效值。
                // 引擎不做执行、只用于归档：run-meta.json 靠它记录这次压测时库上的真实配置。
                params: databaseState.appliedParams || {},
            },
            faults: buildFaultsPayload(),
            autoTimingMode: 'fixed-time',
            runOptions: {
                monitorIntervalSec: 1,
                create: true,
                load: true,
                execute: true,
                reload: workloadParams.reload === true,
            },
        };

        if (containerName) {
            body.containerName = containerName;
        }
        return body;
    };

    // 监控采集一旦没开，这一轮就只在前端空转：ObservabilityPoller 不会建会话，
    // manager 侧没有这次运行，曲线和历史归档全都没有，而且不留任何痕迹。
    // 所以测试真的跑起来时这里把采集打开；监控页的开关仍然可以随时关掉。
    const ensureMonitoringEnabled = () => {
        if (!observabilityEnabled) {
            dispatch(setMonitoringEnabled(true));
        }
    };

    const handleStart = async () => {
        if (workloadState.dirty) {
            showToast(translate('Workload.saveRequiredBeforeStart') || '工作负载已修改，请先保存后再开始测试', 'error');
            return;
        }
        if (!validateAllFaults()) {
            showToast(translate('faultinject.startFailed'), 'error');
            return;
        }
        // v2.1：所选故障中存在未配置自检（check）的故障时，仅提示、不阻断
        const noCheckFaults = allFaultsState
            .flatMap((fault) => (fault.type === 'group' ? (fault.faults || []) : [fault]))
            .filter((f) => {
                const def = FAULT_DEFINITIONS[NAME_KEY_TO_TYPE[f.nameKey] || f.nameKey];
                return def && def.hasCheck === false;
            });
        if (noCheckFaults.length > 0) {
            const ok = window.confirm(translate('verification.noCheckConfirm'));
            if (!ok) return;
        }
        try {
            const response = await startTestByForm(buildRequestBody());
            if (!response?.accepted) {
                if (response?.message?.includes('执行中') && response?.runId) {
                    ensureMonitoringEnabled();
                    dispatch(setRunState({
                        hasTestStarted: true,
                        currentRunId: response.runId,
                        containerName: (databaseState.containerName || '').trim(),
                        dbType: databaseState.type,
                    }));
                    setPostRunHint('running');
                    if (historyPollTimerRef.current) {
                        clearTimeout(historyPollTimerRef.current);
                        historyPollTimerRef.current = null;
                    }
                    showToast(`${response.message} (${response.runId})`, 'success');
                    return;
                }
                showToast(response?.message || translate('faultinject.startFailed'), 'error');
                return;
            }
            dispatch(updateSelectedFaults(allFaultsState));
            ensureMonitoringEnabled();
            dispatch(setRunState({
                hasTestStarted: true,
                currentRunId: response.runId || '',
                containerName: (databaseState.containerName || '').trim(),
                dbType: databaseState.type,
            }));
            setPostRunHint('running');
            if (historyPollTimerRef.current) {
                clearTimeout(historyPollTimerRef.current);
                historyPollTimerRef.current = null;
            }
            showToast(response.runId ? `${translate('faultinject.startSuccess')} (${response.runId})` : translate('faultinject.startSuccess'), 'success');
        } catch (error) {
            showToast(error.message || translate('faultinject.startFailed'), 'error');
        }
    };

    const handleStop = async () => {
        try {
            await stopTestSuite();
            dispatch(setManuallyStopped(true));
            dispatch(finishRunState({ runId: currentRunId || lastRunId }));
            showToast(translate('faultinject.stopSuccess'), 'success');
        } catch (error) {
            showToast(error.message || translate('faultinject.stopSuccess'), 'error');
        }
    };

    // 校验编排是否合理：只读接口，不启动测试、不注入故障
    const handleCheckOrchestration = async () => {
        if (allFaultsState.length === 0) {
            showToast(translate('faultinject.checkEmpty'), 'error');
            return;
        }
        setCheckingOrchestration(true);
        setOrchestrationCheck(null);
        try {
            const result = await checkFaultOrchestration(buildFaultsPayload());
            setOrchestrationCheck(result);
            if (result?.checked === false) {
                showToast(result.message || translate('faultinject.checkError'), 'error');
            } else if (result?.reasonable) {
                showToast(translate('faultinject.checkPass'), 'success');
            } else {
                showToast(result?.message || translate('faultinject.checkFail'), 'error');
            }
        } catch (error) {
            setOrchestrationCheck(null);
            showToast(error.message || translate('faultinject.checkError'), 'error');
        } finally {
            setCheckingOrchestration(false);
        }
    };
    const getFaultCard = () => {

        const defs = FAULT_CATEGORIES[selectedFaultType].map(type => FAULT_DEFINITIONS[type]);
        return defs.map(def => ({
            name: displayFaultName(def, translate, lang), // v2.2：yaml name（含多语言）优先，词典兜底
            nameKey: def.nameKey,
            hasCheck: def.hasCheck,                 // 无自检标记（undefined=视为有自检）
            // description: translate(def.descriptionKey)
        }));
    };
    // const getFaultCard = () => {
    //     switch (selectedFaultType) {
    //         case 'CPU':
    //             // 模拟一个网络请求的结果
    //             return [
    //                 {name:translate('faultinject.fault.cpu.load'),descritption:'指定cpu负载的指定值'},
    //             ]
    //         case 'network':
    //             return [
    //                 {name:translate('faultinject.fault.network.package'),description:'模拟网络包丢失的场景，设置丢包百分比'),
    //                 {name:translate('faultinject.fault.network.delay'),description:'模拟网络环境较差，请求延迟的场景'),
    //                 {name:translate('faultinject.fault.network.unorder'),description:'网络包乱序，模拟网络情况不稳定是，请求顺序错乱情况下的场景'},
    //             ]
    //         case 'memory':
    //             return [
    //                 {name:translate('faultinject.fault.memory.load'),description:'可占用指定百分比的系统内存'}
    //             ]
    //         case 'disk':
    //             return [
    //                 {name:translate('faultinject.fault.disk.filemove'),description:'文件移动，模拟文件丢失，存放位置错误等场景'),
    //                 {name:translate('faultinject.fault.disk.fileremoved'),description:'模拟文件被误删除和不存在的场景'},
    //             ]
    //         default:
    //             console.log("unkown falut type")
    //             break;
    //     }
    // }
    const getFaultParam = (fault) => {
        // fault 应包含 nameKey 字段（例如：'faultinject.fault.cpu.load'）
        const definition = Object.values(FAULT_DEFINITIONS).find(
            def => def.nameKey === fault.nameKey
        );

        if (!definition) {
            console.warn('Unknown fault nameKey:', fault.nameKey);
            return [];
        }

        // 返回参数定义列表，每个参数包含：
        // - paramName
        // - descriptionKey（用于翻译）
        // - type, unit, required, defaultValue 等元数据
        return definition.params.map(param => ({
            ...param,
            faultName: fault.nameKey // 可选：用于调试或绑定
        }));
    };
    // const getFaultParam = (fault) => {
    //     console.log(fault)
    //     switch (fault.name) {
    //         case translate('faultinject.fault.cpu.load'):
    //             return [
    //                 {faultName:fault.name,paramName:"cpu-percent",paramDescription:"percent of burn CPU (0-100)",type:"number",unit:"%",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"cpu-list",paramDescription:"CPUs in which to allow burning (0-3 or 1,3)",type:"number",unit:"",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"cpu-count",paramDescription:"Cpu count",type:"number",unit:"%",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"duration",paramDescription:"durations(s) to climb",type:"number",unit:"s",defaultValue:"",required:false},
    //             ]
        
    //         case translate('faultinject.fault.network.package'):
    //             return [
    //                 {faultName:fault.name,paramName:"percent",paramDescription:"loss percent, [0, 100]",type:"number",unit:"%",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"interface",paramDescription:"Network interface, for example, eth0",type:"number",unit:"",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"destination-ip",paramDescription:"destination ip. Support for using mask to specify the ip range such as 92.168.1.0/24 or comma separated multiple ips, for example 10.0.0.1,11.0.0.1.",type:"IP",unit:"",defaultValue:"",required:false},
    //             ]
    //         case translate('faultinject.fault.network.delay'):
    //             return [
    //                 {faultName:fault.name,paramName:"time",paramDescription:"Delay time, ms",type:"number",unit:"ms",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"interface",paramDescription:"Network interface, for example, eth0",type:"IP",unit:"",defaultValue:"",required:true},
    //             ]
    //         case translate('faultinject.fault.network.unorder'):
    //             return [
    //                 {faultName:fault.name,paramName:"correlation",paramDescription:"Correlation on previous packet, value is between 0 and 100",type:"number",unit:"",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"interface",paramDescription:"Network interface, for example, eth0",type:"IP",unit:"",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"percent",paramDescription:"Packets are sent immediately percentage, must be positive integer without %, for example, --percent 50",type:"number",unit:"%",defaultValue:"",required:true},
    //             ]
    //         case translate('faultinject.fault.memory.load'):
    //             return [
    //                 {faultName:fault.name,paramName:"负载占比",paramDescription:"",type:"number",unit:"%",defaultValue:"",required:true}
    //             ]
    //         case translate('faultinject.fault.disk.filemove'):
    //             return [
    //                 {faultName:fault.name,paramName:"src path",paramDescription:"",type:"string",unit:"",defaultValue:"",required:true},
    //                 {faultName:fault.name,paramName:"dst path",paramDescription:"",type:"string",unit:"",defaultValue:"",required:true}
    //             ]
    //         case translate('faultinject.fault.disk.fileremoved'):
    //             return [
    //                 {faultName:fault.name,paramName:"file path",paramDescription:"",type:"string",unit:"",defaultValue:"",required:true}
    //             ]
    //         default:
    //             console.log("unknown fault param")
    //             break;
    //     }
    // }
    const renderFaultCard = () =>{
        const faults = getFaultCard()
        if (faults.length === 0) {
            return (
                <div className={faultCardStyle.cardContainer}>
                    <div className={faultCardStyle.emptyCategory}>
                        {translate('faultinject.emptyCategoryHint')}
                    </div>
                </div>
            )
        }
        return (
            <div className={faultCardStyle.cardContainer}>
            {faults.map(
                (fault)=>{
                    const isSelected = selectedFaultsState.some(f => f.nameKey === fault.nameKey);
                    const order = isSelected
                        ? selectedFaultsState.findIndex(f => f.nameKey === fault.nameKey) + 1
                        : null;
                    return <FaultCard
                            key={fault.nameKey || fault.name}
                            type={selectedFaultType}
                            isSelected={isSelected}
                            order = {order}
                            onClick={handleFaultCardClick}
                            value={{...fault}} />
                }
            )}
            </div>
        )
    }
    const handleAddSelectedFaults = () => {
        const newAllFaults = selectedFaultsState.map((fault, index) => {
            return { ...fault, id: `${Date.now()}-${index}` };
        });
        setAllFaults([...allFaultsState, ...newAllFaults]);
        setSelectedFaultsState([]);

        const buildFaultParamMap = (faults, parameters) => {
            const result = {};
            for (let i = 0; i < faults.length; i++) {
                const fault = faults[i];
                const paramList = parameters[i] || [];
                result[fault.id] = {};
                for (const param of paramList) {
                    const paramName = param.paramName;
                    const valueString = String(param.defaultValue).length === 0 ? " " : String(param.defaultValue);
                    result[fault.id][paramName] = valueString;
                }
            }
            return result;
        };

        const params = selectedFaultsState.map(fault => {
            return getFaultParam(fault);
        });
        setParamsVaule({ ...paramsValue, ...buildFaultParamMap(newAllFaults, params) });
    };
    const renderSelectionAction = () => {
        if (isGroupMode) {
            return (
                <div className={faultCardStyle.groupControlsPanel}>
                    <div className={faultCardStyle.groupControlsHeader}>
                        <div className={faultCardStyle.groupControlsTitle}>
                            {translate('faultinject.groupBuilderTitle')}
                        </div>
                        <div className={faultCardStyle.groupSelectionBadge}>
                            {translate('faultinject.selectedCount')}: {selectedFaultsState.length}
                        </div>
                    </div>
                    <div className={faultCardStyle.groupControlsHint}>
                        {translate('faultinject.groupBuilderHint')}
                    </div>
                    <div className={faultCardStyle.groupControls}>
                        <button
                            onClick={handleCreateGroup}
                            disabled={selectedFaultsState.length < 2}
                            className={faultCardStyle.createGroupButton}
                        >
                            {translate('faultinject.createGroup')} ({selectedFaultsState.length})
                        </button>
                    </div>
                </div>
            );
        }

        return (
            <div className={faultCardStyle.addButton}>
                <button onClick={handleAddSelectedFaults}>
                    {translate('faultinject.add')} ({selectedFaultsState.length})
                </button>
            </div>
        );
    };
    const renderFaultParam = () => {
        // 情况1：编辑单个故障
        if (currentEditIndex >= 0 && allFaultsState.length > 0) {
            const fault = allFaultsState[currentEditIndex];
            if (fault.type === 'group') return null;

            const faultParamsValue = paramsValue[fault.id] || {};
            const faultParams = getFaultParam(fault);

            return (
                <div className={faultCardStyle.paramContainer}>
                    {faultParams.map((param) => {
                        const paramName = param.paramName;
                        const paramValue = faultParamsValue[paramName] ?? param.defaultValue;
                        const description = pickI18n(param.description, lang) || translate(param.descriptionKey) || '';
                        const label = pickI18n(param.label, lang) || translate(`param.label.${paramName}`) || paramName;
                        const isEmpty = !paramValue || paramValue.trim() === '';
                        const isRequiredAndEmpty = param.required && isEmpty;

                        return (
                            <div key={paramName} className={faultCardStyle.paramGroup}>
                                <label>
                                    {param.required && <span className={faultCardStyle.required}>*</span>}
                                    {label}
                                </label>
                                {description && (
                                    <div className={faultCardStyle.description}>{description}</div>
                                )}
                                <div className={faultCardStyle.inputWrapper}>
                                    <input
                                        type="text"
                                        value={paramValue}
                                        onChange={(e) =>
                                            setParamsVaule({
                                                ...paramsValue,
                                                [fault.id]: { ...faultParamsValue, [paramName]: e.target.value }
                                            })
                                        }
                                        className={isRequiredAndEmpty ? faultCardStyle.requiredInput : ''}
                                    />
                                    {param.unit && <span className={faultCardStyle.unit}>{param.unit}</span>}
                                    {/* 路径类参数旁边挂「选择文件」：列出目标库实际的文件结构，点一条填进来。 */}
                                    <FileTargetPicker
                                        nameKey={fault.nameKey}
                                        paramName={paramName}
                                        onPick={(path) =>
                                            setParamsVaule({
                                                ...paramsValue,
                                                [fault.id]: { ...faultParamsValue, [paramName]: path }
                                            })
                                        }
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            );
        }


        // 情况3：编辑组合内故障
        if (currentEditGroupId && currentEditFaultId) {
            const group = allFaultsState.find(f => f.id === currentEditGroupId);
            if (!group) return null;

            const fault = group.faults.find(f => f.id === currentEditFaultId);
            if (!fault) return null;

            const faultParamsValue = paramsValue[currentEditGroupId]?.[currentEditFaultId] || {};
            const faultParams = getFaultParam(fault);

            return (
                <div className={faultCardStyle.paramContainer}>
                    <div className={faultCardStyle.paramGroupTitle}>
                        {group.name} - {fault.name}
                    </div>

                    {faultParams.filter(p => p.paramName !== 'duration').map((param) => {
                        const paramName = param.paramName;
                        const paramValue = faultParamsValue[paramName] ?? param.defaultValue;
                        const description = pickI18n(param.description, lang) || translate(param.descriptionKey) || '';
                        const label = pickI18n(param.label, lang) || translate(`param.label.${paramName}`) || paramName;
                        const isEmpty = !paramValue || paramValue.trim() === '';
                        const isRequiredAndEmpty = param.required && isEmpty;

                        return (
                            <div key={paramName} className={faultCardStyle.paramGroup}>
                                <label>
                                    {param.required && <span className={faultCardStyle.required}>*</span>}
                                    {label}
                                </label>
                                {description && (
                                    <div className={faultCardStyle.description}>{description}</div>
                                )}
                                <div className={faultCardStyle.inputWrapper}>
                                    <input
                                        type="text"
                                        value={paramValue}
                                        onChange={(e) =>
                                            setParamsVaule({
                                                ...paramsValue,
                                                [currentEditGroupId]: {
                                                    ...paramsValue[currentEditGroupId],
                                                    [currentEditFaultId]: {
                                                        ...faultParamsValue,
                                                        [paramName]: e.target.value,
                                                    },
                                                },
                                            })
                                        }
                                        className={isRequiredAndEmpty ? faultCardStyle.requiredInput : ''}
                                    />
                                    {param.unit && <span className={faultCardStyle.unit}>{param.unit}</span>}
                                    {/* 组内故障的参数：同样挂「选择文件」 */}
                                    <FileTargetPicker
                                        nameKey={fault.nameKey}
                                        paramName={paramName}
                                        onPick={(path) =>
                                            setParamsVaule({
                                                ...paramsValue,
                                                [currentEditGroupId]: {
                                                    ...paramsValue[currentEditGroupId],
                                                    [currentEditFaultId]: {
                                                        ...faultParamsValue,
                                                        [paramName]: path,
                                                    },
                                                },
                                            })
                                        }
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            );
        }

        return null;
    };
    const renderedFaultParam = renderFaultParam();

    return (
        <DndProvider backend={HTML5Backend}>
        <div className={faultCardStyle.pageShell}>
            {toast.show && (
            <div className={`${faultCardStyle.toast} ${faultCardStyle[toast.type]}`}>
                {toast.message}
            </div>
            )}
            <div className={faultCardStyle.selectionCard}>
                <div data-tour="fault-mode" className={`${faultCardStyle.modePanel} ${isGroupMode ? faultCardStyle.modePanelGroup : ''}`}>
                    <div className={faultCardStyle.modePanelInfo}>
                        <div className={faultCardStyle.modePanelTitle}>{translate('faultinject.modeTitle')}</div>
                        <div className={faultCardStyle.modePanelDescription}>
                            {isGroupMode
                                ? translate('faultinject.groupModeDescription')
                                : translate('faultinject.singleModeDescription')}
                        </div>
                    </div>
                    <div className={faultCardStyle.modePanelButtons}>
                        <button
                            type="button"
                            className={!isGroupMode ? faultCardStyle.modeButtonActive : faultCardStyle.modeButton}
                            onClick={() => handleSetGroupMode(false)}
                        >
                            {translate('faultinject.singleMode')}
                        </button>
                        <button
                            type="button"
                            className={isGroupMode ? faultCardStyle.modeButtonActive : faultCardStyle.modeButton}
                            onClick={() => handleSetGroupMode(true)}
                        >
                            {translate('faultinject.groupMode')}
                        </button>
                    </div>
                </div>
                <div data-tour="fault-type-selector" className={faultCardStyle.modeSwitchContainer}>
                    <FaultSelector selectedType={selectedFaultType} onClick={handleFaultTypeClick}/>
                </div>
                <div data-tour="fault-cards" className={faultCardStyle.cardArea}>
                    {renderFaultCard()}
                </div>
                <div data-tour="fault-add-action">
                {renderSelectionAction()}
                </div>
            </div>
            {allFaultsState.length > 0 && (
                <div data-tour="fault-list" className={faultCardStyle.listCard}>
                    <FaultList
                        faults={allFaultsState}
                        paramsValue={paramsValue}
                        onClick={handleFaultListItemClick}
                        onDelete={hanldeFaultListItemDelete}
                        onDeleteGroup={handleDeleteGroup}
                        onReorder={(newFaults, newParams) => {
                            setAllFaults(newFaults);
                            setParamsVaule(newParams);
                        }}
                        onInnerReorder={(groupId, dragIndex, hoverIndex) => {
                            const newFaults = allFaultsState.map(fault => {
                                if (fault.id === groupId && fault.type === 'group') {
                                    const newInnerFaults = [...fault.faults];
                                    const [dragged] = newInnerFaults.splice(dragIndex, 1);
                                    newInnerFaults.splice(hoverIndex, 0, dragged);
                                    return { ...fault, faults: newInnerFaults };
                                }
                                return fault;
                            });
                            setAllFaults(newFaults);
                        }}
                        expandedGroups={expandedGroups}
                        onToggleExpand={handleToggleExpand}
                        onUpdateGroupDuration={(groupId, value) => {
                            setParamsVaule(prev => ({
                                ...prev,
                                [groupId]: {
                                    ...(prev[groupId] || {}),
                                    ['__groupDuration__']: String(value),
                                }
                            }));
                        }}
                    />
                </div>
            )}
            {renderedFaultParam && (
                <div className={faultCardStyle.paramsCard}>
                    {renderedFaultParam}
                </div>
            )}
            <div data-tour="fault-actions" className={faultCardStyle.actionsCard}>
                <div className={faultCardStyle.testButtonsContainer}>
                <button
                    onClick={handleCheckOrchestration}
                    className={faultCardStyle.checkButton}
                    disabled={checkingOrchestration || allFaultsState.length === 0}
                >
                    {checkingOrchestration
                        ? translate("faultinject.checking")
                        : translate("faultinject.checkOrchestration")}
                </button>
                <button
                    onClick={handleStart}
                    className={faultCardStyle.startButton}
                    disabled={hasTestStarted}
                >
                    {translate("faultinject.start")}
                </button>
                <button
                    onClick={handleStop}
                    className={faultCardStyle.stopButton}
                    disabled={!hasTestStarted}
                >
                    {translate("faultinject.stop")}
                </button>
                </div>
                {orchestrationCheck && (
                    <div className={`${faultCardStyle.checkResult} ${
                        orchestrationCheck.checked && orchestrationCheck.reasonable
                            ? faultCardStyle.checkResultPass
                            : faultCardStyle.checkResultFail
                    }`}>
                        <div className={faultCardStyle.checkResultTitle}>
                            {orchestrationCheck.checked === false
                                ? translate("faultinject.checkError")
                                : orchestrationCheck.reasonable
                                    ? translate("faultinject.checkPass")
                                    : translate("faultinject.checkFail")}
                            {orchestrationCheck.mode === "in-memory"
                                ? ` ${translate("faultinject.checkInMemory")}`
                                : ""}
                        </div>
                        {orchestrationCheck.message && (
                            <div>{orchestrationCheck.message}</div>
                        )}
                        {orchestrationCheck.violations?.length > 0 && (
                            <ul className={faultCardStyle.checkResultList}>
                                {orchestrationCheck.violations.map((v, i) => (
                                    <li key={`${v.type}-${v.groupKey || "single"}-${i}`}>
                                        {v.message}
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                )}
            </div>
            {(hasTestStarted || currentRunId || lastRunId) && (
                <div data-tour="fault-chart" className={faultCardStyle.chartCard}>
                    {hasTestStarted && (currentRunId || lastRunId) ? <div className={faultCardStyle.runIdLabel}>Run ID: {currentRunId || lastRunId}</div> : null}
                    <RealTimeChart />
                    {postRunHint && (
                        <div className={faultCardStyle.postRunHint}>
                            <span className={
                                postRunHint === 'running' ? faultCardStyle.hintDotActive :
                                postRunHint === 'waiting' ? faultCardStyle.hintDotPulse :
                                postRunHint === 'stopped' ? faultCardStyle.hintDotDone :
                                faultCardStyle.hintDotDone
                            } />
                            {postRunHint === 'running'
                                ? translate('faultinject.testRunning')
                                : postRunHint === 'waiting'
                                    ? translate('faultinject.waitingForResults')
                                    : postRunHint === 'stopped'
                                        ? translate('faultinject.manuallyStopped')
                                        : translate('faultinject.resultsReady')}
                        </div>
                    )}
                </div>
            )}
        </div>
        </DndProvider>
    )
}
export default FaultInject;
