package com.answer.launcher.core.tool;

import android.content.Context;
import com.answer.launcher.core.LauncherConstants;
import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * This class provides methods for applying hotfixes by merging dex elements and native library
 * directories from a patch ClassLoader into the application's ClassLoader.
 */
public class HotFix {
  public static final String DEX_ELEMENTS = "dexElements";
  public static final String NATIVE_LIBRARY_DIRECTORIES = "nativeLibraryDirectories";

  /**
   * Applies hotfix by merging dex elements and native library directories from the patch
   * ClassLoader into the application's ClassLoader.
   *
   * @param context the application context
   * @param loader the ClassLoader containing the patch
   */
  public static void hotFix(Context context, ClassLoader loader) {
    try {
      Object patchDexElements = getDexElements(loader);
      Object oldDexElements = getDexElements(context.getClassLoader());
      Object newDexElements = merge(patchDexElements, oldDexElements);

      Object pathList = getPathList(context.getClassLoader());
      setDexElements(pathList, newDexElements);

      // 获取原始和补丁 native so 路径
      Object patchNativeLibraryDirs = getNativeLibraryDirectories(loader);
      Object oldNativeLibraryDirs = getNativeLibraryDirectories(context.getClassLoader());
      Object newNativeLibraryDirs = mergeBefore(patchNativeLibraryDirs, oldNativeLibraryDirs); // patch 优先

      setNativeLibraryDirectories(pathList, newNativeLibraryDirs);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
    
    public static void hotFixNative(Context origin, String plugin){
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


  public static boolean addDexPath(ClassLoader classLoader, String dexPath, File optimizedDirectory){
    try {
      Object pathList = getPathList(classLoader);
      Reflector.on(pathList.getClass())
              .method("addDexPath", String.class, File.class)
              .callByCaller(pathList, dexPath, optimizedDirectory);
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
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
    setField(pathList, pathList.getClass(), DEX_ELEMENTS, newElements);
  }
    
  private static void setNativeLibraryDirectories(Object pathList, Object newDirs)
      throws Exception {
    setField(pathList, pathList.getClass(), NATIVE_LIBRARY_DIRECTORIES, newDirs);
  }
  /**
   * Merges two arrays or ArrayLists.
   *
   * @param patchArray the first array or ArrayList
   * @param oldArray the second array or ArrayList
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


  /**
   * 合并两个数组或 ArrayList，patch 元素优先（放在前面）
   *
   * @param patchArray patch 补丁数据（优先加载）
   * @param oldArray   原始数据
   * @return 合并后的新数组或列表
   */
  @SuppressWarnings("unchecked")
  public static Object mergeBefore(Object patchArray, Object oldArray) {
    if (patchArray instanceof Object[] && oldArray instanceof Object[]) {
      int patchLength = Array.getLength(patchArray);
      int oldLength = Array.getLength(oldArray);
      Object newArray = Array.newInstance(patchArray.getClass().getComponentType(), patchLength + oldLength);

      // patch 放前面，优先加载
      System.arraycopy(patchArray, 0, newArray, 0, patchLength);
      System.arraycopy(oldArray, 0, newArray, patchLength, oldLength);

      return newArray;

    } else if (patchArray instanceof ArrayList && oldArray instanceof ArrayList) {
      ArrayList<Object> newList = new ArrayList<>((ArrayList<Object>) patchArray);
      newList.addAll((ArrayList<Object>) oldArray);
      return newList;

    } else {
      throw new IllegalArgumentException("Both parameters must be of the same type and either arrays or ArrayLists.");
    }
  }
}
