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

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author pavlo
 */
public abstract class CollectionUtil {

  public static <T> Collection<T> intersection(Collection<? extends T> a, Collection<? extends T> b) {
    ArrayList<T> result = new ArrayList<>();
    if (a == null || b == null) {
      return result;
    }
    // 统计b中元素出现的次数
    Map<T, Integer> freqMap = new HashMap<>();
    for (T item : b) {
      freqMap.merge(item, 1, Integer::sum);
    }

    // 按A的顺序生成交集
    for (T item : a) {
      Integer count = freqMap.get(item);
      if (count != null && count > 0) {
        result.add(item);
        freqMap.put(item, count - 1);
      }
    }
    return result;
  }

  /**
   * Put all the values of an Iterator into a List
   *
   * @param <T>
   * @param it
   * @return
   */
  public static <T> List<T> list(Iterator<T> it) {
    List<T> list = new ArrayList<>();
    CollectionUtil.addAll(list, it);
    return (list);
  }

  /**
   * Add all the items in the array to a Collection
   *
   * @param <T>
   * @param data
   * @param items
   */
  @SuppressWarnings("unchecked")
  public static <T> Collection<T> addAll(Collection<T> data, T... items) {
    data.addAll(Arrays.asList(items));
    return (data);
  }

  /**
   * Add all of the items from the Iterator into the given collection
   *
   * @param <T>
   * @param data
   * @param items
   */
  public static <T> Collection<T> addAll(Collection<T> data, Iterator<T> items) {
    while (items.hasNext()) {
      data.add(items.next());
    }
    return (data);
  }

  /**
   * @param <T>
   * @param <U>
   * @param map
   * @return
   */
  public static <T, U extends Comparable<U>> T getGreatest(Map<T, U> map) {
    T max_key = null;
    U max_value = null;
    for (Map.Entry<T, U> e : map.entrySet()) {
      T key = e.getKey();
      U value = e.getValue();
      if (max_value == null || value.compareTo(max_value) > 0) {
        max_value = value;
        max_key = key;
      }
    } // FOR
    return (max_key);
  }

  /**
   * Return the first item in a Iterable
   *
   * @param <T>
   * @param items
   * @return
   */
  public static <T> T first(Iterable<T> items) {
    return (CollectionUtil.get(items, 0));
  }

  /**
   * Return the ith element of a set. Super lame
   *
   * @param <T>
   * @param items
   * @param idx
   * @return
   */
  public static <T> T get(Iterable<T> items, int idx) {
    if (items == null || idx < 0) {
      return null;
    }

    int i = 0 ;
    for (T item : items) {
      if (i == idx) {
        return item;
      }
      i++;
    }
    return null;
  }

  /**
   * Return the last item in an Iterable
   *
   * @param <T>
   * @param items
   * @return
   */
  public static <T> T last(Iterable<T> items) {
    T last = null;
    if (items instanceof AbstractList<?>) {
      AbstractList<T> list = (AbstractList<T>) items;
      last = (list.isEmpty() ? null : list.get(list.size() - 1));
    } else {
      for (T t : items) {
        last = t;
      }
    }
    return (last);
  }

  /**
   * Return the last item in an array
   *
   * @param <T>
   * @param items
   * @return
   */
  @SuppressWarnings("unchecked")
  public static <T> T last(T... items) {
    if (items != null && items.length > 0) {
      return (items[items.length - 1]);
    }
    return (null);
  }

  /**
   * Wrap an Iterable around an Iterator
   *
   * @param <T>
   * @param it
   * @return
   */
  public static <T> Iterable<T> iterable(final Iterator<T> it) {
    return (new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return (it);
      }
    });
  }

  public static <T> T pop(Collection<T> items) {
    T t = CollectionUtil.first(items);
    if (t != null) {
      items.remove(t);
    }
    return (t);
  }

  /**
   * 将根据map key排序 将value转换为 List
   *
   * @param map map
   * @param <K> key
   * @param <V> value
   * @return
   */
  public static <K extends Comparable<? super K>, V> List<V> convertList(Map<K, V> map) {
    return map.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue).collect(Collectors.toList());
  }

  /**
   * @param list list 对象
   * @param n 获取末尾多少个数据
   * @param <T> T
   * @return 返回截取list对象的视图 对其修改会影响原始列表
   */
  public static <T> List<T> getLastN(List<T> list, int n) {
    if (list == null || list.isEmpty()) {
      return new ArrayList<>();
    }

    int size = list.size();
    int fromIndex = Math.max(0, size - n);
    return list.subList(fromIndex, size);
  }

  /**
   * 从列表中截取指定范围的子列表。
   *
   * @param list 原始列表
   * @param fromIndex 起始下标（包含）
   * @param count 截取数量（最大返回 count 个）
   * @param <T> 列表中元素类型
   * @return 子列表（如果越界，则返回尽可能多的）
   */
  public static <T> List<T> slice(List<T> list, int fromIndex, int count) {
    if (list == null || list.isEmpty()) {
      return Collections.emptyList();
    }

    int size = list.size();
    if (fromIndex < 0 || fromIndex >= size || count <= 0) {
      return Collections.emptyList();
    }

    int toIndex = Math.min(fromIndex + count, size);
    return list.subList(fromIndex, toIndex);
  }
}
