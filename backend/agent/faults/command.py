import asyncio
import datetime
import platform

from fastapi import APIRouter, Request

router = APIRouter(prefix="/command",
                   tags=["command"],
                   responses={404: {"description": "Not found"}}, )

# 动态编码选择：Windows用gbk，Linux/Mac用utf-8
SYSTEM_ENCODING = 'gbk' if platform.system() == 'Windows' else 'utf-8'

# 限制最大并发命令执行数
semaphore = asyncio.Semaphore(5)  # 允许同时执行5个命令


@router.post("/exec")
async def exec_cmd(request: Request):
    async with semaphore:
        body = await request.body()
        command = body.decode('utf-8')  # 请求体统一用utf-8解码
        # 空行分隔不同的注入命令，便于阅读日志
        print()
        print()
        start_time = datetime.datetime.now()
        print("[AGENT][/command/exec][%s] begin to exec: %s"
              % (start_time.strftime("%Y-%m-%d %H:%M:%S"), command))
        process = None
        try:
            # 使用异步 subprocess
            process = await asyncio.create_subprocess_shell(
                command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )

            # 等待命令执行完成（带超时控制）
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=30)
            returncode = process.returncode
            elapsed = (datetime.datetime.now() - start_time).total_seconds()
            stdout_text = stdout.decode(SYSTEM_ENCODING, errors="replace")
            stderr_text = stderr.decode(SYSTEM_ENCODING, errors="replace")

            if returncode == 0:
                print("[AGENT][/command/exec][SUCCESS] command executed successfully, returncode=0, elapsed=%.2fs" % elapsed)
                # 命令下发/执行正常。若故障仍无效果，问题多半在故障实现层，而非命令下发
                print("[AGENT][/command/exec][SUCCESS] 注：命令执行正常；若注入后无效果，问题不在命令下发，而在故障实现")
            else:
                print("[AGENT][/command/exec][FAIL] command execution failed, returncode=%s, elapsed=%.2fs" % (returncode, elapsed))
            if stdout_text:
                print("[AGENT][/command/exec][STDOUT]\n%s" % stdout_text)
            if stderr_text:
                print("[AGENT][/command/exec][STDERR]\n%s" % stderr_text)

            return {
                "stdout": stdout_text,
                "stderr": stderr_text,
                "returncode": returncode
            }

        except asyncio.TimeoutError:
            error_msg = "Command timed out after 30 seconds"
            print("[AGENT][/command/exec][ERROR] %s: %s" % (error_msg, command))
            if process and process.returncode is None:
                try:
                    # 跨平台终止进程
                    process.terminate()  # 先尝试温和终止（Linux SIGTERM）
                    await asyncio.wait_for(process.wait(), timeout=5)
                except:
                    process.kill()  # 强制终止（Windows/Linux通用）
                    await process.wait()
                error_msg += " (process terminated)"

            return {
                "stdout": "",
                "stderr": "",
                "returncode": -1,
                "error": error_msg
            }
        except Exception as e:
            print("[AGENT][/command/exec][ERROR] unexpected error: %s" % e)
            return {
                "stdout": "",
                "stderr": "",
                "returncode": -1,
                "error": str(e)
            }
