package net.dexxicon.reader.core.model

/** How strong the floating pill nav's liquid-glass blur/tint/saturation effect is (issue #224).
 * [OFF] keeps a flat tint with no blur allocation at all, not just a scaled-down one - the
 * meaningful "turn this off entirely" choice for a user on a low-end device or who just
 * doesn't like the look, distinct from a device's own hardware-tier auto-fallback which a user
 * has no direct control over. Lives in `core:model`, not `core:datastore`, since it needs to be
 * a public parameter type on `core:designsystem`'s `FloatingPillNavBar` and that module has no
 * dependency on `core:datastore` - same reasoning as `BookViewMode`/`BookSort` living here. */
enum class GlassIntensity { OFF, SUBTLE, STANDARD, STRONG }
