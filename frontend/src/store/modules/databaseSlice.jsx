// src/store/modules/databaseSlice.js
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { fetchDatabaseTypes } from '../../api/suiteApi';

export const loadDatabaseTypes = createAsyncThunk(
  'database/loadDatabaseTypes',
  async (deploymentType) => {
    const data = await fetchDatabaseTypes(deploymentType);
    if (!Array.isArray(data)) return [];
    return data;
  }
);

const DEFAULT_DB_NODE = {
  ip: '10.81.0.45',
  port: '5434',
  username: 'test1230',
  password: '',
};

const DEFAULT_OCEANBASE_NODE = {
  ip: '10.81.0.45',
  port: '2901',
  username: 'root@sys',
  password: '',
};

const databaseSlice = createSlice({
  name: 'database',
  initialState: {
    deploymentType: 'standalone', // standalone, distributed
    standalone: {
      ...DEFAULT_DB_NODE,
    },
    distributed: {
      ...DEFAULT_OCEANBASE_NODE,
    },
    type: 'opengauss', // 默认数据库类型
    availableTypes: [],
    availableTypesLoading: false,
    availableTypesError: '',
    isConnected: false,
    connectionStatus: '', // 状态信息: '' (空), 'success', 'error: 具体原因'
    // 数据库跑在 Docker 容器里时填容器名；留空 = 故障直接打在宿主机（以前的行为）。
    // 非空时引擎会把 chaosblade 类故障（cpu/memory/disk/file/process）套一层
    // docker exec 在容器内执行；网络类故障与 DBChaos 类不受影响，仍走宿主机/ JDBC。
    containerName: '',
    // 数据库参数（「应用配置」用）：空串表示这个参数不动
    bufferPool: '', // 缓冲池大小：og/pg → shared_buffers，mysql → innodb_buffer_pool_size
    maxConnections: '', // 最大连接数上限 → max_connections
    restartCommand: '', // 重启命令，og/pg 这类必须重启的库必填（跟其它配置一起持久化）
    applyStatus: '', // ''(空) / 'success' / 'error: 具体原因'
    // 已真正应用到库上的参数，按**真实参数名**存（shared_buffers / innodb_buffer_pool_size / max_connections）。
    // 启动压测时会作为 database.params 发给引擎，只用于归档（引擎不拿它做执行），
    // 这样 run-meta.json 记的就是库里实际生效的配置，而不是另一份手填值。
    appliedParams: {},
  },
  reducers: {
    setDatabaseConfig: (state, action) => {
      return { ...state, ...action.payload };
    },
    setAppliedParams: (state, action) => {
      // 合并而不是替换：这次只改了其中一个参数时，另一个参数的生效值不能被丢掉
      state.appliedParams = { ...state.appliedParams, ...(action.payload || {}) };
    },
    clearAppliedParams: (state) => {
      // 连接目标变了（换库/换主机）→ 这些值不再代表"目标库上实际生效的配置"，必须清掉，
      // 否则会被当成 database.params 归档进 run-meta.json，记的却是另一个库的配置
      state.appliedParams = {};
    },
    setDbParams: (state, action) => {
      const { bufferPool, maxConnections, restartCommand } = action.payload;
      if (bufferPool !== undefined) state.bufferPool = bufferPool;
      if (maxConnections !== undefined) state.maxConnections = maxConnections;
      if (restartCommand !== undefined) state.restartCommand = restartCommand;
    },
    setApplyStatus: (state, action) => {
      state.applyStatus = action.payload;
    },
    setDeploymentType: (state, action) => {
      state.deploymentType = action.payload;
    },
    setConnectionStatus: (state, action) => {
      state.connectionStatus = action.payload;
    },
    clearConnectionStatus: (state) => {
      state.connectionStatus = '';
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(loadDatabaseTypes.pending, (state) => {
        state.availableTypesLoading = true;
        state.availableTypesError = '';
        state.availableTypes = [];
      })
      .addCase(loadDatabaseTypes.fulfilled, (state, action) => {
        state.availableTypesLoading = false;
        state.availableTypes = action.payload;
        if (action.payload.length > 0 && !action.payload.includes(state.type)) {
          if (state.deploymentType === 'distributed' && action.payload.includes('oceanbase')) {
            state.type = 'oceanbase';
          } else {
            state.type = action.payload[0];
          }
        }
      })
      .addCase(loadDatabaseTypes.rejected, (state, action) => {
        state.availableTypesLoading = false;
        state.availableTypesError = action.error.message || 'Failed to load database types';
      });
  },
});

export const {
  setDatabaseConfig,
  setDbParams,
  setAppliedParams,
  clearAppliedParams,
  setApplyStatus,
  setDeploymentType,
  setConnectionStatus,
  clearConnectionStatus,
} = databaseSlice.actions;

export default databaseSlice.reducer;
