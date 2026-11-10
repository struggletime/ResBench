from fastapi import Header, HTTPException
from typing import Union

# https://fastapi.tiangolo.com/zh/tutorial/bigger-applications/#apirouter_3


async def get_privilege_token(token: str):
    # e.g.: http://127.0.0.1:8000/op_instance/Win/postgres/1/stop?token=exec
    if token is None:
        raise HTTPException(status_code=400, detail="Please specify the token")
    elif token != "exec":
        raise HTTPException(status_code=400, detail="No exec token provided")


async def common_params(q: Union[str, None] = None, skip: int = 0, limit: int = 100):
    return {"q": q, "skip": skip, "limit": limit}