/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.3 · `Strings` 文案快照工具（测试专用，纯 JVM，无 Android 依赖）。
 *
 * ## 为什么需要它
 *
 * P0 要把 `Strings` 的 245 个主构造参数拆进功能分组。搬运是**纯机械**的，
 * 但机械搬运恰恰是最容易出错的一类改动：漏掉一条、把两条写串、某个语言文件少搬一行，
 * **都能编译通过**（Kotlin 的具名实参 + 有默认值的参数会让漏项静默）。1
 * 200 个字符串靠人眼 diff 是不可靠的。
 *
 * 所以做法是：拆分**前**先把 8 种语言的**全部可达文案**逐值导出成 JSON 黄金快照，
 * 拆分**后**再用同一套反射逻辑导出一次并逐值比对。这样「搬家 ≠ 改文案」是被
 * 机器证明的，而不是被声称的。
 *
 * ## 反射为什么能覆盖「两条访问路径」
 *
 * `Strings` 的分组搬家靠**类体里的转发属性**保住老调用点
 * （`val tagPlayable get() = tags.playable`）。转发属性也是 getter，
 * 所以反射会同时看到：
 *   · 扁平路径 `tagPlayable`            （**旧** key 访问路径）
 *   · 组路径   `tags.playable`          （**新** key 访问路径）
 * 两条都在快照里 ⇒ 拆分后可以同时断言「旧路径还在且值不变」与「新路径拿到同一个值」。
 *
 * ## lambda 文案怎么比
 *
 * `Strings` 里有 30+ 条是 `(String) -> String` / `(Int, Int) -> String` 这类**函数**，
 * 它们没有可比较的返回值。做法是：从 getter 的**泛型签名**读出形参类型，
 * 用固定的哨兵实参调用一次，把**输出字符串**记进快照。
 * 哨兵值刻意选成 `❰s1❱` 这种不会自然出现的样子 —— 万一某条文案被写成了
 * 不插值的形式（`"UID: x"`），快照里就会看出来参数丢了。
 */

package com.takahashirinta.ncrust.ui.i18n

import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/** 一条文案的「值」在快照里的表示。 */
object StringsSnapshot {

    private const val GROUP_PACKAGE = "com.takahashirinta.ncrust.ui.i18n"

    /**
     * 递归采集 [strings] 的全部可达文案。
     *
     * @return `路径 → 值`（有序，便于稳定落盘）。组内字段路径是 `组名.字段名`。
     */
    fun capture(strings: Strings): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        walk(strings, "", out, HashSet())
        return out
    }

    private fun walk(obj: Any, prefix: String, out: MutableMap<String, Any?>, seen: MutableSet<String>) {
        val clazz = obj.javaClass
        if (!seen.add(clazz.name + "#" + prefix)) return
        for (m in leafGetters(clazz)) {
            val name = prefix + propertyName(m)
            val value = runCatching { m.invoke(obj) }.getOrElse {
                val cause = it.cause ?: it
                out[name] = mapOf("t" to "throw", "v" to cause.javaClass.simpleName + ": " + cause.message)
                return@getOrElse Unit
            }
            if (value != null && isGroup(m)) {
                // 组对象本身记一条（用来断言「组参数确实挂上了」），再递归进去。
                out[name] = mapOf("t" to "group", "v" to m.returnType.simpleName)
                walk(value, "$name.", out, seen)
                continue
            }
            out[name] = encode(m, value)
        }
    }

    /** 该 getter 返回的是不是一个嵌套文案组。 */
    private fun isGroup(m: Method): Boolean =
        m.returnType.name.startsWith("$GROUP_PACKAGE.") &&
            m.returnType.simpleName.endsWith("Strings")

    private fun propertyName(m: Method): String {
        val raw = m.name.removePrefix("get")
        return raw.replaceFirstChar { it.lowercaseChar() }
    }

    /** 只看 `getXxx()` 形状的无参 getter；剔掉 `getClass` 与 Kotlin 的 `$annotations` 合成方法。 */
    private fun leafGetters(clazz: Class<*>): List<Method> =
        clazz.methods
            .filter {
                it.parameterCount == 0 &&
                    it.name.length > 3 &&
                    it.name.startsWith("get") &&
                    it.name != "getClass" &&
                    !it.name.contains("$")
            }
            .sortedBy { it.name }

    private fun encode(m: Method, value: Any?): Map<String, Any?> = when (value) {
        null -> mapOf("t" to "null", "v" to null)
        is String -> mapOf("t" to "s", "v" to value)
        is Boolean -> mapOf("t" to "b", "v" to value)
        is Int -> mapOf("t" to "i", "v" to value)
        is Long -> mapOf("t" to "n", "v" to value)
        is List<*> -> mapOf("t" to "l", "v" to value.map { it?.toString() })
        else -> {
            val arity = functionArity(m)
            if (arity > 0) {
                val args = probeArgs(m, arity)
                val res = runCatching { invokeN(value, args) }
                mapOf(
                    "t" to "fn",
                    "arity" to arity,
                    "args" to args.map { it.toString() },
                    "v" to (res.getOrNull()?.toString()
                        ?: ("THROWS:" + (res.exceptionOrNull()?.message ?: "?"))),
                )
            } else {
                mapOf("t" to "other", "v" to value.toString())
            }
        }
    }

    // ---- 函数类型：从泛型签名读形参类型，用哨兵实参调用一次 ----

    private fun functionArity(m: Method): Int {
        val gen = m.genericReturnType as? ParameterizedType ?: return 0
        if (!gen.rawType.typeName.startsWith("kotlin.jvm.functions.Function")) return 0
        return (gen.actualTypeArguments.size - 1).coerceAtLeast(0)
    }

    private fun probeArgs(m: Method, arity: Int): List<Any?> {
        val gen = m.genericReturnType as ParameterizedType
        return (0 until arity).map { i ->
            when (gen.actualTypeArguments[i].typeName) {
                "java.lang.String" -> "❰s${i + 1}❱"
                "java.lang.Integer", "int" -> 4242 + i
                "java.lang.Long", "long" -> 4242L + i
                "java.lang.Boolean", "boolean" -> true
                else -> "❰x${i + 1}❱"
            }
        }
    }

    private fun invokeN(fn: Any, args: List<Any?>): Any? = when (args.size) {
        1 -> (fn as kotlin.jvm.functions.Function1<Any?, Any?>).invoke(args[0])
        2 -> (fn as kotlin.jvm.functions.Function2<Any?, Any?, Any?>).invoke(args[0], args[1])
        3 -> (fn as kotlin.jvm.functions.Function3<Any?, Any?, Any?, Any?>)
            .invoke(args[0], args[1], args[2])
        4 -> (fn as kotlin.jvm.functions.Function4<Any?, Any?, Any?, Any?, Any?>)
            .invoke(args[0], args[1], args[2], args[3])
        else -> error("unsupported arity ${args.size}")
    }
}
