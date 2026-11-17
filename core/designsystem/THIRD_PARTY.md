# NexioSchedule UI

The NexioSchedule components in this module come from [commit 35503185](https://github.com/HaoZai000/NexioSchedule/tree/35503185bc42bf456c03a4c59dea0d22b2b1d681). See [LICENSE](LICENSE) for the AGPL-3.0 terms. This module uses the public MiUiX icon artifact separately.

Adapted for YINU on 2026-11-05. The Android wrapper binds the floating tabs to YINU destinations, injects the light theme and material fallback, and reuses the upstream drag, shape, shader, and press implementations. The progressive top bar reads the YINU theme. The Android shadow implementation is compiled directly.

The app's settings transition reuses the two easing curves from [OobeMotion.kt](https://github.com/HaoZai000/NexioSchedule/blob/35503185bc42bf456c03a4c59dea0d22b2b1d681/ui-shared/src/commonMain/kotlin/com/haooz/chedule/ui/effects/motion/OobeMotion.kt), credited to HyperCeiler Contributions and licensed under AGPL-3.0-only.

The attachment and message menus also use NexioSchedule's LiquidGlassDropdownMenu, ShortcutMenu and the forked MiUiX drag-selection implementation from the same commit. YINU adapts the anchor direction, viewport positioning, theme and back handling.

The deletion confirmation uses the phone layout and motion from the forked MiUiX `DialogContentLayout.kt`, `DecelerateEasing.kt` and `Button.kt` (Apache-2.0), with the paired action layout from NexioSchedule's `CourseScheduleDialogs.kt` (AGPL-3.0-only). YINU supplies the text, callbacks, and root overlay host.

The search date sheet follows the jump-to-date layout in NexioSchedule's `TodayScreen.kt` and reuses the forked MiUiX `NumberPicker.kt` (Apache-2.0). YINU supplies theme colors, accessibility actions, and calendar-safe date selection.

The search page uses the forked MiUiX `SearchBar.kt` and `Card.kt` (Apache-2.0), with the filter sizing and arrangement from NexioSchedule's `EducationalImportScreen.kt` and the empty state from `SchoolSelectionScreen.kt`. The Android adaptation binds focus and cancellation to YINU's dock and search state.
