package com.houvven.ktx_xposed.hook

import java.lang.reflect.Field

/**
 * Writes static fields, including `static final` ones, on modern Android.
 *
 * Plain [Field.set] throws [IllegalAccessException] for `public static final`
 * fields (e.g. `android.os.Build.BRAND`) on newer ART versions even after
 * `isAccessible = true`. `sun.misc.Unsafe` bypasses the access flags entirely
 * and is the technique used by most device-spoofing modules. Falls back to
 * plain reflection if Unsafe is unavailable.
 */
@PublishedApi
internal object StaticFieldWriter {

    private val unsafe: Any? by lazy {
        runCatching {
            val clazz = Class.forName("sun.misc.Unsafe")
            clazz.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }

    fun write(field: Field, value: Any?) {
        val u = unsafe
        if (u == null) {
            field.isAccessible = true
            field.set(null, value)
            return
        }
        val unsafeClass = u.javaClass
        val offset = unsafeClass
            .getMethod("staticFieldOffset", Field::class.java)
            .invoke(u, field) as Long
        val base = unsafeClass
            .getMethod("staticFieldBase", Field::class.java)
            .invoke(u, field)
        val long = Long::class.javaPrimitiveType!!
        when (field.type) {
            java.lang.Integer.TYPE -> unsafeClass
                .getMethod("putInt", Any::class.java, long, Int::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Long.TYPE -> unsafeClass
                .getMethod("putLong", Any::class.java, long, long)
                .invoke(u, base, offset, value)
            java.lang.Boolean.TYPE -> unsafeClass
                .getMethod("putBoolean", Any::class.java, long, Boolean::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Short.TYPE -> unsafeClass
                .getMethod("putShort", Any::class.java, long, Short::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Byte.TYPE -> unsafeClass
                .getMethod("putByte", Any::class.java, long, Byte::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Character.TYPE -> unsafeClass
                .getMethod("putChar", Any::class.java, long, Char::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Float.TYPE -> unsafeClass
                .getMethod("putFloat", Any::class.java, long, Float::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            java.lang.Double.TYPE -> unsafeClass
                .getMethod("putDouble", Any::class.java, long, Double::class.javaPrimitiveType)
                .invoke(u, base, offset, value)
            else -> unsafeClass
                .getMethod("putObject", Any::class.java, long, Any::class.java)
                .invoke(u, base, offset, value)
        }
    }
}
