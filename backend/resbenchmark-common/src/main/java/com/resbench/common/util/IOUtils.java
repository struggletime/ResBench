package com.resbench.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

public class IOUtils {

    public static String inputStreamToString(InputStream in, Charset charset) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                result.append(buffer, 0, len);
            }
        }
        return result.toString();
    }
}
