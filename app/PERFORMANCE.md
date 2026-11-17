# Performance maintenance, 0.4.6

The requested constraint is to preserve all animation timings, easing, trajectories, shaders, blur materials and gesture effects.

Changes:
- Suppress unchanged Room query snapshots before record conversion. Move timeline/search/pinned/relations/reminder-list conversion and filtering to Dispatchers.Default.
- Use an extension lookup index for file classification, preserving extension precedence and MIME fallback.
- Remember reversed list views, timestamp-group decisions, non-quote link lists and formatted attachment labels by their data/configuration keys.
- Storage scans and file-presence checks remain fresh: do not deduplicate them by database rows alone, because the filesystem can change independently.

Verification: compare protected source SHA-256 hashes against build/qa/performance/protected-hashes.json, and compare animation declarations in the two edited UI files. Unit/UI regressions include unchanged pinned-query suppression and delivery of a real pin change.

No physical-device frame trace was captured. Reduced work is supported by code inspection and functional regression, not a measured FPS improvement. No animation durations/effects are reduced for performance.
