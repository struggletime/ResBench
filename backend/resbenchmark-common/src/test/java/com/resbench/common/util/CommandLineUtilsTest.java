package com.resbench.common.util;

import org.junit.Assert;
import org.junit.Test;

public class CommandLineUtilsTest {

    @Test
    public void testParse(){
        String args = "attach";
        String[] parse = CommandLineUtils.parse(args);

        Assert.assertEquals(1, parse.length);
        Assert.assertEquals("attach", parse[0]);
    }

    @Test
    public void testParse_1(){
        String args = "inject -c  config/opengauss/opengauss_smallbank_config_chaosblade.xml";
        String[] parse = CommandLineUtils.parse(args);

        Assert.assertEquals(3, parse.length);
        Assert.assertEquals("inject", parse[0]);
        Assert.assertEquals("-c", parse[1]);
        Assert.assertEquals("config/opengauss/opengauss_smallbank_config_chaosblade.xml", parse[2]);


    }
}