package com.zqr.freenote.util

/** SQL LIKE 通配符转义工具。 */
object SqlLike {
    /**
     * 转义用户输入中的 LIKE 通配符，配合 SQL 中的 `ESCAPE '\'` 使用，
     * 使搜索按字面匹配（例如输入 "100%" 不会匹配所有便签）。
     */
    fun escape(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
