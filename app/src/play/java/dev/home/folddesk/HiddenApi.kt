package dev.home.folddesk

/**
 * Plain reflection: Google Play doesn't allow lifting Android's hidden API
 * restriction, so calls Android blocks throw and their callers fall back.
 */
object HiddenApi {
    fun invoke(clazz: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method = clazz.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
