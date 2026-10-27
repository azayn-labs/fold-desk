package dev.home.folddesk

/** Choices offered on the FoldDesk settings screen. */

/** Where the taskbar's icons sit along it: start (left, or top on a side bar) or centre. */
enum class Placement(val label: String) { Left("Start"), Center("Centre") }

/** Which screen edge the taskbar runs along. */
enum class TaskbarSide(val label: String) {
    Bottom("Bottom"), Left("Left"), Right("Right");

    val vertical get() = this != Bottom
}

enum class StatusAction(val label: String) {
    QuickSettings("Quick settings"),
    Battery("Battery"),
    Connections("Connections"),
}

enum class ClockAction(val label: String) {
    Notifications("Notifications"),
    Clock("Clock"),
    ShowDesktop("Show desktop (hide all windows)"),
    Calendar("Calendar"),
}

/** New windows as a fraction of the usable screen. */
enum class WindowSize(val label: String, val width: Float, val height: Float) {
    Small("Small", 0.45f, 0.62f),
    Medium("Medium", 0.62f, 0.82f),
    Large("Large", 0.8f, 0.92f),
}
