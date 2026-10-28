package dev.home.folddesk

/**
 * Methods Android hides from apps, called by plain reflection. Calls Android
 * blocks throw and their callers fall back: One UI still opens apps in windows
 * from the launch bounds alone, and Samsung's blur isn't on the blocked list.
 */
object HiddenApi {
    fun invoke(clazz: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method = clazz.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
