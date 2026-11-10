// src/config/faultConfig.js

// 故障类型常量（推荐使用）
export const FAULT_TYPES = {
  // CPU 相关
  CPU_LOAD: 'cpu.load',

  // 网络相关
  NETWORK_PACKET_LOSS: 'network.packet-loss',
  NETWORK_DELAY: 'network.delay',
  NETWORK_REORDER: 'network.reorder',
  NETWORK_PACKET_DUPLICATION: 'network.packet-duplication',
  NETWORK_PACKET_CORRUPT: 'network.packet-corrupt',

  // 内存相关
  MEMORY_LOAD: 'memory.load',

  // 磁盘相关
  DISK_FILE_MOVE: 'disk.file-move',
  DISK_FILE_REMOVE: 'disk.file-remove',
  DISK_FILE_APPEND: 'disk.file-append',
  DISK_FILE_CHMOD: 'disk.file-chmod',
  DISK_FILL: 'disk.fill', // 新增
  // 进程相关（新增大类）
  PROCESS_STOP: 'process.stop',
  PROCESS_KILL: 'process.kill',


  APPLICATION_ERROR:'application.error',

    // 新增：数据库错误
  CONNECTION_SURGE: 'connection.surge',
  CONNECTION_LIMIT_EXCEEDED: 'connection.limit.exceeded',
  CONNECTION_THREAD_SATURATION: 'connection.thread.saturation',
  TRANSACTION_STACK_OVERFLOW: 'transaction.stack.overflow',
  TRANSACTION_MASSIVE_ROLLBACK: 'transaction.massive.rollback',
  SQL_PLAN_FLIP: 'sql.plan.flip',
  SQL_STACK_OVERFLOW_SQL_DEPTH: 'sql.stack.overflow.sql.depth',
  SQL_STACK_OVERFLOW_VIEW_NEST: 'sql.stack.overflow.view.nest',
  SQL_STACK_OVERFLOW_JOIN_BOMB: 'sql.stack.overflow.join.bomb',
  EXEC_STACK_OVERFLOW_FUNC_RECURSE: 'exec.stack.overflow.func.recurse',
  EXEC_STACK_OVERFLOW_PROC_RECURSE: 'exec.stack.overflow.proc.recurse',
  EXEC_READ_AMP_TRAP: 'exec.read.amp.trap',
  TXN_UNCOMMITTED: 'txn.uncommitted',
  TXN_DUPLICATE: 'txn.duplicate',
  TXN_DEADLOCK_STORM: 'txn.deadlock.storm',
  TXN_MAX_PREPARED: 'txn.max.prepared',
  STORAGE_MVCC_BLOAT: 'storage.mvcc.bloat',
  BUFFER_MEMORY_PRESSURE: 'buffer.memory.pressure',
};

// 故障定义：每个故障包含 nameKey, descriptionKey, params
export const FAULT_DEFINITIONS = {
  [FAULT_TYPES.CPU_LOAD]: {
    nameKey: 'faultinject.fault.cpu.load',
    descriptionKey: 'faultinject.fault.cpu.load.desc',
    params: [
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.NETWORK_PACKET_LOSS]: {
    nameKey: 'faultinject.fault.network.package',
    descriptionKey: 'faultinject.fault.network.package.desc',
    params: [
      {
        paramName: 'percent',
        descriptionKey: 'faultinject.param.network.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '10'
      },
      {
        paramName: 'interface',
        descriptionKey: 'faultinject.param.network.interface.desc',
        type: 'string',
        required: true,
        defaultValue: 'ens17f0'
      },
      {
        paramName: 'destination-ip',
        descriptionKey: 'faultinject.param.network.destip.desc',
        type: 'string',
        required: false,
        defaultValue: ''
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.NETWORK_DELAY]: {
    nameKey: 'faultinject.fault.network.delay',
    descriptionKey: 'faultinject.fault.network.delay.desc',
    params: [
      {
        paramName: 'time',
        descriptionKey: 'faultinject.param.network.delay.time.desc',
        type: 'number',
        unit: 'ms',
        required: true,
        defaultValue: '200'
      },
      {
        paramName: 'interface',
        descriptionKey: 'faultinject.param.network.interface.desc',
        type: 'string',
        required: true,
        defaultValue: 'ens17f0'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.NETWORK_REORDER]: {
    nameKey: 'faultinject.fault.network.unorder',
    descriptionKey: 'faultinject.fault.network.unorder.desc',
    params: [
      {
        paramName: 'correlation',
        descriptionKey: 'faultinject.param.network.reorder.correlation.desc',
        type: 'number',
        required: true,
        defaultValue: '80'
      },
      {
        paramName: 'percent',
        descriptionKey: 'faultinject.param.network.reorder.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '50'
      },
      {
        paramName: 'interface',
        descriptionKey: 'faultinject.param.network.interface.desc',
        type: 'string',
        required: true,
        defaultValue: 'ens17f0'
      },
      {
        paramName: 'time',
        descriptionKey: 'faultinject.param.network.delay.time.desc',
        type: 'number',
        unit: 'ms',
        required: false,
        defaultValue: '500'
      },
      {
        paramName: 'gap',
        descriptionKey: 'faultinject.param.network.reorder.gap.desc',
        type: 'number',
        required: false,
        defaultValue: '2'
      },
      {
        paramName: 'destination-ip',
        descriptionKey: 'faultinject.param.network.destip.desc',
        type: 'string',
        required: false,
        defaultValue: ''
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },
  [FAULT_TYPES.NETWORK_PACKET_DUPLICATION]: {
    nameKey: 'faultinject.fault.network.duplicate',
    descriptionKey: 'faultinject.fault.network.duplicate.desc',
    params: [
      {
        paramName: 'interface',
        descriptionKey: 'faultinject.param.network.duplicate.interface.desc',
        type: 'string',
        required: true,
        defaultValue: 'ens17f0'
      },
      {
        paramName: 'percent',
        descriptionKey: 'faultinject.param.network.duplicate.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '50'
      },
      {
        paramName: 'destination-ip',
        descriptionKey: 'faultinject.param.network.duplicate.destip.desc',
        type: 'string',
        required: false,
        defaultValue: ''
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.NETWORK_PACKET_CORRUPT]: {
    nameKey: 'faultinject.fault.network.corrupt',
    descriptionKey: 'faultinject.fault.network.corrupt.desc',
    params: [
      {
        paramName: 'interface',
        descriptionKey: 'faultinject.param.network.corrupt.interface.desc',
        type: 'string',
        required: true,
        defaultValue: 'ens17f0'
      },
      {
        paramName: 'percent',
        // 显式 label：否则会落到 param.label.percent（「丢包率」），与包损坏语义不符
        label: { zh: '损坏比例', en: 'Corruption Percent' },
        descriptionKey: 'faultinject.param.network.corrupt.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '10'
      },
      {
        paramName: 'destination-ip',
        descriptionKey: 'faultinject.param.network.corrupt.destip.desc',
        type: 'string',
        required: false,
        defaultValue: ''
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.MEMORY_LOAD]: {
    nameKey: 'faultinject.fault.memory.load',
    descriptionKey: 'faultinject.fault.memory.load.desc',
    params: [
      {
        paramName: 'memory-percent',
        descriptionKey: 'faultinject.param.memory.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '80'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.DISK_FILE_MOVE]: {
    nameKey: 'faultinject.fault.disk.filemove',
    descriptionKey: 'faultinject.fault.disk.filemove.desc',
    params: [
      {
        paramName: 'src-path',
        descriptionKey: 'faultinject.param.disk.filemove.src.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp/source.log'
      },
      {
        paramName: 'dst-dir',
        descriptionKey: 'faultinject.param.disk.filemove.dst.dir.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp/'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.DISK_FILE_REMOVE]: {
    nameKey: 'faultinject.fault.disk.fileremoved',
    descriptionKey: 'faultinject.fault.disk.fileremoved.desc',
    params: [
      {
        paramName: 'file-path',
        descriptionKey: 'faultinject.param.disk.fileremove.path.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp/target.log'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },
  [FAULT_TYPES.DISK_FILE_APPEND]: {
    nameKey: 'faultinject.fault.disk.fileappend',
    descriptionKey: 'faultinject.fault.disk.fileappend.desc',
    params: [
      {
        paramName: 'content',
        descriptionKey: 'faultinject.param.disk.fileappend.content.desc',
        type: 'string',
        required: true,
        defaultValue: ''
      },
      {
        paramName: 'filepath',
        descriptionKey: 'faultinject.param.disk.fileappend.filepath.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp/append.log'
      },
      {
        paramName: 'count',
        descriptionKey: 'faultinject.param.disk.fileappend.count.desc',
        type: 'number',
        required: false,
        defaultValue: '1'
      },
      {
        paramName: 'escape',
        descriptionKey: 'faultinject.param.disk.fileappend.escape.desc',
        type: 'string',
        required: false,
        defaultValue: ''
      },
      {
        paramName: 'interval',
        descriptionKey: 'faultinject.param.disk.fileappend.interval.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '1'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },
  [FAULT_TYPES.DISK_FILE_CHMOD]: {
    nameKey: 'faultinject.fault.disk.filechmod',
    descriptionKey: 'faultinject.fault.disk.filechmod.desc',
    params: [
      {
        paramName: 'filepath',
        descriptionKey: 'faultinject.param.disk.filechmod.filepath.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp/target.log'
      },
      {
        paramName: 'mark',
        descriptionKey: 'faultinject.param.disk.filechmod.mark.desc',
        type: 'string',
        required: true,
        defaultValue: '777'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },
  [FAULT_TYPES.DISK_FILL]: {
    nameKey: 'faultinject.fault.disk.fill',
    descriptionKey: 'faultinject.fault.disk.fill.desc',
    params: [
      {
        paramName: 'path',
        descriptionKey: 'faultinject.param.disk.fill.path.desc',
        type: 'string',
        required: true,
        defaultValue: '/tmp'
      },
      {
        paramName: 'percent',
        descriptionKey: 'faultinject.param.disk.fill.percent.desc',
        type: 'number',
        unit: '%',
        required: true,
        defaultValue: '90'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.PROCESS_STOP]: {
    nameKey: 'faultinject.fault.process.stop',
    descriptionKey: 'faultinject.fault.process.stop.desc',
    params: [
      {
        paramName: 'process-cmd',
        descriptionKey: 'faultinject.param.process.cmd.desc',
        type: 'string',
        required: true,
        defaultValue: 'nginx'
      },
      {
        paramName: 'duration',
        descriptionKey: 'faultinject.param.duration.desc',
        type: 'number',
        unit: 's',
        required: false,
        defaultValue: '30'
      }
    ]
  },

  [FAULT_TYPES.PROCESS_KILL]: {
    nameKey: 'faultinject.fault.process.kill',
    descriptionKey: 'faultinject.fault.process.kill.desc',
    params: [
      {
        paramName: 'process-cmd',
        descriptionKey: 'faultinject.param.process.cmd.desc',
        type: 'string',
        required: true,
        defaultValue: 'nginx'
      },
      {
        paramName: 'signal',
        descriptionKey: 'faultinject.param.process.signal.desc',
        type: 'number',
        required: false,
        defaultValue: '9'
      }
    ]
  },
  [FAULT_TYPES.APPLICATION_ERROR]: {
  nameKey: 'faultinject.fault.application.error',
  descriptionKey: 'faultinject.fault.application.error.desc',
  params: [
    {
      paramName: 'target-service',
      descriptionKey: 'faultinject.param.application.target.service.desc',
      type: 'string',
      required: true,
      // defaultValue: 'gaussdb'
    },
    {
      paramName: 'error-type',
      descriptionKey: 'faultinject.param.application.error.type.desc',
      type: 'string',
      required: true,
      defaultValue: 'exception',
      options: ['exception', 'return-error', 'timeout']
    },
    {
      paramName: 'class-method',
      descriptionKey: 'faultinject.param.application.class.method.desc',
      type: 'string',
      required: true,
      defaultValue: 'com.bank.TransferService.transfer'
    },
    {
      paramName: 'http-status',
      descriptionKey: 'faultinject.param.application.http.status.desc',
      type: 'number',
      required: false,
      defaultValue: '500'
    },
    {
      paramName: 'delay-ms',
      descriptionKey: 'faultinject.param.application.delay.desc',
      type: 'number',
      unit: 'ms',
      required: false,
      defaultValue: '5000'
    },
    {
      paramName: 'probability',
      descriptionKey: 'faultinject.param.application.probability.desc',
      type: 'number',
      unit: '%',
      required: false,
      defaultValue: '100'
    },
    {
      paramName: 'duration',
      descriptionKey: 'faultinject.param.duration.desc',
      type: 'number',
      unit: 's',
      required: false,
      defaultValue: '30'
    }
  ]
},
[FAULT_TYPES.CONNECTION_LIMIT_EXCEEDED]: {
  nameKey: 'faultinject.fault.connection.limit.exceeded',
  descriptionKey: 'faultinject.fault.connection.limit.exceeded.desc',
  params: [
    { paramName: 'attempted-connections', descriptionKey: 'faultinject.param.connection.limit.attempted.desc', type: 'number', required: true, defaultValue: '400' },
    { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
  ]
},

[FAULT_TYPES.TRANSACTION_STACK_OVERFLOW]: {
  nameKey: 'faultinject.fault.transaction.stack.overflow',
  descriptionKey: 'faultinject.fault.transaction.stack.overflow.desc',
  params: [
    { paramName: 'transaction-type', descriptionKey: 'faultinject.param.transaction.type.desc', type: 'string', required: true, defaultValue: 'trans_recurse' },
    { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
  ]
},

[FAULT_TYPES.TRANSACTION_MASSIVE_ROLLBACK]: {
  nameKey: 'faultinject.fault.transaction.massive.rollback',
  descriptionKey: 'faultinject.fault.transaction.massive.rollback.desc',
  params: [
    { paramName: 'threads', descriptionKey: 'faultinject.param.transaction.rollback.threads.desc', type: 'number', required: false, defaultValue: '16' },
    { paramName: 'rollback-rate', descriptionKey: 'faultinject.param.transaction.rollback.rate.desc', type: 'number', unit: '%', required: true, defaultValue: '80' },
    { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
  ],
  },
  [FAULT_TYPES.CONNECTION_SURGE]: {
    nameKey: 'faultinject.fault.connection.surge',
    descriptionKey: 'faultinject.fault.connection.surge.desc',
    params: [
      { paramName: 'concurrent-connections', descriptionKey: 'faultinject.param.connection.limit.attempted.desc', type: 'number', required: true, defaultValue: '200' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.CONNECTION_THREAD_SATURATION]: {
    nameKey: 'faultinject.fault.connection.thread_saturation',
    descriptionKey: 'faultinject.fault.connection.thread_saturation.desc',
    params: [
      { paramName: 'concurrent-connections', descriptionKey: 'faultinject.param.connection.limit.attempted.desc', type: 'number', required: true, defaultValue: '32' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.SQL_PLAN_FLIP]: {
    nameKey: 'faultinject.fault.sql.plan_flip',
    descriptionKey: 'faultinject.fault.sql.plan_flip.desc',
    params: [
      { paramName: 'threads', descriptionKey: 'faultinject.param.threads.desc', type: 'number', required: false, defaultValue: '16' },
      { paramName: 'count', descriptionKey: 'faultinject.param.count.desc', type: 'number', required: false, defaultValue: '1000000' },
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '60000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
  [FAULT_TYPES.SQL_STACK_OVERFLOW_SQL_DEPTH]: {
    nameKey: 'faultinject.fault.sql.stack_overflow.sql_depth',
    descriptionKey: 'faultinject.fault.sql.stack_overflow.sql_depth.desc',
    params: [
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '1000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.SQL_STACK_OVERFLOW_VIEW_NEST]: {
    nameKey: 'faultinject.fault.sql.stack_overflow.view_nest',
    descriptionKey: 'faultinject.fault.sql.stack_overflow.view_nest.desc',
    params: [
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '1000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.SQL_STACK_OVERFLOW_JOIN_BOMB]: {
    nameKey: 'faultinject.fault.sql.stack_overflow.join_bomb',
    descriptionKey: 'faultinject.fault.sql.stack_overflow.join_bomb.desc',
    params: [
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '1000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.EXEC_STACK_OVERFLOW_FUNC_RECURSE]: {
    nameKey: 'faultinject.fault.exec.stack_overflow.func_recurse',
    descriptionKey: 'faultinject.fault.exec.stack_overflow.func_recurse.desc',
    params: [
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '1000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.EXEC_STACK_OVERFLOW_PROC_RECURSE]: {
    nameKey: 'faultinject.fault.exec.stack_overflow.proc_recurse',
    descriptionKey: 'faultinject.fault.exec.stack_overflow.proc_recurse.desc',
    params: [
      { paramName: 'interval', descriptionKey: 'faultinject.param.interval.desc', type: 'number', unit: 'ms', required: false, defaultValue: '1000' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '30' }
    ]
  },
  [FAULT_TYPES.EXEC_READ_AMP_TRAP]: {
    nameKey: 'faultinject.fault.exec.read_amp_trap',
    descriptionKey: 'faultinject.fault.exec.read_amp_trap.desc',
    params: [
      { paramName: 'warmup', descriptionKey: 'faultinject.param.warmup.desc', type: 'number', unit: 'ms', required: false, defaultValue: '30000' },
      { paramName: 'mutators', descriptionKey: 'faultinject.param.mutators.desc', type: 'number', required: false, defaultValue: '8' },
      { paramName: 'scanners', descriptionKey: 'faultinject.param.scanners.desc', type: 'number', required: false, defaultValue: '4' },
      { paramName: 'scan-mode', descriptionKey: 'faultinject.param.scan.mode.desc', type: 'string', required: false, defaultValue: 'mixed' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '120' }
    ]
  },
  [FAULT_TYPES.TXN_UNCOMMITTED]: {
    nameKey: 'faultinject.fault.txn.uncommitted_txn',
    descriptionKey: 'faultinject.fault.txn.uncommitted_txn.desc',
    params: [
      { paramName: 'table', descriptionKey: 'faultinject.param.table.desc', type: 'string', required: true, defaultValue: 'bmsql_stock' },
      { paramName: 'holders', descriptionKey: 'faultinject.param.holders.desc', type: 'number', required: false, defaultValue: '2' },
      { paramName: 'rows', descriptionKey: 'faultinject.param.rows.desc', type: 'number', required: false, defaultValue: '500' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
  [FAULT_TYPES.TXN_DUPLICATE]: {
    nameKey: 'faultinject.fault.txn.duplicate_txn',
    descriptionKey: 'faultinject.fault.txn.duplicate_txn.desc',
    params: [
      { paramName: 'sessions', descriptionKey: 'faultinject.param.sessions.desc', type: 'number', required: false, defaultValue: '50' },
      { paramName: 'mode', descriptionKey: 'faultinject.param.mode.desc', type: 'string', required: false, defaultValue: 'UPDATE' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
  [FAULT_TYPES.TXN_DEADLOCK_STORM]: {
    nameKey: 'faultinject.fault.txn.deadlock_storm',
    descriptionKey: 'faultinject.fault.txn.deadlock_storm.desc',
    params: [
      { paramName: 'table', descriptionKey: 'faultinject.param.table.desc', type: 'string', required: false, defaultValue: 'bmsql_stock' },
      { paramName: 'anchors', descriptionKey: 'faultinject.param.anchors.desc', type: 'number', required: false, defaultValue: '2' },
      { paramName: 'waiters', descriptionKey: 'faultinject.param.waiters.desc', type: 'number', required: false, defaultValue: '16' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
  [FAULT_TYPES.TXN_MAX_PREPARED]: {
    nameKey: 'faultinject.fault.txn.max_prepared',
    descriptionKey: 'faultinject.fault.txn.max_prepared.desc',
    params: [
      { paramName: 'count', descriptionKey: 'faultinject.param.count.desc', type: 'number', required: false, defaultValue: '201' },
      { paramName: 'concurrency', descriptionKey: 'faultinject.param.concurrency.desc', type: 'number', required: false, defaultValue: '50' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
  [FAULT_TYPES.STORAGE_MVCC_BLOAT]: {
    nameKey: 'faultinject.fault.storage.mvcc_bloat',
    descriptionKey: 'faultinject.fault.storage.mvcc_bloat.desc',
    params: [
      { paramName: 'anchors', descriptionKey: 'faultinject.param.anchors.desc', type: 'number', required: false, defaultValue: '1' },
      { paramName: 'mutators', descriptionKey: 'faultinject.param.mutators.desc', type: 'number', required: false, defaultValue: '8' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '120' }
    ]
  },
  [FAULT_TYPES.BUFFER_MEMORY_PRESSURE]: {
    nameKey: 'faultinject.fault.buffer.memory_pressure',
    descriptionKey: 'faultinject.fault.buffer.memory_pressure.desc',
    params: [
      { paramName: 'batch', descriptionKey: 'faultinject.param.batch.desc', type: 'number', unit: 'MB', required: false, defaultValue: '100' },
      { paramName: 'threads', descriptionKey: 'faultinject.param.threads.desc', type: 'number', required: false, defaultValue: '4' },
      { paramName: 'duration', descriptionKey: 'faultinject.param.duration.desc', type: 'number', unit: 's', required: false, defaultValue: '60' }
    ]
  },
};
export const FAULT_CATEGORIES = {
  CPU: [FAULT_TYPES.CPU_LOAD],
  network: [
    FAULT_TYPES.NETWORK_PACKET_LOSS,
    FAULT_TYPES.NETWORK_DELAY,
    FAULT_TYPES.NETWORK_REORDER,
    FAULT_TYPES.NETWORK_PACKET_DUPLICATION,
    FAULT_TYPES.NETWORK_PACKET_CORRUPT
  ],
  memory: [FAULT_TYPES.MEMORY_LOAD],
  IO: [
    FAULT_TYPES.DISK_FILE_MOVE,
    FAULT_TYPES.DISK_FILE_REMOVE,
    FAULT_TYPES.DISK_FILE_APPEND,
    FAULT_TYPES.DISK_FILE_CHMOD,
    FAULT_TYPES.DISK_FILL // 新增
  ],
  process: [ // 新增分类
    FAULT_TYPES.PROCESS_STOP,
    FAULT_TYPES.PROCESS_KILL
  ],
  // application:[FAULT_TYPES.APPLICATION_ERROR],
  database: [
    FAULT_TYPES.CONNECTION_SURGE,
    FAULT_TYPES.CONNECTION_LIMIT_EXCEEDED,
    FAULT_TYPES.CONNECTION_THREAD_SATURATION,
    FAULT_TYPES.TRANSACTION_STACK_OVERFLOW,
    FAULT_TYPES.TRANSACTION_MASSIVE_ROLLBACK,
    FAULT_TYPES.SQL_PLAN_FLIP,
    FAULT_TYPES.SQL_STACK_OVERFLOW_SQL_DEPTH,
    FAULT_TYPES.SQL_STACK_OVERFLOW_VIEW_NEST,
    FAULT_TYPES.SQL_STACK_OVERFLOW_JOIN_BOMB,
    FAULT_TYPES.EXEC_STACK_OVERFLOW_FUNC_RECURSE,
    FAULT_TYPES.EXEC_STACK_OVERFLOW_PROC_RECURSE,
    FAULT_TYPES.EXEC_READ_AMP_TRAP,
    FAULT_TYPES.TXN_UNCOMMITTED,
    FAULT_TYPES.TXN_DUPLICATE,
    FAULT_TYPES.TXN_DEADLOCK_STORM,
    FAULT_TYPES.TXN_MAX_PREPARED,
    FAULT_TYPES.STORAGE_MVCC_BLOAT,
    FAULT_TYPES.BUFFER_MEMORY_PRESSURE,
  ],

};
// // 可选：按大类分组，用于左侧故障类型选择器
// export const FAULT_CATEGORIES = {
//   CPU: [FAULT_TYPES.CPU_LOAD],
//   network: [FAULT_TYPES.NETWORK_PACKET_LOSS, FAULT_TYPES.NETWORK_DELAY, FAULT_TYPES.NETWORK_REORDER,FAULT_TYPES.NETWORK_PACKET_DUPLICATION],
//   memory: [FAULT_TYPES.MEMORY_LOAD],
//   IO: [FAULT_TYPES.DISK_FILE_MOVE, FAULT_TYPES.DISK_FILE_REMOVE,FAULT_TYPES.DISK_FILE_APPEND ,FAULT_TYPES.DISK_FILE_CHMOD ]
// };
// 从 nameKey 映射到 type
export const NAME_KEY_TO_TYPE = {};
for (const [type, def] of Object.entries(FAULT_DEFINITIONS)) {
  NAME_KEY_TO_TYPE[def.nameKey] = type;
}

/**
 * 从后端目录（GET /suite/faults/catalog，yaml 驱动）同步故障定义（设计 v2.1 §5.4.1）。
 *
 * 目录请求成功即视为权威（yaml 决定 database 等目录分类的故障列表）：
 *  - 目录已有的故障：原地更新 name/descriptionKey/params/hasCheck（老 i18n 词典优先，缺失回退文本）
 *  - 目录新增的故障：动态创建 FAULT_DEFINITIONS + FAULT_CATEGORIES 分组条目，前端无需改代码即可出现
 *  - 目录出现的分类：整表替换为该分类在 yaml 中的故障（替换而非追加）；目录无 database 条目时清空 database
 *  - 后端不可用/未升级：静默保留内置列表，功能与旧版一致
 */
export async function hydrateFaultCatalog() {
  let items = [];
  try {
    const { fetchFaultCatalog } = await import("../api/suiteApi");
    items = await fetchFaultCatalog();
  } catch (e) {
    console.warn("[faultCatalog] catalog fetch failed, keeping built-in fault list:", e?.message);
    return;
  }
  if (!Array.isArray(items)) return;

  // 收集目录中各分类的故障 nameKey（保持 yaml 顺序）
  const catalogByCategory = new Map();
  for (const item of items) {
    const type = item.nameKey;
    if (!type) continue;
    const category = item.category || "database";
    if (!catalogByCategory.has(category)) {
      catalogByCategory.set(category, []);
    }
    if (!catalogByCategory.get(category).includes(type)) {
      catalogByCategory.get(category).push(type);
    }

    const existing = FAULT_DEFINITIONS[type];
    const catalogParams = Array.isArray(item.params)
      ? item.params.map((p) => ({
          paramName: p.paramName,
          descriptionKey: p.description || "", // yaml 未写 description 则为空 → 渲染时什么都不显示（不再回退 label，避免把双语 JSON 串当词典 key）
          description: p.description || "", // v2.2 多语言：原样保留（JSON 对象串/文本），渲染时 yaml 优先
          label: p.label || p.paramName,    // v2.2 多语言：原样保留（JSON 对象串/文本），渲染时 yaml 优先
          type: p.type || "string",
          unit: p.unit || "",
          required: !!p.required,
          defaultValue: String(p.defaultValue ?? ""),
        }))
      : [];

    if (existing) {
      // 只补齐目录信息，不覆盖既有人工文案键
      if (!existing.name) existing.name = item.name;
      existing.name = existing.name || item.name;
      existing.descriptionKey = existing.descriptionKey || item.description || "";
      existing.category = category;
      existing.hasCheck = !!item.hasCheck;
      if (existing.params && existing.params.length > 0) {
        // 目录参数仅作为 label 兜底补充
        const byName = {};
        catalogParams.forEach((p) => { byName[p.paramName] = p; });
        existing.params = existing.params.map((p) => ({ ...byName[p.paramName], ...p, label: byName[p.paramName]?.label || p.label }));
        const known = new Set(existing.params.map((p) => p.paramName));
        existing.params = existing.params.concat(catalogParams.filter((p) => !known.has(p.paramName)));
      } else {
        existing.params = catalogParams;
      }
    } else {
      FAULT_DEFINITIONS[type] = {
        nameKey: type,
        name: item.name || type,
        descriptionKey: item.description || item.name || type,
        category,
        hasCheck: !!item.hasCheck,
        params: catalogParams,
      };
    }
  }

  // 目录驱动的分类：整表替换（database 及 yaml 出现的其它分类），替换而非追加
  for (const [category, nameKeys] of catalogByCategory) {
    if (!Array.isArray(FAULT_CATEGORIES[category])) {
      FAULT_CATEGORIES[category] = [];
    }
    FAULT_CATEGORIES[category].splice(0, FAULT_CATEGORIES[category].length, ...nameKeys);
  }
  // 目录为空 / 未包含 database 分类（如全注释）→ 清空 database，DB 故障列表完全由 yaml 决定
  if (!catalogByCategory.has("database")) {
    FAULT_CATEGORIES.database = [];
  }

  // 重建反向映射
  for (const key of Object.keys(NAME_KEY_TO_TYPE)) {
    delete NAME_KEY_TO_TYPE[key];
  }
  for (const [type, def] of Object.entries(FAULT_DEFINITIONS)) {
    if (def.nameKey) NAME_KEY_TO_TYPE[def.nameKey] = type;
  }
}

/**
 * v2.2 多语言取值（yaml {zh,en} 对象或 JSON 串或普通文本）：
 * 传入当前语言 lang（'zh'/'en'），优先取 yaml 指定语言，缺省 zh → en → 原文本。
 */
export function pickI18n(value, lang) {
  if (value == null) return "";
  if (typeof value === "object") return value[lang] || value.zh || value.en || "";
  const text = String(value).trim();
  if (text.startsWith("{")) {
    try {
      const obj = JSON.parse(text);
      if (obj && typeof obj === "object") {
        return obj[lang] || obj.zh || obj.en || text;
      }
    } catch {
      /* 非 JSON，按普通文本处理 */
    }
  }
  return text;
}

/**
 * 故障展示名解析（v2.2：yaml 优先，词典兜底）：
 * yaml 显式 name（含 {zh,en} 多语言）→ 按当前语言显示；
 * yaml 未写 name（回退为 nameKey）→ 词典命中用译文，否则回退 yaml name 文本。
 * 用法：displayFaultName(def, translate, lang)
 */
export function displayFaultName(def, translate, lang) {
  if (!def) return "";
  const yamlText = def.nameKey ? pickI18n(def.name, lang) : "";
  if (yamlText && yamlText !== def.nameKey) return yamlText; // yaml 显式 name 优先
  if (!def.nameKey) return def.name || "";
  const translated = translate(def.nameKey);
  return translated !== def.nameKey ? translated : (def.name || def.nameKey);
}