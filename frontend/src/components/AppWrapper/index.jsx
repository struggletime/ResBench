import React from "react";
import style from "./AppWrapper.module.css";

const AppWrapper = ({children}) => {
    return (
        <div className={style['app-container']}>
            {children}
        </div>
    )
}
export default AppWrapper;