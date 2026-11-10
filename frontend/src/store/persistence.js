const STORAGE_KEY = 'resbench_config';

export function loadPersistedState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return migratePersistedState(JSON.parse(raw));
  } catch {
    localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

function migratePersistedState(state) {
  if (state?.database?.type === 'oceanbase'
      && state.database.deploymentType === 'distributed'
      && state.database.distributed) {
    return {
      ...state,
      database: {
        ...state.database,
        distributed: {
          ...state.database.distributed,
          port: ['2903', '2881'].includes(state.database.distributed.port) ? '2901' : state.database.distributed.port,
          username: state.database.distributed.username === 'root' ? 'root@sys' : state.database.distributed.username,
        },
      },
    };
  }
  return state;
}

export function persistState(state) {
  try {
    const toPersist = {
      database: state.database,
      workload: state.workload,
      faults: state.faults,
      testRun: state.testRun,
      observability: {
        enabled: state.observability.enabled,
        selectedTargets: state.observability.selectedTargets,
        selectedMetricIds: state.observability.selectedMetricIds,
        selectedRunId: state.observability.selectedRunId,
      },
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(toPersist));
  } catch {
    // Storage full or unavailable — silently ignore
  }
}
