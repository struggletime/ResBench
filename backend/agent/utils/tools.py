import signal
import subprocess
import os
import datetime
import tempfile


def exec(command, timeout=60, capture=False):
    # 只要捕获输出，start postgres操作就会Hang
    start_time = datetime.datetime.now()
    command_str = " ".join(command)
    print("[AGENT][exec][%s] begin to exec: %s" % (start_time.strftime("%Y-%m-%d %H:%M:%S"), command_str))
    # ret = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)  # WILL Hang

    # capture=True：把子进程输出重定向到临时文件，再把文件内容拼进返回串。
    #
    # 为什么不用管道：chaosblade 的 create 会拉起常驻的 chaos_os 进程，它继承 stdout/stderr
    # 管道后管道永远等不到 EOF，communicate() 会一直挂到 chaos_os 退出（可能是 120s），
    # 引擎那边早就在 10s 限额上放弃了。临时文件不依赖 EOF，父进程退出即可读取。
    # 默认 False 保持旧行为（输出直接进 agent 日志，不占内存、不影响任何现有命令）。
    out_file = tempfile.TemporaryFile(mode="w+") if capture else None
    err_file = tempfile.TemporaryFile(mode="w+") if capture else None

    try:
        with subprocess.Popen(command,
                              stdout=out_file,
                              stderr=err_file,
                              universal_newlines=True) as proc:
            try:
                stdout, stderr = proc.communicate(timeout=timeout)
                returncode = proc.poll()
                if capture:
                    out_file.seek(0)
                    stdout = out_file.read()
                    err_file.seek(0)
                    stderr = err_file.read()
                elapsed = (datetime.datetime.now() - start_time).total_seconds()
                if returncode == 0:
                    print("[AGENT][exec][SUCCESS] command executed successfully: %s (returncode=0, elapsed=%.2fs)" % (command_str, elapsed))
                    # 命令下发/执行正常。若故障仍无效果，问题多半在故障实现层，而非命令下发
                    print("[AGENT][exec][SUCCESS] 注：命令执行正常；若注入后无效果，问题不在命令下发，而在故障实现")
                else:
                    print("[AGENT][exec][FAIL] command execution failed: %s (returncode=%s, elapsed=%.2fs)" % (command_str, returncode, elapsed))
                return "Executed: %s: %s: %s" % ("Successful" if returncode == 0 else "Error " + str(returncode), stdout, stderr)
            except subprocess.TimeoutExpired:
                # os.killpg(os.getpgid(proc.pid), signal.SIGKILL) # 使用killpg会把自己杀死
                os.kill(proc.pid, signal.SIGKILL)
                print("[AGENT][exec][ERROR] command timed out after %ss: %s" % (timeout, command_str))
                return "TimeoutExpired=%s" % timeout
    except FileNotFoundError as e:
        # 命令/可执行文件不存在：属于命令下发或环境配置错误
        print("[AGENT][exec][ERROR] command not found (dispatch/env error): %s — %s" % (command_str, e))
        return "CommandNotFound: %s" % e
    except Exception as e:
        print("[AGENT][exec][ERROR] unexpected error executing command: %s — %s" % (command_str, e))
        return "Error: %s" % e
    finally:
        # 无论成功/超时/异常都要关掉临时文件；后台子进程即使还持有 FD，
        # 关闭父进程这一份也不会影响它，只是它后续的输出不会有人再读。
        if out_file is not None:
            out_file.close()
        if err_file is not None:
            err_file.close()


def exec_capture(command, timeout=30):
    """结构化执行：返回 (returncode, stdout, stderr, timed_out)。

    与 exec() 的区别：保留 stdout/stderr 原文，供 /check 等需要解析输出的
    协议使用。命令超时会被 kill 并标记 timed_out=True。
    """
    start_time = datetime.datetime.now()
    command_str = " ".join(str(c) for c in command)
    print("[AGENT][exec_capture][%s] begin to exec: %s" % (start_time.strftime("%Y-%m-%d %H:%M:%S"), command_str))
    try:
        proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        try:
            stdout, stderr = proc.communicate(timeout=timeout)
            returncode = proc.poll()
            elapsed = (datetime.datetime.now() - start_time).total_seconds()
            print("[AGENT][exec_capture][%s] rc=%s elapsed=%.2fs stdout_len=%s stderr_len=%s"
                  % ("SUCCESS" if returncode == 0 else "FAIL", returncode, elapsed,
                     len(stdout or ""), len(stderr or "")))
            return returncode, stdout or "", stderr or "", False
        except subprocess.TimeoutExpired:
            proc.kill()
            try:
                proc.communicate()
            except Exception:
                pass
            print("[AGENT][exec_capture][ERROR] command timed out after %ss: %s" % (timeout, command_str))
            return -1, "", "TimeoutExpired after %ss" % timeout, True
    except FileNotFoundError as e:
        print("[AGENT][exec_capture][ERROR] command not found: %s — %s" % (command_str, e))
        return -1, "", "CommandNotFound: %s" % e, False
    except Exception as e:
        print("[AGENT][exec_capture][ERROR] unexpected error: %s — %s" % (command_str, e))
        return -1, "", "Error: %s" % e, False
