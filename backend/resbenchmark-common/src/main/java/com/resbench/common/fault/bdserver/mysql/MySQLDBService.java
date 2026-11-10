package com.resbench.common.fault.bdserver.mysql;

import com.resbench.common.fault.bdserver.DBService;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class MySQLDBService extends DBService {

  public MySQLDBService(String username, String password, List<String> hosts, String driverClass) {
    super(username, password, hosts, driverClass);
  }

  @Override
  public List<String> getClusteringMasters() {
    List<String> clusteringMasters = new ArrayList<>();
    for (String nodeHost : hosts) {
      try (Connection connection =
             DriverManager.getConnection(nodeHost, user, password)) {
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        statement.execute("SHOW VARIABLES LIKE 'read_only';");
        ResultSet rs = statement.getResultSet();
        String re = "";
        while (rs.next()) {
          re = rs.getString("Value");
        }
        if ("off".equalsIgnoreCase(re)) {
          String jdbcUri = connection.getMetaData().getURL();
          String uriString = jdbcUri.replaceFirst("^jdbc:", "");
          URI uri = new URI(uriString);
          String host = uri.getHost();
          InetAddress address = InetAddress.getByName(host);
          String ip = address.getHostAddress();
          clusteringMasters.add(ip);
          connection.commit();
        }
      } catch (SQLException | UnknownHostException | URISyntaxException e) {
        e.printStackTrace();
      }
    }
    return clusteringMasters;
  }

  @Override
  public List<String> getClusteringSlaves() {
    List<String> clusteringSlaves = new ArrayList<>();
    for (String nodeHost : hosts) {
      try (Connection connection =
             DriverManager.getConnection(nodeHost, user, password)) {
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        statement.execute("SHOW VARIABLES LIKE 'read_only';");
        ResultSet rs = statement.getResultSet();
        String re = "";
        while (rs.next()) {
          re = rs.getString("Value");
        }
        if ("on".equalsIgnoreCase(re)) {
          String jdbcUri = connection.getMetaData().getURL();
          String uriString = jdbcUri.replaceFirst("^jdbc:", "");
          URI uri = new URI(uriString);
          String host = uri.getHost();
          InetAddress address = InetAddress.getByName(host);
          String ip = address.getHostAddress();
          clusteringSlaves.add(ip);
          connection.commit();
        }
      } catch (SQLException | UnknownHostException | URISyntaxException e) {
        e.printStackTrace();
      }
    }
    return clusteringSlaves;
  }
}
