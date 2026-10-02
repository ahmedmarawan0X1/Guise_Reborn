package com.houvven.ktx_xposed.hook

import com.houvven.ktx_xposed.logger.XposedLogger
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Writes static fields, including `static final` ones, across Android versions.
 *
 * Android 17 removed most of `sun.misc.Unsafe` (e.g. `staticFieldOffset`), and plain
 * [Field.set] rejects `public static final` fields (e.g. `android.os.Build.BRAND`)
 * on modern ART even after `isAccessible = true`. This writer probes several
 * strategies at runtime and uses the first one that works:
 *
 * 1. `sun.misc.Unsafe` staticFieldOffset/staticFieldBase + putXxx (older Android);
 * 2. Clear the FINAL bit in the field's `accessFlags` via reflection, then [Field.set]
 *    (requires hidden-API exemption on newer Android);
 * 3. Plain [Field.set] (oldest Android).
 *
 * Which strategy succeeded is logged once per process for diagnostics.
 */
@PublishedApi
internal object StaticFieldWriter {

    private val unsafe: Any? by lazy {
        runCatching {
            val clazz = Class.forName("sun.misc.Unsafe")
            clazz.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }

    /** 1 = Unsafe static write, 2 = accessFlags clear + Field.set, 3 = plain Field.set */
    @Volatile
    private var workingStrategy: Int = 0

    fun write(field: Field, value: Any?) {
        when (workingStrategy) {
            1 -> if (tryUnsafeWrite(field, value)) return
            2 -> if (tryAccessFlagsWrite(field, value)) return
            3 -> if (tryPlainWrite(field, value)) return
        }
        for (strategy in 1..3) {
            val ok = when (strategy) {
                1 -> tryUnsafeWrite(field, value)
                2 -> tryAccessFlagsWrite(field, value)
                else -> tryPlainWrite(field, value)
            }
            if (ok) {
                if (workingStrategy == 0) {
                    workingStrategy = strategy
                    XposedLogger.i("StaticFieldWriter: using strategy $strategy")
                }
                return
            }
        }
        XposedLogger.e("StaticFieldWriter: all strategies failed for $field")
    }

    // region Strategy 1: sun.misc.Unsafe (removed in Android 17)

    private fun tryUnsafeWrite(field: Field, value: Any?): Boolean {
        val u = unsafe ?: return false
        return runCatching {
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
        }.isSuccess
    }

    // endregion

    // region Strategy 2: clear FINAL via Field.accessFlags, then Field.set

    private val hiddenApiExempted: Boolean by lazy {
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
            vmRuntimeClass
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("L"))
        }.isSuccess
    }

    private val accessFlagsField: Field? by lazy {
        if (!hiddenApiExempted) return@lazy null
        runCatching {
            Field::class.java.getDeclaredField("accessFlags").apply { isAccessible = true }
        }.getOrNull()
    }

    private fun tryAccessFlagsWrite(field: Field, value: Any?): Boolean {
        val accessFlags = accessFlagsField ?: return false
        return runCatching {
            val original = accessFlags.getInt(field)
            accessFlags.setInt(field, original and Modifier.FINAL.inv())
            field.isAccessible = true
            field.set(null, value)
            accessFlags.setInt(field, original)
        }.isSuccess
    }

    // endregion

    // region Strategy 3: plain Field.set (works on older ART)

    private fun tryPlainWrite(field: Field, value: Any?): Boolean = runCatching {
        field.isAccessible = true
        field.set(null, value)
    }.isSuccess

    // endregion
}
