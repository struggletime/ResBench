package com.resbench.common.fault.bdserver.gauss;

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

public class GaussDBService extends DBService {

  public GaussDBService(String username, String password, List<String> hosts, String driverClass) {
      super(username, password, hosts, driverClass);

  }

  @Override
  public List<String> getClusteringMasters() {
    List<String> clusteringMasters = new ArrayList<String>();
    for (String nodeHost : hosts) {
      try (Connection connection =
          DriverManager.getConnection(nodeHost, user, password)) {
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        statement.execute("SELECT * FROM pg_is_in_recovery()");
        ResultSet rs = statement.getResultSet();
        String re = "";
        while (rs.next()) {
          re = rs.getString(1);
        }
        if (re.equals("f")) {
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
    List<String> clusteringSlaves = new ArrayList<String>();
    for (String nodeHost : hosts) {
      try (Connection connection =
          DriverManager.getConnection(nodeHost, user, password)) {
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        statement.execute("SELECT * FROM pg_is_in_recovery()");
        ResultSet rs = statement.getResultSet();
        String re = "";
        while (rs.next()) {
          re = rs.getString(1);
        }
        if (re.equals("t")) {
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
