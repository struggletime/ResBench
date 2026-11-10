/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resbench.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author pavlo2
 */
public abstract class FileUtil {
  private static final Logger LOG =  LoggerFactory.getLogger(FileUtil.class);
  private static final Pattern EXT_SPLIT = Pattern.compile("\\.");

  /**
   * Join path components
   *
   * @param args
   * @return
   */
  public static String joinPath(String... args) {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for (String a : args) {
      if (a != null && a.length() > 0) {
        if (!first) {
          result.append("/");
        }
        result.append(a);
        first = false;
      }
    }
    return result.toString();
  }

  /**
   * 使用传统IO方式复制文件（兼容性更好）
   * @param sourceFilePath 源文件路径
   * @param targetDirPath 目标目录路径
   * @return 复制后的文件路径，如果失败返回 null
   */
  public static String copyFileTraditional(String sourceFilePath, String targetDirPath) {
    try {
      // 创建目录对象
      File targetDir = new File(targetDirPath);
      if (!targetDir.exists()) {
        targetDir.mkdirs();
      }

      // 获取文件名
      File sourceFile = new File(sourceFilePath);
      if (!sourceFile.exists()) {
        System.err.println("错误：源文件不存在 - " + sourceFilePath);
        return null;
      }

      String fileName = sourceFile.getName();
      String targetPath = targetDirPath + File.separator + fileName;

      // 使用文件流复制
      try (FileInputStream fis = new FileInputStream(sourceFile);
           FileOutputStream fos = new FileOutputStream(targetPath)) {

        byte[] buffer = new byte[1024];
        int length;
        while ((length = fis.read(buffer)) > 0) {
          fos.write(buffer, 0, length);
        }
      }

      System.out.println("文件复制成功: " + sourceFilePath + " -> " + targetPath);
      return targetPath;

    } catch (IOException e) {
      System.err.println("文件复制失败: " + e.getMessage());
      return null;
    }
  }

  /**
   * 将文件移动到另一个目录
   * @param sourceFilePath 源文件完整路径（如：/home/user/file.txt）
   * @param targetDirPath 目标目录路径（如：/home/user/documents/）
   * @return 移动后的文件路径，如果失败返回 null
   */
  public static String moveFile(String sourceFilePath, String targetDirPath) {
    try {
      Path source = Paths.get(sourceFilePath);
      Path targetDir = Paths.get(targetDirPath);

      // 检查源文件是否存在
      if (!Files.exists(source)) {
        System.err.println("错误：源文件不存在 - " + sourceFilePath);
        return null;
      }

      // 创建目标目录（如果不存在）
      if (!Files.exists(targetDir)) {
        Files.createDirectories(targetDir);
      }

      // 获取文件名
      String fileName = source.getFileName().toString();

      // 构建目标路径
      Path target = targetDir.resolve(fileName);

      // 执行移动操作
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

      System.out.println("文件转移成功: " + sourceFilePath + " -> " + target.toString());
      return target.toString();

    } catch (IOException e) {
      System.err.println("文件转移失败: " + e.getMessage());
      return null;
    }
  }

  /**
   * Given a basename for a file, find the next possible filename if this file already exists. For
   * example, if the file test.res already exists, create a file called, test.1.res
   *
   * @param basename
   * @return
   */
  public static String getNextFilename(String basename) {

    if (!exists(basename)) {
      return basename;
    }

    File f = new File(basename);
    if (f != null && f.isFile()) {
      String parts[] = EXT_SPLIT.split(basename);

      // Check how many files already exist
      int counter = 1;
      String nextName = parts[0] + "." + counter + "." + parts[1];
      while (exists(nextName)) {
        ++counter;
        nextName = parts[0] + "." + counter + "." + parts[1];
      }
      return nextName;
    }

    // Should we throw instead??
    return null;
  }

  public static boolean exists(String path) {
    return (new File(path).exists());
  }

  /**
   * Create any directory in the list paths if it doesn't exist
   *
   * @param paths
   */
  public static void makeDirIfNotExists(String... paths) {
    for (String p : paths) {
      if (p == null) {
        continue;
      }
      File f = new File(p);
      if (!f.exists()) {
        f.mkdirs();
      }
    }
  }

  public static void writeStringToFile(File file, String content) throws IOException {
    try (FileWriter writer = new FileWriter(file)) {
      writer.write(content);
      writer.flush();
    }
  }

  /**
   * 获取指定目录下的所有子目录名称（只包含名称，不含路径）
   *
   * @param parentDirPath 父目录的路径（相对或绝对）
   * @return 所有子目录名称的列表
   */
  public static List<String> listSubDirectoryNames(String parentDirPath) {
    List<String> subDirNames = new ArrayList<>();
    File parentDir = new File(parentDirPath);

    if (!parentDir.exists() || !parentDir.isDirectory()) {
      System.err.println("不是有效目录: " + parentDirPath);
      return subDirNames;
    }

    File[] files = parentDir.listFiles();
    if (files == null) {
      return subDirNames;
    }

    for (File file : files) {
      if (file.isDirectory()) {
        subDirNames.add(file.getName());
      }
    }

    return subDirNames;
  }

  /**
   * 读取文件的所有内容，作为一个字符串返回
   *
   * @param filePath 文件路径（支持相对或绝对路径）
   * @return 文件内容字符串，失败时返回 null
   */
  public static String readFileAsString(String filePath) {
    try {
      List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
      return String.join(System.lineSeparator(), lines);
    } catch (IOException e) {
      LOG.error("读取文件失败: {}", filePath);
      return null;
    }
  }

  /**
   * 获取最新的子目录
   *
   * @param resultsDir 存放结果的目录
   * @return 最新的子目录，如果没有子目录则返回 null
   */
  public static File getLatestDir(File resultsDir) {
    File[] subDirs = resultsDir.listFiles(File::isDirectory);

    if (subDirs == null || subDirs.length == 0) {
      return null;
    }
    File latestDir = null;
    long latestTime = 0;
    for (File dir : subDirs) {
      long dirTime = dir.lastModified();
      if (dirTime > latestTime) {
        latestTime = dirTime;
        latestDir = dir;
      }
    }
    return latestDir;
  }

  /**
   * 根据历史名称查找对应的结果目录
   *
   * @param historyName 历史名称
   * @return 目标目录，如果不存在则返回 null
   */
  public static File getTagetDir(String historyName) {
    File resultsDir = new File("results");
    if (!resultsDir.exists()) {
      if (!resultsDir.mkdir()) {
        return null;
      }
    }
    File[] subDirs = resultsDir.listFiles(File::isDirectory);

    if (subDirs == null || subDirs.length == 0) {
      return null;
    }

    File targer = null;
    for (File dir : subDirs) {
      if (dir.getName().equals(historyName)) {
        targer = dir;
      }
    }
    return targer;
  }

  /**
   * 根据文件目录按行读取文件内容
   *
   * @param filePath 文件目录
   * @return List<String>
   */
  public static List<String> readFile(String filePath) {
    List<String> lines = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        lines.add(line);
      }
    } catch (IOException e) {
      LOG.error("Failed to read the {}. err: {}", filePath, e.getMessage());
    }
    return lines;
  }

  /**
   * 向指定路径的文件写入内容（覆盖模式）。
   *
   * @param filePath 文件路径
   * @param content 写入的内容
   * @throws IOException 写入失败时抛出
   */
  public static void writeToFile(String filePath, String content) throws IOException {
    writeToFile(filePath, content, false);
  }

  /**
   * 向指定路径的文件写入内容。
   *
   * @param filePath 文件路径
   * @param content 写入的内容
   * @param append 是否追加内容，true = 追加，false = 覆盖
   * @throws IOException 写入失败时抛出
   */
  public static void writeToFile(String filePath, String content, boolean append)
      throws IOException {
    File file = new File(filePath);
    // 自动创建目录
    File parentDir = file.getParentFile();
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs();
    }

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, append))) {
      writer.write(content);
    }
  }

  /**
   * 使用文件锁（跨进程）覆盖写入指定路径内容
   *
   * @param path 文件路径
   * @param content 要写入的内容
   * @param charset 字符编码（如 UTF-8）
   * @param append 是否追加
   * @throws IOException 如果文件操作出错
   */
  public static void writeWithLock(String path, String content, Charset charset, boolean append)
      throws IOException {
    File file = new File(path);
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    try (FileOutputStream fos = new FileOutputStream(file, append);
         FileChannel channel = fos.getChannel()) {

      FileLock lock = channel.lock(); // 独占锁
      try {
        byte[] bytes = content.getBytes(charset);
        fos.write(bytes);
        fos.flush();
      } finally {
        lock.release();
      }
    }
  }

  /**
   * 使用文件锁（跨进程）覆盖写入指定路径内容 覆盖写入
   *
   * @param path 文件路径
   * @param content 要写入的内容
   * @throws IOException 如果文件操作出错
   */
  public static void writeWithLock(String path, String content) throws IOException {
    writeWithLock(path, content, StandardCharsets.UTF_8, false);
  }

  public static List<String> readWithLock(String path, Charset charset) throws IOException {
    File file = new File(path);
    if (!file.exists()) {
      throw new IOException("文件不存在: " + path);
    }
    List<String> lines = new ArrayList<>();

    FileInputStream fis = new FileInputStream(file);
    FileChannel channel = fis.getChannel();
    FileLock lock = null;
    try {
      lock = channel.lock(0L, Long.MAX_VALUE, true); // 共享锁
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(fis, charset))) {
        String line;
        while ((line = reader.readLine()) != null) {
          lines.add(line);
        }
      }
    } finally {
      if (lock != null && lock.isValid()) {
        lock.release();
      }
      channel.close();
      fis.close();
    }

    return lines;
  }

  /**
   * @param path 路径
   * @return 返回异常
   * @throws IOException IOException
   */
  public static List<String> readWithLock(String path) throws IOException {
    return readWithLock(path, StandardCharsets.UTF_8);
  }

  public static Set<String> getWorkLoads(String directory) throws IOException {
    Set<String> loads = new HashSet<>();
    Path pathDir = Paths.get(directory);
    if (!Files.isDirectory(pathDir)) {
      LOG.error("directory does not exists: {}",directory);
      return loads;
    }

    try(Stream<Path> paths = Files.list(pathDir)) {
      loads = paths.filter(Files::isRegularFile)
              .map(path -> {
                String fileName = path.getFileName().toString();
                String[] parts = fileName.split("_");
                return parts.length >= 2 ? parts[1]:null;
              })
              .filter(part -> part != null)
              .collect(Collectors.toSet());
    }
    return loads;
  }
}
