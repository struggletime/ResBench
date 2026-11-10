package com.resbench.common.fault;

import java.util.HashSet;
import java.util.Set;
import java.util.TimerTask;

public class Memento {
    // 当前注入的故障
    private Set<TimerTask> recoverTask = new HashSet<>();

    public Memento() {
    }

    public void addRecoverTask(TimerTask task) {
        recoverTask.add(task);
    }

    public void removeRecoverTask(TimerTask task) {
        recoverTask.remove(task);
    }

    public Set<TimerTask> getRecoveredTask() {
        return recoverTask;
    }
}
