package com.resbench.common.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CommandExecutor {

    private static volatile Process currentProcess;

    public static boolean stopCurrentProcess() {
        if (currentProcess != null) {
            Process process = currentProcess;
            currentProcess = null;
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                    return !process.isAlive();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @param command        命令
     * @param workingDir     设置工作目录
     * @param env            设置环境变量
     * @param timeoutSeconds 命令超时时间
     * @return 执行code
     * @throws IOException          IO异常
     * @throws InterruptedException
     * @throws TimeoutException
     */
    private static CommandResult runInternal(
            List<String> command, File workingDir, Map<String, String> env, long timeoutSeconds) {

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir);
            }
            if (env != null) {
                builder.environment().putAll(env);
            }

            Process process = builder.start();
            currentProcess = process;

            Future<?> stdoutFuture =
                    executor.submit(() -> readStream(process.getInputStream(), stdoutBuilder));
            Future<?> stderrFuture =
                    executor.submit(() -> readStream(process.getErrorStream(), stderrBuilder));
            boolean finished;

            if (timeoutSeconds <= 0) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            }

            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(
                        -1, stdoutBuilder.toString(), "Timeout after " + timeoutSeconds + " seconds");
            }

            stdoutFuture.get();
            stderrFuture.get();

            int exitCode = process.exitValue();
            return new CommandResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString());
        } catch (Exception e) {
            return new CommandResult(-1, "", "Exception: " + e.getMessage());
        }
    }

    /**
     * @param rawCommand     命令
     * @param workingDir     设置工作目录
     * @param env            设置环境变量
     * @param timeoutSeconds 命令超时时间
     * @return 执行code
     * @throws IOException          IO异常
     * @throws InterruptedException
     * @throws TimeoutException
     */
    public static CommandResult run(
            String rawCommand, File workingDir, Map<String, String> env, long timeoutSeconds) {

        List<String> command = wrapCommandByOS(rawCommand);
        return runInternal(command, workingDir, env, timeoutSeconds);
    }

    public static CommandResult run(
            List<String> command, File workingDir, Map<String, String> env, long timeoutSeconds) {
        return runInternal(command, workingDir, env, timeoutSeconds);
    }

    public static CommandResult run(String shellCommand, long timeoutSeconds) {
        return run(shellCommand, null, null, timeoutSeconds);
    }

    /**
     * 自动判断平台并封装为适当的命令格式（bash 或 cmd）。
     */
    private static List<String> wrapCommandByOS(String rawCommand) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return Arrays.asList("cmd", "/c", "\"" + rawCommand + "\"");
        } else {
            return Arrays.asList("bash", "-c", rawCommand);
        }
    }

    private static void readStream(InputStream stream, StringBuilder outputBuilder) {
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            outputBuilder.append("Stream read error: ").append(e.getMessage()).append("\n");
        }
    }

    public static class CommandResult {
        private final int exitCode;

        private final String stdout;

        private final String stderr;

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "Exit Code: "
                    + exitCode
                    + "\n--- STDOUT ---\n"
                    + stdout
                    + "\n--- STDERR ---\n"
                    + stderr;
        }
    }
}
