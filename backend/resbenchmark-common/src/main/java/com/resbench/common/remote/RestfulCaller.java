package com.resbench.common.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import com.resbench.common.remote.exception.RemoteAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestfulCaller {
    private static final Logger logger = LoggerFactory.getLogger(RestfulCaller.class);
    String hostPort = null;

    public RestfulCaller(String hostPort) {
        this.hostPort = hostPort;
    }

    // get方式请求
    public String restCallerGet(String path, String param) {
        // path 接口路径 xxx/xxx/xxx
        // param 入参 ?xxx=x&xxx=x&xxx=x

        String data = "";

        // url拼接
        String lasturl = this.hostPort + path + param;
        try {
            URL url = java.net.URI.create(lasturl).toURL();
            // 打开和url之间的连接
            HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();

            // 请求头
            urlConn.setRequestProperty("Accept-Charset", "utf-8");
            urlConn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            urlConn.setDoOutput(true);
            urlConn.setDoInput(true);
            urlConn.setRequestMethod("GET"); // GET和POST必须全大写
            urlConn.connect();

            int code = urlConn.getResponseCode(); // 获得响应码
            if (code == 200) { // 响应成功，获得响应的数据
                // InputStream is = urlConn.getInputStream();//得到数据流（输入流）
                // byte[] buffer = new byte[1024];
                // int length = 0;
                // while ((length = is.read(buffer)) != -1) {
                //  String res = new String(buffer, 0, length);
                //  data += res;
                // }
                // System.out.println(data);

                // 解决中文乱码
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(urlConn.getInputStream(), "UTF-8"));
                // data = reader.readLine();

                // 解决返回参数换行问题，一次读一行，读入null时文件结束
                int line = 1;
                String tempString = null;
                while ((tempString = reader.readLine()) != null) {
                    data += tempString;
                    // 把当前行号显示出来
                    // System.out.println("line " + line + ": " + tempString);
                    line++;
                }
                reader.close();
            }
            urlConn.disconnect(); // 断开连接

        } catch (Exception e) {
            e.printStackTrace();
        }

        return data;
    }

    // post方式请求
    public String restCallerPost(String path, String param) throws RemoteAccessException {
        return restCallerPostWithTimeout(path, param, 0);
    }

    /**
     * 一次 POST 调用的结构化结果：把**有没有收到回复**和回复内容分开记录。
     *
     * <p>故障注入的判定标准是「请求发出去了，并且在合理时间内收到了 agent 的回复」，
     * 靠字符串内容反推（比如把 {@code "false"} 当失败）分不清「没收到回复」和
     * 「收到了但服务端返回非 200」——这两件事的处置完全不同。
     */
    public static final class HttpResult {
        /** 是否收到了 HTTP 响应（连不上 / 超时 / 请求写入卡死 → false） */
        public final boolean replied;

        /** HTTP 状态码；没收到响应时为 0 */
        public final int status;

        /** 响应体；非 200 时为字面量 {@code "false"}（与 {@link #restCallerPostWithTimeout} 一致） */
        public final String body;

        /** 从提交请求到拿到响应的耗时（ms） */
        public final long elapsedMs;

        /** 传输层错误描述；正常时为 null */
        public final String error;

        /** 传输层异常原文，供 {@link #restCallerPostWithTimeout} 原样抛出；正常时为 null */
        final Throwable cause;

        /**
         * 本次调用实际走的传输：{@code "http"}（本类，每次新建 TCP 连接）或
         * {@code "ws"}（{@code FaultInjector} 的常驻命令通道）。
         *
         * <p>记这一笔是为了区分「恢复为什么没成功」的两种截然不同的原因：走 HTTP 的那次
         * 失败在新建连接，走通道的那次失败在别处。以前两者在归档里长得一模一样。
         */
        public final String transport;

        /** 走常驻通道时，发送那一刻通道是否处于 OPEN；走 HTTP 时为 null */
        public final Boolean channelAlive;

        public HttpResult(
                boolean replied, int status, String body, long elapsedMs, String error, Throwable cause) {
            this(replied, status, body, elapsedMs, error, cause, "http", null);
        }

        public HttpResult(
                boolean replied,
                int status,
                String body,
                long elapsedMs,
                String error,
                Throwable cause,
                String transport,
                Boolean channelAlive) {
            this.replied = replied;
            this.status = status;
            this.body = body;
            this.elapsedMs = elapsedMs;
            this.error = error;
            this.cause = cause;
            this.transport = transport;
            this.channelAlive = channelAlive;
        }

        @Override
        public String toString() {
            return "HttpResult{replied=" + replied + ", status=" + status + ", elapsedMs=" + elapsedMs
                    + ", error=" + error + ", body=" + body + ", transport=" + transport
                    + ", channelAlive=" + channelAlive + "}";
        }
    }

    // post方式请求，带超时设置
    public String restCallerPostWithTimeout(String path, String param, int timeoutMs) throws RemoteAccessException {
        HttpResult result = restCallerPostWithTimeoutResult(path, param, timeoutMs);
        if (result.cause != null) {
            throw new RemoteAccessException(
                    String.format("Access request failed. path %s, param %s", path, param), result.cause);
        }
        return result.body;
    }

    /**
     * 带超时的 POST，返回结构化结果 —— 与 {@link #restCallerPostWithTimeout} 的唯一区别是
     * <b>不抛异常</b>，把「有没有收到回复」和耗时一并交回调用方。
     *
     * <p>返回体、非 200 时返回字面量 {@code "false"} 等行为与旧方法逐字一致，
     * 所以 {@code FaultVerifier.postCheck} 那条路径不受影响。
     */
    public HttpResult restCallerPostWithTimeoutResult(String path, String param, int timeoutMs) {
        long start = System.currentTimeMillis();
        int responseCode = 0;
        boolean replied = false;
        String data = "";

        // url拼接
        String lasturl = this.hostPort + path;
        try {
            URL restURL = java.net.URI.create(lasturl).toURL();
            HttpURLConnection conn = (HttpURLConnection) restURL.openConnection();
            conn.setRequestMethod("POST");
            // 请求头
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);

            // 设置超时。注意：connect/read 超时盖不住「请求写入卡死」那种情况
            // （HttpURLConnection 没有写超时），所以调用方还要有一层真正的卡死线。
            if (timeoutMs > 0) {
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
            }

            // 输入流
            // OutputStream os = conn.getOutputStream();
            // 解决中文乱码
            OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
            os.write(param);
            os.flush();
            // 输出response code
            responseCode = conn.getResponseCode();
            // 走到这里说明服务端确实应答了
            replied = true;
            // 输出response
            if (responseCode == 200) {
                // 输出流
                // 解决中文乱码
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));

                // 解决返回参数换行问题，一次读一行，读入null时文件结束
                String tempString = null;
                while ((tempString = reader.readLine()) != null) {
                    data += tempString;
                }
                reader.close();

            } else {
                data = "false";
            }
            // 断开连接
            os.close();
            conn.disconnect();
        } catch (Exception e) {
            logger.error("Access request failed. path {}, param {}, err {}", path, param, e.getMessage());
            return new HttpResult(
                    replied, responseCode, data, System.currentTimeMillis() - start, e.getMessage(), e);
        }
        return new HttpResult(true, responseCode, data, System.currentTimeMillis() - start, null, null);
    }

    public static void main(String[] args) {
        // 接口路径
        String pathGet = "/data/123";
        String pathPost = "/xxx/xxx/postFunction";

        System.out.println(pathGet);

        // get
        String paramGet = "?aaa=1&bbb=2";
        // RestCallerUtil为自行封装的工具类
        RestfulCaller rcuGet = new RestfulCaller("http://127.0.0.1:8000");
        String resultDataGet = rcuGet.restCallerGet(pathGet, paramGet);
        System.out.println(resultDataGet);

        //    //post
        //    String paramPost = "{'aaa':'1','bbb':'2'}";
        //    //RestCallerUtil为自行封装的工具类
        //    RestfulCaller rcuPost = new RestfulCaller("http://127.0.0.1:8000/");
        //    String resultDataPost = rcuPost.restCallerPost(pathPost, paramPost);
        //    System.out.println(resultDataPost);

    }

    public String getHostPort() {
        return hostPort;
    }
}
