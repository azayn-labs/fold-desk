package dev.home.folddesk

import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Calls methods Android hides from apps; the GitHub build lifts the restriction. */
object HiddenApi {
    fun invoke(clazz: Class<*>, target: Any?, name: String, vararg args: Any?): Any? =
        HiddenApiBypass.invoke(clazz, target, name, *args)
}
