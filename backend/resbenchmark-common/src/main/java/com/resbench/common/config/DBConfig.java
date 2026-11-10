package com.resbench.common.config;


import java.util.ArrayList;
import java.util.List;

public class DBConfig {
    public String type;
    public String driverClass;
    public String url;
    public String username;
    public String password;
    public int randomSeed;
    public int batchSize;
    public int maxRetries;
    public boolean newConnectionPerTxn;
    public boolean reconnectOnConnectionFailure;
    public int userGroupNum;
    public int terminals; // 外部获取
    public int loaderThreads;
    public String isolationMode; // 外部获取
    public double scaleFactor;
    public String dataCheckType;
    public String dataDir;
    public String ddlPath;
    public double selectivity;
    public List<String> nodes = new ArrayList<>();

    public DBConfig() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = driverClass;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(int randomSeed) {
        this.randomSeed = randomSeed;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isNewConnectionPerTxn() {
        return newConnectionPerTxn;
    }

    public void setNewConnectionPerTxn(boolean newConnectionPerTxn) {
        this.newConnectionPerTxn = newConnectionPerTxn;
    }

    public boolean getReconnectOnConnectionFailure() {
        return reconnectOnConnectionFailure;
    }

    public void setReconnectOnConnectionFailure(boolean reconnectOnConnectionFailure) {
        this.reconnectOnConnectionFailure = reconnectOnConnectionFailure;
    }

    public int getUserGroupNum() {
        return userGroupNum;
    }

    public void setUserGroupNum(int userGroupNum) {
        this.userGroupNum = userGroupNum;
    }

    public int getTerminals() {
        return terminals;
    }

    public void setTerminals(int terminals) {
        this.terminals = terminals;
    }

    public int getLoaderThreads() {
        return loaderThreads;
    }

    public void setLoaderThreads(int loaderThreads) {
        this.loaderThreads = loaderThreads;
    }

    public String getIsolationMode() {
        return isolationMode;
    }

    public void setIsolationMode(String isolationMode) {
        this.isolationMode = isolationMode;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }

    public void setScaleFactor(double scaleFactor) {
        this.scaleFactor = scaleFactor;
    }

    public String getDataCheckType() {
        return dataCheckType;
    }

    public void setDataCheckType(String dataCheckType) {
        this.dataCheckType = dataCheckType;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public double getSelectivity() {
        return selectivity;
    }

    public void setSelectivity(double selectivity) {
        this.selectivity = selectivity;
    }

    public List<String> getNodes() {
        return nodes;
    }

    public void setNodes(List<String> nodes) {
        this.nodes = nodes;
    }

    public void addNode(String node) {
        this.nodes.add(node);
    }

    public String getDdlPath() {
        return ddlPath;
    }

    public void setDdlPath(String ddlPath) {
        this.ddlPath = ddlPath;
    }

    public boolean isReconnectOnConnectionFailure() {
        return reconnectOnConnectionFailure;
    }
}
