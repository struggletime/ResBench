from dependencies.fault_models import common_params
from utils.tools import exec
from fastapi.templating import Jinja2Templates


from fastapi import APIRouter, Depends, Request

router = APIRouter(prefix="/process",
    tags=["process"],
    responses={404: {"description": "Not found"}},)


templates = Jinja2Templates(directory="templates")  # 模板目录

@router.get("/instance/{os}/{dbtype}/{inst}/{op}")
async def op_db_instance(request: Request, os: str, dbtype: str, inst: int, op: str, com: dict = Depends(common_params)):
    print(com)
    command = []
    if 'Win' in os:
        if dbtype == 'postgres':
            if inst == 1:
                command.append("C:\\Program Files\\PostgreSQL\\15\\bin\\pg_ctl.exe")

                if op == 'start':
                    command.append("start")
                elif op == 'stop':
                    command.append("stop")

                command.append("-D")
                command.append("C:\\Program Files\\PostgreSQL\\15\\data")

    retinfo = exec(command)

    return templates.TemplateResponse(
        "faults.html",
        {
            "request": request,
            "retinfo": retinfo,
        }
    )