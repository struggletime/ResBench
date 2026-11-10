package com.resbench.remote;

import java.io.IOException;
import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;
import javax.websocket.*;

@ClientEndpoint
public class WebMonitorInfo {
  private Session session;

  public static void main(String[] args) {
    WebMonitorInfo client = new WebMonitorInfo();
    client.connect("ws://localhost:8000/ws_java_client");

    Timer timer = new Timer();
    timer.schedule(
        new TimerTask() {
          @Override
          public void run() {
            try {
              client.sendMsg("log from Java client: " + System.nanoTime());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        },
        0,
        1000); // 每秒生成一个日志

    try {
      Thread.sleep(1000 * 20);
      timer.cancel();
      client.session.close();
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  @OnMessage
  public void onMessage(String message) {
    System.out.println("Received message: " + message);
    // 处理接收到的消息
  }

  public void sendMsg(String msg) throws IOException {
    this.session.getBasicRemote().sendText(msg);
  }

  public void connect(String url) {
    WebSocketContainer container = ContainerProvider.getWebSocketContainer();
    try {
      this.session = container.connectToServer(this, new URI(url));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void close() {
    try {
      System.out.println("To close the session ...");
      session.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
