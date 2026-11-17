# Message motion

Reference study: Telegram Android's [TextMessageEnterTransition](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java), [ChatListItemAnimator](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java), and [ThanosEffect](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/ThanosEffect.java), inspected 2026-11-08.

YINU's implementation is original; it does not bundle Telegram's source files or shaders. Telegram's View/RecyclerView transitions and GLES 3 transform-feedback implementation are not drop-in Compose components.

- Sending associates transient input geometry with the new record ID before the Room commit. A Compose display-list overlay flies from this origin to the measured message anchor, with separate horizontal and vertical easing. The destination follows composer resizing. Old IDs, offscreen records and restored screens do not replay an old send.
- Deletion captures a bounded message snapshot once. A GLES 2 renderer in a non-interactive ViewGroupOverlay draws intact pixels until each cell detaches, then independently moves and fades textured particles. The first frame must preserve the original bitmap. Particle positions are analytical, with no per-frame CPU image processing.
- GPU limits: eight visible captures, 1.1 megapixels per capture, and approximately 52,000 particles per renderer. Low-memory/unsupported devices and rendering failures use a cheap fade. EGL, buffers, textures, bitmaps and overlays are released when finished or disposed. System-disabled animations skip the effect.
- Lazy item placement animates gap closing. Graphics effects do not alter the stored record, undo payload or backup format.

`build/qa/validate_particles.py` compiles the production shaders against an installed ANGLE runtime and verifies intact, partial and transparent frames. Its GIF is a shader test fixture, not a phone recording. Device compositing and perceived timing still need physical-device validation.
