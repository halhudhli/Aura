# Aura V2 — Implementation checkpoints

Scope: the approved Glass UI / MagicPad 3 design and the staged roadmap in the design handoff.
Work continues on the existing review branch; existing subtitle/player features must remain available.

| Stage | State | Acceptance |
|---|---|---|
| 1. Incoming media / tablet foundation | Implemented; final verification | VIEW/SEND/multiple files, URI access, modern permissions, open without full library permission |
| 2. Home / files / animated wallpapers | Implemented; final verification | Responsive home, real library counts, sorting/filtering, still/GIF/WebP/video wallpapers, moving mountain default |
| 3. Audio / queues | Implemented; final verification | Media session, foreground/background audio, notification/lock-screen controls, saved queues, EQ |
| 4. M3U / IPTV / EPG | Implemented; final verification | File/URL playlists, HLS distinction, channel groups/favorites/logos, XMLTV, reconnect |
| 5. Advanced video tools | Implemented; final verification | Local seek previews, two-finger zoom/pan, chapter/queue navigation, bookmark notes, saved effects |
| 6. Advanced subtitles / AutoSync | Implemented; final verification | Fonts, language preferences, secondary subtitle, SDH, offset/drift fitting, confidence gate/cancellation; embedded text extraction where Android supports it |
| 7. Network / casting | Implemented; device interoperability pending | SMB2/3, SFTP host-key verification, FTP/FTPS certificate verification, range relay; DLNA discovery/transport and selected local-file serving |
| 8. Configured services | Implemented; own account required | Gemini text translation/companion and Xtream live/VOD/series/episodes; no personal service credentials in source |

Each checkpoint requires suitable checks and a successful APK build. Physical-device behavior is reported separately from unit/compile checks. Service-dependent features require the user's own configured source/account.

Previous local checkpoint: 71 tests, 0 failures, 1 optional live-service skip; lint and APK build passed. Final checks and API 35 UI smoke evidence will be recorded after the final source snapshot is built.

Interoperability limits: no Chromecast protocol claim; DLNA local-file delivery requires Aura to remain open. No voice recognition/OCR claim for AutoSync. Bitmap subtitle extraction, DRM bypass, provider-specific intro timestamps and transcoding for incompatible receivers are outside the implemented path. HDR output follows mpv/device capabilities; there is no claim of device-certified HDR. The app does not fabricate available channels, episodes, servers, or AI responses.
