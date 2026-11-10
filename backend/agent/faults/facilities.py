from fastapi import APIRouter

router = APIRouter()


@router.get("/power/on/", tags=["power"])
async def read_users():
    return [{"username": "Rick"}, {"username": "Morty"}]

