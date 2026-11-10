package com.resbench.common.util;

import java.util.ArrayList;

public class CommandLineUtils {

    /**
     *
     *
     * @param commandLine  输入命令 如： inject -c xxx.xml 解析为字符串
     * @return
     */
    public static String[] parse(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return null;
        }
        ArrayList<String> args = new ArrayList();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (Character.isWhitespace(c)) {
                if(current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }
}
