package com.resbench.common.fault;

import com.resbench.common.fault.bdserver.DBService;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 故障服务实现类，负责故障的定义和注入
 */
public class FaultManager {
    public static final ArrayList<FaultManager.FaultInfo> FAULT_INFOS = new ArrayList<>();
    private static final Logger LOG = LoggerFactory.getLogger(FaultManager.class);

    private final Path basePath;
    private TestSuite testSuite;
    private AtomicBoolean started = new AtomicBoolean(false);
    private FaultInjector.FaultRunner faultRunner;
    private Memento memento = new Memento();

    public FaultManager(Path basePath) {
        this.basePath = basePath;
    }

    public void startTestSuite(TestSuite testSuite, DBService service) {
        startTestSuite(testSuite, service, null);
    }

    public void startTestSuite(TestSuite testSuite, DBService service, String historyName) {
        this.testSuite = testSuite;
        if (this.testSuite == null) {
            throw new IllegalStateException("TestSuite is not initialized");
        }
        if (started.compareAndSet(false, true)) {
            LOG.info("Starting test suite: {}", testSuite.getName());
            // 创建并启动故障运行器
            this.faultRunner = new FaultInjector.FaultRunner(this, basePath, testSuite, service, historyName);
            Thread faultThread = new Thread(faultRunner);
            faultThread.setName("FaultRunner");
            faultThread.start();
        } else {
            LOG.info("FaultInjector thread for {} already started", testSuite.getName());
        }
    }

    public static void addFaultInfo(FaultInfo faultInfo) {
        FAULT_INFOS.add(faultInfo);
    }

    public TestSuite buildTestSuite(XMLConfiguration workLoadConfig, XMLConfiguration faultConfig) {
        this.testSuite = new TestSuite();
        int size = workLoadConfig.configurationsAt("/works/work").size();
        if (size > 0) {
            List<Fault> faults = Fault.loadFaultFromXML(workLoadConfig.getDocument());
            HierarchicalConfiguration<ImmutableNode> work = workLoadConfig.configurationAt("works/work[" + 1 + "]");

            String testSuitName = work.getString("testSuite");
            List<HierarchicalConfiguration<ImmutableNode>> suites = faultConfig.configurationsAt("testSuite");

            for (HierarchicalConfiguration<ImmutableNode> suite : suites) {
                String name = suite.getString("@name");
                if (testSuitName.equals(name)) {
                    testSuite.setName(name);
                    List<HierarchicalConfiguration<ImmutableNode>> cases = suite.configurationsAt("case");
                    System.out.printf(String.valueOf(cases.size()));
                    for (HierarchicalConfiguration<ImmutableNode> c : cases) {
                        String id = c.getString("@id");
                        Fault fault =
                                faults.stream().filter(x -> x.ID == Integer.parseInt(id)).findFirst().orElse(null);
                        if (fault == null) {
                            throw new RuntimeException("No fault found for id " + id);
                        }
                        Case faultCase = new Case(c, fault);
                        testSuite.addWork(faultCase);
                    }
                }
            }
        }
        return testSuite;
    }

    public TestSuite getTestSuite() {
        return testSuite;
    }

    public void stop() {
        started.set(false);
        if (faultRunner != null) {
            // 目前FaultRunner没有提供停止方法，后续可以考虑添加
            faultRunner.close();
            Set<TimerTask> recoveredTask = memento.getRecoveredTask();
            // 恢复已注入故障
            recoveredTask.forEach(TimerTask::run);
            LOG.info("FaultService stopped");
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    public static final class FaultInfo {
        private final String name;
        private final long startMillSeconds;
        private final long durationMillSeconds;

        public FaultInfo(String name, long startMillSeconds, long durationMillSeconds) {
            this.name = name;
            this.startMillSeconds = startMillSeconds;
            this.durationMillSeconds = durationMillSeconds;
        }

        public String getName() {
            return name;
        }

        public long getStartMillSeconds() {
            return startMillSeconds;
        }

        public long getDurationMillSeconds() {
            return durationMillSeconds;
        }
    }

    public Memento getMemento() {
        return memento;
    }

    public void setMemento(Memento memento) {
        this.memento = memento;
    }
}
