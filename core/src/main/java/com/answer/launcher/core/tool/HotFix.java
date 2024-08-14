package com.answer.launcher.core.tool;

import android.content.Context;
import com.answer.launcher.core.Constants;
import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * This class provides methods for applying hotfixes by merging dex elements and native library
 * directories from a patch ClassLoader into the application's ClassLoader.
 */
public class HotFix {

  /**
   * Applies hotfix by merging dex elements and native library directories from the patch
   * ClassLoader into the application's ClassLoader.
   *
   * @param context the application context
   * @param patchClassLoader the ClassLoader containing the patch
   */
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
    
    public static void HotFixNative(Context origin, String plugin){
        try {
        	Object pathList = getPathList(origin.getClassLoader());
        Reflector.on(pathList.getClass())
          .method("addNativePath", Collection.class)
          .callByCaller(
              pathList, Collections.singletonList(plugin));
        } catch(Exception err) {
        	err.printStackTrace();
        }
    }
    
    
  /**
   * Gets the value of a field from an object using reflection.
   *
   * @param obj the object containing the field
   * @param clazz the class of the object
   * @param fieldName the name of the field
   * @return the value of the field
   */
  private static Object getField(Object obj, Class<?> clazz, String fieldName) {
    try {
      return Reflector.on(clazz).field(fieldName).get(obj);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Sets the value of a field in an object using reflection.
   *
   * @param obj the object containing the field
   * @param clazz the class of the object
   * @param fieldName the name of the field
   * @param value the new value to set
   */
  private static void setField(Object obj, Class<?> clazz, String fieldName, Object value) {
    try {
      Reflector.on(clazz).field(fieldName).set(obj, value);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Gets the pathList object from a BaseDexClassLoader.
   *
   * @param classLoader the BaseDexClassLoader
   * @return the pathList object
   */
  private static Object getPathList(ClassLoader classLoader) {
    try {
      return getField(classLoader, Class.forName("dalvik.system.BaseDexClassLoader"), "pathList");
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Gets the dexElements array from a ClassLoader.
   *
   * @param classLoader the ClassLoader
   * @return the dexElements array
   */
  private static Object getDexElements(ClassLoader classLoader) {
    Object pathList = getPathList(classLoader);
    return getField(pathList, pathList.getClass(), "dexElements");
  }

  /**
   * Gets the nativeLibraryDirectories list from a ClassLoader.
   *
   * @param classLoader the ClassLoader
   * @return the nativeLibraryDirectories list
   */
  private static Object getNativeLibraryDirectories(ClassLoader classLoader) {
    Object pathList = getPathList(classLoader);
    return getField(pathList, pathList.getClass(), "nativeLibraryDirectories");
  }

  private static void setDexElements(Object pathList, Object newElements) throws Exception {
    setField(pathList, pathList.getClass(), Constants.DEX_ELEMENTS, newElements);
  }
    
  private static void setNativeLibraryDirectories(Object pathList, Object newDirs)
      throws Exception {
    setField(pathList, pathList.getClass(), Constants.NATIVE_LIBRARY_DIRECTORIES, newDirs);
  }
  /**
   * Merges two arrays or ArrayLists.
   *
   * @param array1 the first array or ArrayList
   * @param array2 the second array or ArrayList
   * @return the merged array or ArrayList
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
