from dependencies.fault_models import common_params
from utils.tools import exec
from fastapi.templating import Jinja2Templates


from fastapi import APIRouter, Depends, Request

router = APIRouter(prefix="/hardware",
    tags=["hardware"],
    responses={404: {"description": "Not found"}},)


templates = Jinja2Templates(directory="templates")  # 模板目录


@router.get("/mem/{node}/{op}", tags=["memory"])
async def op_mem(request: Request, node: int, op: str, com: dict = Depends(common_params)):
    print(com)
    command = []
    if node == 1:
        command.append("")

        if op == 'xxx':
            command.append("")
        elif op == 'yyy':
            command.append("")

        command.append("")

    retinfo = exec(command)

    return templates.TemplateResponse(
        "faults.html",
        {
            "request": request,
            "retinfo": retinfo,
        }
    )

@router.get("/disk/{node}/{op}", tags=["memory"])
async def op_disk(request: Request, node: int, op: str, com: dict = Depends(common_params)):
    print(com)
    command = []
    if node == 1:
        command.append("")

        if op == 'xxx':
            command.append("")
        elif op == 'yyy':
            command.append("")

        command.append("")

    retinfo = exec(command)

    return templates.TemplateResponse(
        "faults.html",
        {
            "request": request,
            "retinfo": retinfo,
        }
    )
