package com.resbench.common.fault;

public class Command {
  private String cmd;
  private String[] args;

  public Command(String cmd, String[] args) {
    this.cmd = cmd.trim();
    this.args = args.clone();
  }


  public String getCmd() {
    return cmd;
  }

  public void setCmd(String cmd) {
    this.cmd = cmd;
  }

  public String[] getArgs() {
    return args;
  }

  public void setArgs(String[] args) {
    this.args = args;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (String arg : args) {
      sb.append(" ").append(arg);
    }
    return cmd + sb;
  }
}
