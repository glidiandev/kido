# Changes in the Spanish edition

A summary for the original author ([glidiandev/kido](https://github.com/glidiandev/kido)). Everything was
built and run on a Xiaomi Mi TV P1 (MiTV-MOOQ0, Android TV 10, MediaTek m7632, 2 GB RAM) and checked with
screenshots pulled from the TV. The version stays at `0.1`; nothing here adds a network permission.

Base commit: `f4004bb` of glidiandev/kido.

## Interface

- **Spanish UI.** Every English label is now Spanish (about 90 strings). The Japanese kanji are kept.
  Each label was measured against the bundled fonts so it fits its fixed slot; two were shortened
  ("ESPERA", "INICIANDO"). Dates use the Spanish locale (`MIÉ 21 OCT 2026`).
- **Second section renamed** to `アプリ APLICACIONES` / tab `APPS` (kanji アプリ, available in the Mincho
  subset). Tabs now take their kanji from a `TAB_KANJI` array instead of `substring(0, 2)`.
- **Section frame lowered** (y 400 → 432) so the section titles no longer sit on its top line; the ruler
  moved with it.
- **Long names in the status block** stay on one line and are shortened with "…" before the `// CH.xx` part.
- **Footer clock.** The footer starts with a clock that ticks every second, followed by the log lines
  (which no longer carry their own timestamps).

## Honeycomb

- **Scrolls by rows.** Only two full rows show at a time; the honeycomb slides up when focus moves to a
  lower row, rows above fade out under the title, and a `▲ ▼ MÁS CANALES` hint shows when there is more.
  Before, the third row was cut off by the bottom of the screen.
- **The link status list scrolls** to keep the focused channel visible (it sits in a non-focusable
  ScrollView used only for measuring and clipping).
- **The glow travels in a straight line** (X and Y together, diagonals included) and moves with the
  honeycomb when it scrolls.
- **Fluid motion.** A decelerating curve (`PathInterpolator(0.2, 0, 0, 1)`) and shorter durations for the
  section pan (800 → 620 ms), honeycomb scroll and glow; hexes animate with a hardware layer.
- **Up from the first row** goes back to the main panels.

## Moving apps (MOVER)

- The options menu now has a single **MOVER** entry (instead of "move left/right"). The tile's glow turns
  red and pulses; the arrows move it and the other tiles slide to make room. OK keeps the order, Back cancels.
- Works on the five main panels and in the honeycomb (Up/Down change rows). The honeycomb order is saved
  (`order` preference); new apps go last, alphabetically.
- **Up from the honeycomb's first row while moving** enters the main panels: Left/Right pick which of the
  five to replace, OK swaps them (the replaced app takes the moved app's place in the honeycomb).
- `rebuild()` keeps you in the section of the focused app instead of panning over from the main panels.

## Inputs

- **HDMI-CEC device names.** A CEC device is its own `TvInputInfo` whose parent is the HDMI port. Only the
  ports become boxes now (before, a CEC child showed up as an extra "port"), and each port box shows the
  device name (e.g. `PLAYSTATION 5`) with the port underneath, or the custom name given in the TV's input
  settings. A port with a CEC device counts as `CONECTADO` even while the device is in standby. Names refresh
  live (`onInputAdded/Removed/Updated`, `onTvInputInfoUpdated`), and the `ENTRADA DETECTADA` alert fires
  when a new CEC device appears and shows its name.
- **Inputs panel** (`InputPanelActivity`): a side panel in KIDŌ's style listing HDMI ports (with CEC names),
  AV and TV, opened over any app. It also answers `com.android.tv.action.VIEW_INPUTS`.
- **Input button helper** (`InputKeyService`, opt-in, enabled over ADB): an accessibility service that
  only filters `KEYCODE_TV_INPUT` / `KEYCODE_MEDIA_RECORD` and reads no window content. On this TV the
  Input button is a global key whose handler (`partnercustomizer`) launches
  `com.google.android.tvlauncher/.inputs.InputsPanelActivity` by explicit component, so once the stock
  launcher is disabled the button does nothing without the helper.
- **Known trade-off:** while any key-filtering accessibility service is bound, Android's
  `KeyboardInterceptor` handles volume keys before dispatch and the dispatcher no longer synthesizes repeats,
  so a held volume key steps once. Arrow repeats are not affected. Verified on the P1 four times (helper on →
  one step, off → continuous). The service never receives volume events, so it cannot compensate. Hence a
  switch in the Settings menu (`BOTÓN INPUT: SÍ / NO`), which needs `WRITE_SECURE_SETTINGS` granted once
  over ADB.

## App catalog, categories and colors

- New catalog entries: YouTube Music, YouTube Kids, the Xiaomi screensaver app (`com.mitv.dream`, shown as
  "Fondos Mi"). Disney+ is now `番組 SERIES`.
- **Categories for unknown apps**, by keywords in the label and package and then by
  `ApplicationInfo.category`: CINE, SERIES, FAMILIA, VIDEO, ANIME, MÚSICA, JUEGOS, TIENDA, REPRODUCTOR,
  NOTICIAS, FOTOS, PROYECCIÓN, TV EN VIVO, HERRAMIENTAS, NAVEGADOR, DEPORTES. Only kanji present in the
  Mincho subset are used (it carries about 100), so some are approximations (情報 for news).
- **Colors from the logo** for apps not in the catalog: the banner (or icon) is drawn into a 64×36 bitmap,
  vivid pixels are grouped into 24 hue buckets weighted by saturation × value, and the strongest bucket's
  average color is used, lifted for a dark background. Grey/white/black logos fall back to the category
  color. Results are cached per package and APK timestamp.

## Files

- New: `InputPanelActivity.java`, `InputKeyService.java`, `res/xml/input_key_service.xml`.
- Changed: `KidoActivity.java`, `Kido.java`, `KidoDream.java`, `AndroidManifest.xml`, `strings.xml`,
  `themes.xml`, `lint.xml`, `README.md` (now in Spanish), plus three screenshots in `docs/`.
