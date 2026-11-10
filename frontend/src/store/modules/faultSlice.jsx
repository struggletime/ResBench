import { createSlice } from '@reduxjs/toolkit';

const faultSlice = createSlice({
    name:"faultInjection",
    initialState:{
        selectedFaults:[
            // {
            //     name:"",
            //     description:"",
            // }
        ], // 所有选中的错误
        isGroupMode: false, // 是否处于组合模式
        paramsValue: {},
        selectedFaultType: 'CPU',
        expandedGroups: [],
        currentEditIndex: -1,
        currentEditGroupId: null,
        currentEditFaultId: null,
    },
    reducers:{
        updateSelectedFaults: (state,action)=>{
            state.selectedFaults = action.payload
        },
        updateParamsValue: (state, action) => {
            state.paramsValue = action.payload || {};
        },
        updateSelectedFaultType: (state, action) => {
            state.selectedFaultType = action.payload || 'CPU';
        },
        updateExpandedGroups: (state, action) => {
            state.expandedGroups = action.payload || [];
        },
        updateCurrentEditState: (state, action) => {
            state.currentEditIndex = Number.isInteger(action.payload?.currentEditIndex)
                ? action.payload.currentEditIndex
                : -1;
            state.currentEditGroupId = action.payload?.currentEditGroupId ?? null;
            state.currentEditFaultId = action.payload?.currentEditFaultId ?? null;
        },
        deleteFault: (state,action) =>{
            state.selectedFaults = state.selectedFaults.filter(item => item.name !== action.payload.name);
        },
        // 切换组合模式
        toggleGroupMode: (state) => {
            state.isGroupMode = !state.isGroupMode;
        },
        // 设置组合模式状态
        setGroupMode: (state, action) => {
            state.isGroupMode = action.payload;
        },
        // 添加组合故障
        addFaultGroup: (state, action) => {
            state.selectedFaults.push(action.payload);
        },
        // 更新组合内的故障
        updateGroupFaults: (state, action) => {
            const { groupId, faults } = action.payload;
            const group = state.selectedFaults.find(item => item.id === groupId && item.type === 'group');
            if (group) {
                group.faults = faults;
            }
        },
        // 从组合中移除故障
        removeFaultFromGroup: (state, action) => {
            const { groupId, faultId } = action.payload;
            const group = state.selectedFaults.find(item => item.id === groupId && item.type === 'group');
            if (group) {
                group.faults = group.faults.filter(f => f.id !== faultId);
            }
        },
        // 删除整个组合
        deleteGroup: (state, action) => {
            state.selectedFaults = state.selectedFaults.filter(item => item.id !== action.payload);
        },
    }
})
export const {
    updateSelectedFaults,
    updateParamsValue,
    updateSelectedFaultType,
    updateExpandedGroups,
    updateCurrentEditState,
    deleteFault,
    toggleGroupMode,
    setGroupMode,
    addFaultGroup,
    updateGroupFaults,
    removeFaultFromGroup,
    deleteGroup
} = faultSlice.actions
export default faultSlice.reducer;
