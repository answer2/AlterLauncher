package com.answer.launcher.core.tool;

import android.content.Context;
import com.answer.launcher.core.Constants;
import java.lang.reflect.Array;
import java.util.ArrayList;

public class HotFix {
  /** 插桩修复 */
  public static void hotFix(Context context, ClassLoader loader) {
    try {
      // 获取自有类加载器中的 dexElements 对象
      Object patchDexElements = getDexElements(loader);
      // 获取系统类加载器中的 dexElements 对象
      Object oldDexElements = getDexElements(context.getClassLoader());
      // 合并 dexElements 数组
      Object newDexElements = merge(patchDexElements, oldDexElements);
      // 获取自有类加载器中的 nativeLibraryDirectories 对象
      Object patchNativeLibraryDirs = getNativeLibraryDirectories(loader);
      // 获取系统类加载器中的 nativeLibraryDirectories 对象
      Object oldNativeLibraryDirs = getNativeLibraryDirectories(context.getClassLoader());
      // 合并 nativeLibraryDirectories 数组
      Object newNativeLibraryDirs = merge(patchNativeLibraryDirs, oldNativeLibraryDirs);
      // 获取系统类加载器中的 pathList 对象
      Object pathList = getPathList(context.getClassLoader());
      // 将合并后的 dexElements 数组赋值给系统的类加载器 pathList 对象的 dexElements 属性
      setDexElements(pathList, newDexElements);
      // 将合并后的 nativeLibraryDirectories 数组赋值给系统的类加载器 pathList 对象的 nativeLibraryDirectories 属性
      setNativeLibraryDirectories(pathList, newNativeLibraryDirs);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * 获取某个属性对象
   *
   * @param obj 该属性所属类的对象
   * @param clazz 该属性的所属类
   * @param field 属性名
   */
  public static Object getField(Object obj, Class<?> clazz, String field) {
    try {
      return Reflector.on(clazz).field(field).get(obj);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * 给某个属性赋值
   *
   * @param obj 该属性所属类的对象
   * @param clazz 该属性的所属类
   * @param field 属性名
   * @param value 值
   */
  public static void setField(Object obj, Class<?> clazz, String field, Object value) {
    try {
       Reflector.on(clazz).field(field).set(obj, value);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * 获取BaseDexClassLoader对象中的pathList对象
   *
   * @param baseDexClassLoader baseDexClassLoader对象
   */
  public static Object getPathList(Object baseDexClassLoader) {
    try {
      return getField(
          baseDexClassLoader, Class.forName(Constants.BASE_DEX_CLASS_LOADER), Constants.PATH_LIST);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * 获取PathList对象中的dexElements对象
   * @param pathList pathList对象
   */
  public static Object getDexElements(ClassLoader classloader) {
        Object pathList = getPathList(classloader);
    return getField(pathList, pathList.getClass(), Constants.DEX_ELEMENTS);
  }

  private static void setDexElements(Object pathList, Object newElements) throws Exception {
    setField(pathList, pathList.getClass(), Constants.DEX_ELEMENTS, newElements);
  }
    
  private static Object getNativeLibraryDirectories(ClassLoader classLoader) throws Exception {
    Object pathList = getPathList(classLoader);
    return getField(pathList, pathList.getClass(), Constants.NATIVE_LIBRARY_DIRECTORIES);
  }

  private static void setNativeLibraryDirectories(Object pathList, Object newDirs)
      throws Exception {
    setField(pathList, pathList.getClass(), Constants.NATIVE_LIBRARY_DIRECTORIES, newDirs);
  }

  /**
   * 数组合并或列表合并
   *
   * @param patchArray 补丁包数组或列表
   * @param oldArray 应用原数组或列表
   * @return 合并后的新数组或列表
   */
@SuppressWarnings("unchecked")
  public static Object merge(Object patchArray, Object oldArray) {
    if (patchArray instanceof Object[] && oldArray instanceof Object[]) {
      int patchLength = Array.getLength(patchArray);
      int oldLength = Array.getLength(oldArray);
      Object newArray =
          Array.newInstance(patchArray.getClass().getComponentType(), patchLength + oldLength);

      // 将补丁包数组放在前面
      System.arraycopy(oldArray, 0, newArray, 0, oldLength);

      System.arraycopy(patchArray, 0, newArray, oldLength, patchLength);

      return newArray;
    } else if (patchArray instanceof ArrayList && oldArray instanceof ArrayList) {
      ArrayList<Object> newList = new ArrayList<>((ArrayList<Object>) patchArray);
      newList.addAll((ArrayList<Object>) oldArray);
      return newList;
    } else {
      throw new IllegalArgumentException(
          "Both parameters must be of the same type and either arrays or ArrayLists.");
    }
  }
}
