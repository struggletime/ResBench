import { createSlice } from "@reduxjs/toolkit";

const historySlice = createSlice({
    name:'history',
    initialState:{
        runs:[
            {
                id:'1' ,
                runName:'test network',
                DBVersion:'mysql 5.0',
                startTime:'2025-6-22 12:37',
                endTime:'2025-6-22 13:50',
                status:'error',
                operations:['download']
            },
            {
                id:'2' ,
                runName:'test CPU',
                DBVersion:'mysql 5.0',
                startTime:'2025-6-27 9:13',
                endTime:'2025-6-27 12:24',
                status:'normal',
                operations:['download']
            },
            {
                id:'3' ,
                runName:'test disk',
                DBVersion:'mysql 5.0',
                startTime:'2025-6-30 11:57',
                endTime:'2025-6-30 16:17',
                status:'stopped',
                operations:['download']
            },
            {
                id:'4' ,
                runName:'test disk',
                DBVersion:'mysql 5.0',
                startTime:'2025-7-4 10:35',
                endTime:'....',
                status:'running',
                operations:['download']
            },
        ]
    },
    reducers:{
        addHistory:(state,action) => {
            // 其中action payload应该为对象
            state.runs = [...state.runs,action.payload]
        },
        deleteHistory:(state,action) => {
            // 其中action payload应该为对象
            state.runs = state.runs.filter(item=> item.id !== action.payload.id)
        }
    }

})

export const {addHistory,deleteHistory} = historySlice.actions;
export default historySlice.reducer;