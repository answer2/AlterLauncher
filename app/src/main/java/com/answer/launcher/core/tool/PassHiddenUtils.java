package com.answer.launcher.core.tool;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;

import static org.lsposed.hiddenapibypass.HiddenApiBypass.*;


/**
 * @Author AnswerDev
 * @Date 2024/07/09 16:26
 * @Library LSPosed 
 */
public class PassHiddenUtils {

    @SuppressWarnings("unchecked")
    public static List<Field> getDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        fields.addAll(getInstanceFields(clazz));
        fields.addAll(getStaticFields(clazz));
        return fields;
    }

    public static Field getField(Class<?> clazz, String name) {
        return findField(clazz, name, false);
    }

    public static Field getDeclaredField(Class<?> clazz, String name) {
        return findField(clazz, name, true);
    }

    @SuppressWarnings("unchecked")
    private static Field findField(Class<?> clazz, String name, boolean includeStatic) {
        List<Field> fields = includeStatic ? getDeclaredFields(clazz) : getInstanceFields(clazz);
        for (Field field : fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        throw new NoSuchFieldError(clazz.getName() + " couldn't find " + name);
    }
}
