import React, { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import {
  setDatabaseConfig,
  setDbParams,
  setAppliedParams,
  clearAppliedParams,
  setApplyStatus,
  setDeploymentType,
  setConnectionStatus,
  loadDatabaseTypes,
} from '../../store/modules/databaseSlice';
import { loadWorkloads } from '../../store/modules/workloadSlice';
import styles from './DatabaseConfig.module.css';
import { useLanguage } from '../../i18n';
import { testDatabaseConnection, applyDatabaseParams } from '../../api/suiteApi';

/** 能改参数的库：只做单机 og / pg / mysql（ob、gaussdb、分布式都不支持） */
const PARAM_SUPPORTED_TYPES = ['opengauss', 'postgresql', 'postgre', 'mysql'];
/** 这两个参数在这些库里是 postmaster 上下文 → 必须重启才生效，所以必须配重启命令 */
const NEEDS_RESTART_TYPES = ['opengauss', 'postgresql', 'postgre'];

const DatabaseConfig = () => {
  const dispatch = useDispatch();
  const state = useSelector((state) => state.database);
  const { translate } = useLanguage();
  const backendTypeOptions = (state.availableTypes || []).map((type) => ({
    value: type,
    label: type,
  }));

  const [formData, setFormData] = useState({
    type: state.type || 'mysql',
  });

  const [saveButtonState, setSaveButtonState] = useState('default');
  const [testButtonState, setTestButtonState] = useState('default');
  const [applyButtonState, setApplyButtonState] = useState('default');
  const [toast, setToast] = useState({ show: false, message: '', type: 'success' });
  const [isSaved, setIsSaved] = useState(true);

  useEffect(() => {
    dispatch(loadDatabaseTypes(state.deploymentType));
  }, [dispatch, state.deploymentType]);

  useEffect(() => {
    setFormData({
      type: state.type || 'mysql',
    });
  }, [state.type]);

  useEffect(() => {
    if (state.type) {
      dispatch(loadWorkloads(state.type));
    }
  }, [dispatch, state.type]);

  useEffect(() => {
    // 进页面时 og/pg 的重启命令默认带上（是个可编辑的初值，不是只读的提示）。
    // 只在空着、或还停在某个库的默认值上时补 —— 手改过的不动。
    if (state.type && state.restartCommand !== getDefaultRestartCommand(state.type)
        && isDefaultRestartCommand(state.restartCommand)) {
      dispatch(setDbParams({ restartCommand: getDefaultRestartCommand(state.type) }));
    }
    // 只在换库类型时补默认值：把 state.restartCommand 放进依赖会让它在用户打字时也重跑，
    // 边打边被覆盖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dispatch, state.type]);

  function getDefaultPort(dbType, deploymentType = state.deploymentType) {
    // GaussDB 分布式(CN)默认端口 25308，单机默认端口 5434
    if (dbType === 'gaussdb') {
      return deploymentType === 'distributed' ? '25308' : '5434';
    }
    const portMap = {
      mysql: '3306',
      postgresql: '5432',
      opengauss: '5434',
      oceanbase: '2901'
    };
    return portMap[dbType] || '';
  }

  function getDefaultAuth(dbType) {
    if (dbType === 'opengauss') return { username: 'test1230', password: '' };
    if (dbType === 'gaussdb') return { username: 'test1230', password: '' };
    if (dbType === 'oceanbase') return { username: 'root@sys', password: '' };
    if (dbType === 'mysql') return { username: 'root', password: '' };
    if (dbType === 'postgresql' || dbType === 'postgre') return { username: 'postgres', password: '' };
    return null;
  }

  function getDefaultIp(dbType, deploymentType = state.deploymentType) {
    if (deploymentType === 'distributed' && dbType === 'oceanbase') {
      return '';
    }
    return null;
  }

  /**
   * 各库的重启命令默认值（og/pg 是 postmaster 参数，改完必须重启才生效）。
   * 必须写成后台执行：agent 等单条命令的上限是 30 秒，前台跑会被提前 kill。
   */
  function getDefaultRestartCommand(dbType) {
    if (dbType === 'opengauss') {
      return 'nohup su - YOUR_USER -c "gs_ctl restart -D /home/YOUR_USER/gaussdb-data/data -Z single_node" >/tmp/resbench_restart.log 2>&1 &';
    }
    if (dbType === 'postgresql' || dbType === 'postgre') {
      return 'nohup systemctl restart postgresql >/tmp/resbench_restart.log 2>&1 &';
    }
    return '';
  }

  /** 当前填的是不是"某个库的默认值"（= 用户没改过）——换库时这种情况才覆盖掉 */
  function isDefaultRestartCommand(value) {
    const trimmed = (value || '').trim();
    if (!trimmed) return true;
    return PARAM_SUPPORTED_TYPES.some((dbType) => getDefaultRestartCommand(dbType) === trimmed);
  }

  function applyDefaultConnection(config, dbType, deploymentType = state.deploymentType) {
    const updates = { ...config, port: getDefaultPort(dbType, deploymentType) };
    const defaultIp = getDefaultIp(dbType, deploymentType);
    const defaultAuth = getDefaultAuth(dbType);
    if (defaultIp) {
      updates.ip = defaultIp;
    }
    if (defaultAuth) {
      updates.username = defaultAuth.username;
      updates.password = defaultAuth.password;
    }
    return updates;
  }

  function getHostForValidation(value) {
    const trimmed = value.trim();
    if (!trimmed.includes('://')) return trimmed;
    try {
      return new URL(trimmed).hostname;
    } catch {
      return trimmed;
    }
  }

  const showToast = (message, type = 'success') => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: '', type }), 3000);
  };

  const resetSaveButton = () => setSaveButtonState('default');

  const validateForm = () => {
    const errors = [];

    if (!formData.type) {
      errors.push(translate('DatabaseConfig.type') + translate('DatabaseConfig.isRequired'));
    }

    if (state.deploymentType === 'standalone') {
      const config = state.standalone;
      if (!config.ip.trim()) {
        errors.push(translate('DatabaseConfig.ip') + translate('DatabaseConfig.isRequired'));
      } else {
        const ipRegex = /^(\d{1,3}\.){3}\d{1,3}$/;
        if (!ipRegex.test(config.ip.trim())) {
          errors.push(translate('DatabaseConfig.ipInvalid'));
        }
      }
      if (!config.port.trim()) {
        errors.push(translate('DatabaseConfig.port') + translate('DatabaseConfig.isRequired'));
      } else if (!/^\d+$/.test(config.port.trim()) || parseInt(config.port.trim(), 10) <= 0 || parseInt(config.port.trim(), 10) > 65535) {
        errors.push(translate('DatabaseConfig.port') + translate('DatabaseConfig.mustBeValidPort'));
      }
      if (!config.username.trim()) {
        errors.push(translate('DatabaseConfig.username') + translate('DatabaseConfig.isRequired'));
      }
      if (!config.password.trim()) {
        errors.push(translate('DatabaseConfig.password') + translate('DatabaseConfig.isRequired'));
      }
    } else if (state.deploymentType === 'distributed') {
      if (!state.distributed.ip.trim()) {
        errors.push(translate('DatabaseConfig.ip') + translate('DatabaseConfig.isRequired'));
      } else {
        const ipRegex = /^(\d{1,3}\.){3}\d{1,3}$/;
        if (!ipRegex.test(getHostForValidation(state.distributed.ip))) {
          errors.push(translate('DatabaseConfig.ipInvalid'));
        }
      }
      if (!state.distributed.port.trim()) {
        errors.push(translate('DatabaseConfig.port') + translate('DatabaseConfig.isRequired'));
      } else if (!/^\d+$/.test(state.distributed.port.trim()) || parseInt(state.distributed.port.trim(), 10) <= 0 || parseInt(state.distributed.port.trim(), 10) > 65535) {
        errors.push(translate('DatabaseConfig.port') + translate('DatabaseConfig.mustBeValidPort'));
      }
      if (!state.distributed.username.trim()) {
        errors.push(translate('DatabaseConfig.username') + translate('DatabaseConfig.isRequired'));
      }
      if (!state.distributed.password.trim()) {
        errors.push(translate('DatabaseConfig.password') + translate('DatabaseConfig.isRequired'));
      }
    }
    return errors;
  };

  const handleSave = () => {
    const errors = validateForm();

    if (errors.length > 0) {
      setSaveButtonState('error');
      showToast(errors[0], 'error');
      setTimeout(resetSaveButton, 2000);
      return;
    }

    dispatch(setDatabaseConfig(formData));
    setIsSaved(true);
    setSaveButtonState('success');
    showToast(translate('DatabaseConfig.saveSuccess') || '配置已保存！', 'success');
    setTimeout(resetSaveButton, 2000);
  };

  const handleTestConnection = async () => {
    const errors = validateForm();
    if (errors.length > 0) {
      dispatch(setConnectionStatus(`error: ${errors[0]}`));
      showToast(errors[0], 'error');
      return;
    }

    dispatch(setConnectionStatus(''));
    setTestButtonState('loading');

    try {
      const response = await testDatabaseConnection({
        dbType: state.type,
        deploymentType: state.deploymentType,
        standalone: state.standalone,
        distributed: state.distributed,
      });

      if (response?.success) {
        dispatch(setConnectionStatus('success'));
        showToast(response.message || translate('DatabaseConfig.testSuccess'), 'success');
      } else {
        const errorMsg = response?.message || translate('DatabaseConfig.testFail');
        dispatch(setConnectionStatus(`error: ${errorMsg}`));
        showToast(errorMsg, 'error');
      }
    } catch (error) {
      const errorMsg = error.message || translate('DatabaseConfig.testFail');
      dispatch(setConnectionStatus(`error: ${errorMsg}`));
      showToast(errorMsg, 'error');
    } finally {
      setTestButtonState('default');
    }
  };

  /** 改了输入就清掉上次的应用结果（否则按钮还挂着"已应用"，但填的值已经变了） */
  const handleParamChange = (field, value) => {
    dispatch(setApplyStatus(''));
    dispatch(setDbParams({ [field]: value }));
  };

  // 刷新页面后不要把上次会话的"已应用 / 应用失败"带出来：库里的实际情况可能早就变了
  useEffect(() => {
    dispatch(setApplyStatus(''));
  }, [dispatch]);

  /**
   * 应用数据库参数：缓冲池大小 / 最大连接数上限。
   *
   * 输入框里的值直接写进 redux（跟其它数据库配置一起被持久化）。
   * 成功后：① 把库里读回的实际值写回输入框；② 按**真实参数名**记进 appliedParams，
   * 启动压测时会作为 database.params 发出去（引擎只用于归档），让 run-meta.json 记的是
   * 库里实际生效的配置，而不是另一份手填值。
   */
  const handleApplyParams = async () => {
    const errors = validateForm();
    if (errors.length > 0) {
      showToast(errors[0], 'error');
      return;
    }
    if (state.deploymentType !== 'standalone') {
      showToast(translate('DatabaseConfig.applyOnlyStandalone'), 'error');
      return;
    }
    if (!PARAM_SUPPORTED_TYPES.includes(state.type)) {
      showToast(translate('DatabaseConfig.applyUnsupportedType', { type: state.type }), 'error');
      return;
    }

    const bufferPool = (state.bufferPool || '').trim();
    const maxConnections = (state.maxConnections || '').trim();
    if (!bufferPool && !maxConnections) {
      showToast(translate('DatabaseConfig.applyNeedParams'), 'error');
      return;
    }
    const restartCommand = (state.restartCommand || '').trim();
    if (NEEDS_RESTART_TYPES.includes(state.type) && !restartCommand) {
      showToast(translate('DatabaseConfig.applyNeedRestart'), 'error');
      return;
    }

    setApplyButtonState('loading');
    dispatch(setApplyStatus(''));
    try {
      const response = await applyDatabaseParams({
        dbType: state.type,
        deploymentType: state.deploymentType,
        standalone: state.standalone,
        params: { bufferPool, maxConnections },
        restart: { command: restartCommand, waitSec: 180 },
      });

      if (response?.success) {
        dispatch(setApplyStatus('success'));
        // 只回写这次真正改动的参数（以 response.targets 的键为准），并跳过引擎的"读不到"占位 ——
        // 那是字符串（truthy），直接回写会把"读不到"塞进输入框和归档里
        const targets = response.targets || {};
        const after = response.after || {};
        const usable = (value) => Boolean(value) && value !== '读不到';
        const nextInput = {};
        if (targets.bufferPool && usable(after.bufferPool)) nextInput.bufferPool = after.bufferPool;
        if (targets.maxConnections && usable(after.maxConnections)) {
          nextInput.maxConnections = after.maxConnections;
        }
        if (Object.keys(nextInput).length > 0) {
          dispatch(setDbParams(nextInput));
        }
        // 语义键 → 真实参数名（引擎的 actualNames 给了映射，前端不用再抄一份）
        const applied = {};
        Object.entries(response.actualNames || {}).forEach(([semantic, actualName]) => {
          if (actualName && targets[semantic] && usable(after[semantic])) {
            applied[actualName] = after[semantic];
          }
        });
        if (Object.keys(applied).length > 0) {
          dispatch(setAppliedParams(applied));
        }
        // ignored 不能吞掉：只发一个错键会被"两个都空"拦住，但"一个对 + 一个错"时错键会无声消失
        const ignored = response.ignored || [];
        showToast(
          ignored.length > 0
            ? translate('DatabaseConfig.applyIgnored', {
              params: ignored.join(translate('common.listSeparator')),
            })
            : response.message || translate('DatabaseConfig.applyApplied'),
          'success',
        );
      } else {
        const errorMsg = response?.message || translate('DatabaseConfig.applyFailed');
        dispatch(setApplyStatus(`error: ${errorMsg}`));
        showToast(errorMsg, 'error');
      }
    } catch (error) {
      const errorMsg = error.message || translate('DatabaseConfig.applyFailed');
      dispatch(setApplyStatus(`error: ${errorMsg}`));
      showToast(errorMsg, 'error');
    } finally {
      setApplyButtonState('default');
    }
  };

  /**
   * 连接目标变了（库类型 / 部署方式 / ip / 端口 / 账号），之前"已应用"的结论就不再成立：
   * 按钮不能还挂着"已应用"，appliedParams 也不能继续以"库里实际生效值"的名义被归档。
   */
  const invalidateApplied = () => {
    dispatch(setApplyStatus(''));
    dispatch(clearAppliedParams());
    setApplyButtonState('default');
  };

  const handleDeploymentTypeChange = (e) => {
    const newDeploymentType = e.target.value;
    setIsSaved(false);
    dispatch(setDeploymentType(newDeploymentType));
    invalidateApplied();
  };

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    dispatch(setConnectionStatus(''));
    setIsSaved(false);
    invalidateApplied();
    setFormData((prev) => ({ ...prev, [name]: value }));
    if (name === 'type') {
      dispatch(setDatabaseConfig({ type: value }));
      dispatch(loadWorkloads(value));
      
      if (state.deploymentType === 'standalone') {
        const configType = state.deploymentType;
        const updates = applyDefaultConnection(state[configType], value);
        dispatch(setDatabaseConfig({
          [configType]: updates
        }));
      }

      // 换库类型时把重启命令一并对齐到该库的默认值。
      // 只在"没填过 / 填的还是某个库的默认值"时覆盖 —— 手填过的命令不能被换库悄悄冲掉。
      if (isDefaultRestartCommand(state.restartCommand)) {
        dispatch(setDbParams({ restartCommand: getDefaultRestartCommand(value) }));
      }
    }
  };

  const handleStandaloneInputChange = (field, value) => {
    dispatch(setConnectionStatus(''));
    setIsSaved(false);
    invalidateApplied();
    dispatch(setDatabaseConfig({
      standalone: { ...state.standalone, [field]: value }
    }));
  };

  const handleDistributedInputChange = (field, value) => {
    dispatch(setConnectionStatus(''));
    setIsSaved(false);
    invalidateApplied();
    dispatch(setDatabaseConfig({
      distributed: { ...state.distributed, [field]: value }
    }));
  };

  /**
   * 容器名：只是"故障往哪儿注入"的开关，不改连接目标，也不改库上的参数。
   * 所以不像 handleStandaloneInputChange 那样清 connectionStatus / appliedParams ——
   * 那些是从 ip:port 那个库上读回来的，换个容器名并不会让它们失效。
   */
  const handleContainerNameChange = (value) => {
    setIsSaved(false);
    dispatch(setDatabaseConfig({ containerName: value }));
  };

  const getSaveButtonText = () => {
    if (saveButtonState === 'error') return translate('DatabaseConfig.saveFailed');
    if (isSaved) return translate('DatabaseConfig.saved') || '已保存';
    return translate('DatabaseConfig.saveBtn') || 'Save';
  };

  const getTestButtonText = () => {
    if (testButtonState === 'loading') return translate('DatabaseConfig.testing');
    if (state.connectionStatus === 'success') return translate('DatabaseConfig.connected') || '已连接';
    return translate('DatabaseConfig.testBtn') || 'Test Connection';
  };

  const getApplyButtonText = () => {
    if (applyButtonState === 'loading') return translate('DatabaseConfig.applying');
    if (state.applyStatus === 'success') return translate('DatabaseConfig.applied');
    // 失败原因保留在按钮上（toast 3 秒就没了），详细原因看 title
    if ((state.applyStatus || '').startsWith('error')) return translate('DatabaseConfig.applyFailedShort');
    return translate('DatabaseConfig.applyBtn') || '应用配置';
  };

  const applyErrorDetail = (state.applyStatus || '').startsWith('error')
    ? state.applyStatus.replace(/^error:\s*/, '')
    : '';

  const getDatabaseTypeOptions = (deploymentType = state.deploymentType) => {
    switch (deploymentType) {
      case 'standalone':
        return [
          { value: 'mysql', label: 'MySQL' },
          { value: 'postgresql', label: 'PostgreSQL' },
          { value: 'opengauss', label: 'openGauss' },
          { value: 'gaussdb', label: 'GaussDB' }
        ];
      case 'primary-standby':
        return [
          { value: 'opengauss', label: 'openGauss' }
        ];
      case 'distributed':
        return [
          { value: 'oceanbase', label: 'OceanBase' },
          { value: 'gaussdb', label: 'GaussDB' }
        ];
      default:
        return [
          { value: 'mysql', label: 'MySQL' },
          { value: 'postgresql', label: 'PostgreSQL' },
          { value: 'opengauss', label: 'openGauss' },
          { value: 'gaussdb', label: 'GaussDB' }
        ];
    }
  };

  const typeOptions = backendTypeOptions.length > 0
    ? backendTypeOptions
    : getDatabaseTypeOptions();

  useEffect(() => {
    if (backendTypeOptions.length === 0) return;
    if (!state.type || !backendTypeOptions.some((option) => option.value === state.type)) {
      let fallbackType = 'mysql';
      if (state.deploymentType === 'distributed') fallbackType = 'oceanbase';
      const matchedType = backendTypeOptions.find((option) => option.value === fallbackType)?.value
        || backendTypeOptions[0].value;
      setFormData((prev) => ({ ...prev, type: matchedType }));
      dispatch(setDatabaseConfig({ type: matchedType }));
      dispatch(loadWorkloads(matchedType));
      
      if (state.deploymentType === 'standalone') {
        const configType = state.deploymentType;
        const updates = applyDefaultConnection(state[configType], matchedType);
        dispatch(setDatabaseConfig({
          [configType]: updates
        }));
      } else if (state.deploymentType === 'distributed') {
        const updates = applyDefaultConnection(state.distributed, matchedType);
        dispatch(setDatabaseConfig({
          distributed: updates
        }));
      } else if (state.deploymentType === 'primary-standby') {
        const nodes = state['primary-standby'].nodes.map(n => {
          return applyDefaultConnection(n, matchedType);
        });
        dispatch(setDatabaseConfig({
          'primary-standby': { ...state['primary-standby'], nodes }
        }));
      }
    }
  }, [backendTypeOptions, dispatch, state.deploymentType, state.type]);

  const renderConnectionFields = (config, onChangeHandler) => (
    <div className={styles.configSection}>
      <div className={styles.formRowHorizontal}>
        <div className={styles.formGroup}>
          <label htmlFor="ip">{translate('DatabaseConfig.ip')}</label>
          <input
            type="text"
            id="ip"
            name="ip"
            value={config.ip}
            onChange={(e) => onChangeHandler('ip', e.target.value)}
            placeholder="127.0.0.1"
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="port">{translate('DatabaseConfig.port')}</label>
          <input
            type="text"
            id="port"
            name="port"
            value={config.port}
            onChange={(e) => onChangeHandler('port', e.target.value)}
            placeholder="3306"
            className={styles.input}
          />
        </div>
      </div>
      <div className={styles.formRowHorizontal}>
        <div className={styles.formGroup}>
          <label htmlFor="username">{translate('DatabaseConfig.username')}</label>
          <input
            type="text"
            id="username"
            name="username"
            value={config.username}
            onChange={(e) => onChangeHandler('username', e.target.value)}
            className={styles.input}
          />
        </div>
        <div className={styles.formGroup}>
          <label htmlFor="password">{translate('DatabaseConfig.password')}</label>
          <input
            type="password"
            id="password"
            name="password"
            value={config.password}
            onChange={(e) => onChangeHandler('password', e.target.value)}
            className={styles.input}
          />
        </div>
      </div>
    </div>
  );


  return (
    <div className={styles.container}>
      {toast.show && (
        <div className={`${styles.toast} ${styles[toast.type]}`}>
          {toast.message}
        </div>
      )}

      <div className={styles.formRowHorizontal}>
        <div className={styles.formGroup}>
          <label htmlFor="deploymentType">{translate('DatabaseConfig.deploymentType')}</label>
          <select
            id="deploymentType"
            name="deploymentType"
            value={state.deploymentType}
            onChange={handleDeploymentTypeChange}
            className={styles.select}
          >
            <option value="standalone">{translate('DatabaseConfig.deploymentType.standalone')}</option>
            <option value="distributed">{translate('DatabaseConfig.deploymentType.distributed')}</option>
          </select>
        </div>

        <div className={styles.formGroup}>
          <label htmlFor="type">{translate('DatabaseConfig.type')}</label>
          <select
            id="type"
            name="type"
            value={formData.type}
            onChange={handleInputChange}
            className={styles.select}
          >
            {typeOptions.map(option => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {state.deploymentType === 'standalone' && renderConnectionFields(state.standalone, handleStandaloneInputChange)}
      {state.deploymentType === 'distributed' && renderConnectionFields(state.distributed, handleDistributedInputChange)}

      <div className={styles.configSection}>
        <div className={styles.formGroup}>
          <label htmlFor="containerName">{translate('DatabaseConfig.containerName')}</label>
          <input
            type="text"
            id="containerName"
            name="containerName"
            value={state.containerName || ''}
            onChange={(e) => handleContainerNameChange(e.target.value)}
            placeholder="my-postgres"
            className={styles.input}
          />
          <span className={styles.hint}>
            {translate('DatabaseConfig.containerNameHint')}
          </span>
        </div>
      </div>

      {state.deploymentType === 'standalone' && PARAM_SUPPORTED_TYPES.includes(state.type) && (
        <div className={styles.configSection}>
          <div className={styles.formRowHorizontal}>
            <div className={styles.formGroup}>
              <label htmlFor="bufferPool">{translate('DatabaseConfig.bufferPool') || '缓冲池大小'}</label>
              <input
                type="text"
                id="bufferPool"
                name="bufferPool"
                value={state.bufferPool || ''}
                onChange={(e) => handleParamChange('bufferPool', e.target.value)}
                placeholder={state.type === 'mysql' ? '2G' : '2GB'}
                className={styles.input}
              />
            </div>
            <div className={styles.formGroup}>
              <label htmlFor="maxConnections">{translate('DatabaseConfig.maxConnections') || '最大连接数上限'}</label>
              <input
                type="text"
                id="maxConnections"
                name="maxConnections"
                value={state.maxConnections || ''}
                onChange={(e) => handleParamChange('maxConnections', e.target.value)}
                placeholder="500"
                className={styles.input}
              />
            </div>
          </div>
          {NEEDS_RESTART_TYPES.includes(state.type) && (
            <div className={styles.formGroup}>
              <label htmlFor="restartCommand">{translate('DatabaseConfig.restartCommand') || '重启命令（必须重启才生效）'}</label>
              <input
                type="text"
                id="restartCommand"
                name="restartCommand"
                value={state.restartCommand || ''}
                onChange={(e) => handleParamChange('restartCommand', e.target.value)}
                placeholder={getDefaultRestartCommand(state.type) || ''}
                className={styles.input}
              />
              <span className={styles.hint}>
                {translate('DatabaseConfig.agentRestartHint')}
              </span>
              {(state.containerName || '').trim() !== '' && (
                <span className={styles.hint}>
                  {translate('DatabaseConfig.restartCommandContainerHint')}
                </span>
              )}
            </div>
          )}
          <span className={styles.hint}>
            {translate('DatabaseConfig.applySupportedHint')}
          </span>
        </div>
      )}

      {state.deploymentType === 'standalone' && !PARAM_SUPPORTED_TYPES.includes(state.type) && (
        <span className={styles.hint}>
          {translate('DatabaseConfig.applyUnsupportedHint', { type: state.type })}
        </span>
      )}

      <div className={styles.btnContainer}>
        <button
          className={`${styles.btnSave} ${
            saveButtonState === 'error'
              ? styles.btnError
              : ''
          }`}
          onClick={handleSave}
          disabled={isSaved}
        >
          {getSaveButtonText()}
        </button>
        <button
          className={`${styles.btnTest} ${
            testButtonState === 'loading' ? styles.btnLoading : ''
          }`}
          onClick={handleTestConnection}
          disabled={testButtonState === 'loading'}
        >
          {getTestButtonText()}
        </button>
        <button
          className={`${styles.btnApply} ${
            applyButtonState === 'loading'
              ? styles.btnLoading
              : state.applyStatus === 'success'
                ? styles.btnSuccess
                : (state.applyStatus || '').startsWith('error')
                  ? styles.btnError
                  : ''
          }`}
          onClick={handleApplyParams}
          disabled={applyButtonState === 'loading'}
          title={applyErrorDetail || undefined}
        >
          {getApplyButtonText()}
        </button>
      </div>
    </div>
  );
};

export default DatabaseConfig;
