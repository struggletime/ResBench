import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { fetchWorkloads } from '../../api/suiteApi';

function normalizeWorkloadName(name) {
  const value = String(name || '').toLowerCase();
  if (value === 'benchmarksql') return 'benchmarkSQL';
  if (value === 'ycsb') return 'ycsb';
  if (value === 'smallbank') return 'smallbank';
  if (value === 'tpcc') return 'tpcc';
  return name;
}

export const loadWorkloads = createAsyncThunk(
  'workload/loadWorkloads',
  async (dbType, { rejectWithValue }) => {
    if (!dbType) return [];
    try {
      const data = await fetchWorkloads(dbType);
      if (!Array.isArray(data)) return [];
      return data.map(normalizeWorkloadName);
    } catch (error) {
      return rejectWithValue(error.message || 'Failed to load workloads');
    }
  }
);

const workloadSlice = createSlice({
    name:'workload',
    initialState:{
        workload:'smallbank',
      dirty: false,
        availableWorkloads: [],
        availableWorkloadsLoading: false,
        availableWorkloadsError: '',
        benchmarkSQLConfig:{reload:false,
            warehouses:'',
            concurrency:'',
            duration:'',
        },
        smallbankConfig:{
            accountsNum:'1000',
            terminals:'100',
            duration:'120',
            amalgamateWeight:'15',
            balanceWeight:'15',
            depositCheckingWeight:'15',
            sendPaymentWeight:'25',
            transactSavingsWeight:'15',
            writeCheckWeight:'15'
        },
        ycsbConfig: {
          recordCount: '1000000',
          threadCount: '50',
          duration: '300',
          operationCount: '',
          requestDistribution: 'uniform',
          readProportion: '1.0',
          updateProportion: '0',
          insertProportion: '0',
          scanProportion: '0',
        }
    },
    reducers:{
        setWorkload: (state,action) =>{
            state.workload = action.payload;
        },
        updateBenchmarkSQLConfig : (state,action)=>{
            state.benchmarkSQLConfig = action.payload
        state.dirty = false;
        },
        updateSmallbankConfig: (state,action)=>{
            state.smallbankConfig = action.payload
        state.dirty = false;
      },
      updateYcsbConfig: (state, action) => {
        state.ycsbConfig = action.payload;
        state.dirty = false;
      },
      setWorkloadDirty: (state, action) => {
        state.dirty = Boolean(action.payload);
      },
      markWorkloadSaved: (state) => {
        state.dirty = false;
      }
    },
    extraReducers: (builder) => {
      builder
        .addCase(loadWorkloads.pending, (state) => {
          state.availableWorkloadsLoading = true;
          state.availableWorkloadsError = '';
        })
        .addCase(loadWorkloads.fulfilled, (state, action) => {
          state.availableWorkloadsLoading = false;
          state.availableWorkloads = action.payload;
          if (action.payload.length > 0) {
            if (action.payload.includes('smallbank')) {
              state.workload = 'smallbank';
            } else if (!action.payload.includes(state.workload)) {
              state.workload = action.payload[0];
            }
          }
        })
        .addCase(loadWorkloads.rejected, (state, action) => {
          state.availableWorkloadsLoading = false;
          state.availableWorkloadsError = action.payload || action.error.message || 'Failed to load workloads';
        });
    }
})

export const {setWorkload,updateBenchmarkSQLConfig,updateSmallbankConfig,updateYcsbConfig,setWorkloadDirty,markWorkloadSaved} = workloadSlice.actions;
export default workloadSlice.reducer;
