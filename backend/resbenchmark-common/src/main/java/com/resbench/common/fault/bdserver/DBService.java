package com.resbench.common.fault.bdserver;

import com.resbench.common.fault.bdserver.gauss.GaussDBService;
import com.resbench.common.fault.bdserver.mysql.MySQLDBService;
import com.resbench.common.fault.bdserver.oceanbase.OceanBaseDBService;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public abstract class DBService {
    protected final List<String> hosts;

    protected final String user;

    protected final String password;

    protected final String driverClass;

    protected DBService(String user, String password, List<String> hosts, String driverClass) {
        this.hosts = new ArrayList<>();
        this.user = user;
        this.password = password;
        this.hosts.addAll(hosts);
        this.driverClass = driverClass;
    }

    public abstract List<String> getClusteringMasters();

    public abstract List<String> getClusteringSlaves();

    public List<String> getHosts() {
        return hosts;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public List<String> getAllClusteringMems() {
        List<String> allNodes = new ArrayList<>();
        for (String node : hosts) {
            try {
                InetAddress address = InetAddress.getByName(node);
                allNodes.add(address.getHostAddress());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        return allNodes;
    }

    public static DBService createDBService(String type, String user, String password, List<String> hosts, String driverClass) {
        DBService service;
        switch (type) {
            case "MYSQL": {
                service = new MySQLDBService(user, password, hosts, driverClass);
                break;
            }
            case "OPENGAUSS":
            case "GAUSSDB": {
                service = new GaussDBService(user, password, hosts, driverClass);
                break;
            }
            case "OCEANBASE": {
                service = new OceanBaseDBService(user, password, hosts, driverClass);
                break;
            }
            default:
                service = new DBService(user, password, hosts, driverClass) {
                    @Override
                    public List<String> getClusteringMasters() {
                        ArrayList<String> list = new ArrayList<>();
                        list.add("localhost");
                        return list;
                    }

                    @Override
                    public List<String> getClusteringSlaves() {
                        ArrayList<String> list = new ArrayList<>();
                        list.add("localhost");
                        return list;
                    }
                };
        }
        return service;
    }
}
