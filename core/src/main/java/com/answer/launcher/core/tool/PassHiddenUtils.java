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
        /*
         * The order of these lookups is load bearing, not cosmetic.
         *
         * HiddenApiBypass does not ask the runtime for a class's fields, it
         * walks ART's Class object with Unsafe: it reads the iFields / sFields
         * pointers at offsets it probed at class-init from a mirror class, then
         * reads the count at that pointer. When a probed offset lands on the
         * wrong word the value it reads back is not null but garbage, and the
         * next call is Unsafe.getInt(garbage) - a SIGSEGV that no try/catch can
         * turn into an exception, so the process just dies. That is what
         * happened here: the instance walk was fine, the static walk of
         * ActivityThread$AppBindData read 0x11 and killed :game inside
         * Application.onCreate.
         *
         * Every caller in this project wants an INSTANCE field of a framework
         * class, so ask the safe side first. Plain reflection comes next: it
         * covers static fields too, and the hidden-api exemptions installed in
         * AlterApplication / MinecraftLoader unfilter the framework members we
         * touch, so it resolves what the walks would. The static walk stays as
         * the last resort, only for names plain reflection cannot see.
         */
        Field field = search(getInstanceFields(clazz), name);
        if (field != null) return field;
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
        }
        if (includeStatic) {
            field = search(getStaticFields(clazz), name);
            if (field != null) return field;
        }
        throw new NoSuchFieldError(clazz.getName() + " couldn't find " + name);
    }

    private static Field search(List<Field> fields, String name) {
        for (Field field : fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }
}
