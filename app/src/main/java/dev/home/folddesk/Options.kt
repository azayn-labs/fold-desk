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

/** What a vertical swipe on the empty desktop does. */
enum class SwipeAction(val label: String) {
    Drawer("App drawer"),
    Notifications("Notifications"),
    QuickSettings("Quick settings"),
    None("Nothing"),
}

/** Taskbar background material. */
enum class TaskbarBackground(val label: String) { Solid("Solid"), Translucent("Translucent"), Blurred("Blurred") }

/** Taskbar thickness and icon size, in dp. */
enum class TaskbarSize(val label: String, val bar: Int, val sideBar: Int, val keyW: Int, val keyH: Int, val icon: Int) {
    Compact("Compact", 40, 48, 36, 32, 24),
    Default("Default", 48, 56, 44, 40, 32),
    Large("Large", 58, 66, 54, 50, 40),
}

/** A named taskbar colour + accent pair (see design/v012-colours.html). */
data class ColourPreset(val name: String, val bar: Long, val accent: Long)

val ColourPresets = listOf(
    ColourPreset("Midnight", 0xFF141A2E, 0xFF3E91FF),
    ColourPreset("Graphite", 0xFF1C1C1E, 0xFFA8A8AD),
    ColourPreset("Forest", 0xFF13261C, 0xFF34C759),
    ColourPreset("Sunset", 0xFF2E1A17, 0xFFFF9F0A),
    ColourPreset("Grape", 0xFF231633, 0xFFAF52DE),
    ColourPreset("Rose", 0xFF2A1520, 0xFFFF375F),
    ColourPreset("Ocean", 0xFF0F2A33, 0xFF32D2C8),
    ColourPreset("Snow", 0xFFF2F2F6, 0xFF0A7CFF),
)

val CustomBarColours = listOf(0xFF0F0F12, 0xFF1C1C1E, 0xFF141A2E, 0xFF13261C, 0xFF2E1A17, 0xFF231633, 0xFF0F2A33, 0xFFF2F2F6)
val CustomAccents = listOf(0xFF3E91FF, 0xFF32D2C8, 0xFF34C759, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF375F, 0xFFAF52DE, 0xFFA8A8AD)
