# MeshForge — UI Art Direction & Asset Specification

**Status:** approved visual direction / implementation brief  
**Platform:** Android  
**Theme:** Carpenter Workshop × Forge Hearth  
**Reference:** `meshforge_carpenter_forge_ui_board.png` (approved concept sheet)  
**Brand:** MeshForge · Firmware forge for MeshCore  
**Author:** WarDaddy 🪖

## 1. Visual direction

Combine the warm, medium-brown and orange wooden surfaces of **Carpenter Workshop** with the blacksmith assets of **Forge Hearth**: a lit brick furnace, steel anvil, hammer, sparks and workshop tools. The result is a practical firmware utility that feels like a friendly craftsman's workshop, not a hacker console or a fantasy game.

- The main app background is warm honey-oak / chestnut wood, not white, cream, black or dark blue. Brown and orange must be visibly dominant on exposed background areas.
- Use light parchment-coloured cards for technical information; dark walnut wooden headers and navigation; gold primary actions.
- Retain the illustrated Meškoriai-inspired bear and bees. Use the reference's bold ink outlines, warm comic shading, tactile illustrated materials and restrained highlights.
- The bear wears sunglasses and a leather blacksmith apron. His head is uncovered: no raised hood, hat or helmet. Helper bees actively hold the golden mesh while the bear forges it.
- Keep the bear on the right, facing left, bees to the left holding one continuous mesh across the anvil. The bear's far arm holds a conventional straight-handled blacksmith hammer at the bottom of its strike; the near hand steadies the work. The scene is not a battle pose.
- Use furnace, anvil and forge sparks as thematic assets instead of generic forest vistas, neon circuitry or unrelated carpentry scenes.
- Preserve existing app behavior, information hierarchy and Android navigation. The artwork supports the utility; it must not obstruct it.

## 2. Palette

The eight brand colours below are transcribed from the final generated concept sheet. They are design tokens, not a claim about every individual pixel in the illustration.

| Token | Colour | HEX | Usage |
|---|---|---|---|
| `honeyOak` | Honey Oak | `#D7983A` | Main warm wood background; texture highlights |
| `walnutBrown` | Walnut Brown | `#7B4A21` | Wood bars, navigation and structural trim |
| `amber` | Amber | `#E6A241` | Wood highlights, secondary warm accents |
| `saddleLeather` | Saddle Leather | `#8B4E2B` | Leather accents and decorative trim |
| `parchmentCream` | Parchment Cream | `#F9E9D0` | Content cards, inputs and lists |
| `brassYellow` | Brass Yellow | `#FFC93C` | Main buttons, selected tabs, progress emphasis |
| `forgeOrange` | Forge Orange | `#FF7A00` | Sparks, furnace, highlights and in-progress decoration |
| `deepBrown` | Deep Brown | `#3E2614` | Dark wood shadows, text on light backgrounds |

### Derived UI tokens (implementation proposals)

These supporting colours are intentionally added to make the concept suitable for a real app; they are not printed swatches from the reference.

| Token | HEX | Usage |
|---|---|---|
| `textPrimary` | `#2B1B10` | Main text on light surfaces |
| `textSecondary` | `#65472F` | Secondary text on parchment |
| `textOnWood` | `#FFF4DD` | Text on dark wooden bars |
| `surfaceRaised` | `#FFF1DB` | Dialogs and raised content |
| `surfaceMuted` | `#EED6B3` | Dividers, inactive controls and card variation |
| `outline` | `#95633A` | Card outlines and control borders |
| `success` | `#236B27` | Connected, verified, completed |
| `successContainer` | `#D9EBC1` | Success messages on light surfaces |
| `warning` | `#98420B` | Warning text, not decorative orange |
| `warningContainer` | `#FFE0AE` | Warning panels |
| `error` | `#A32120` | Failures, destructive actions |
| `errorContainer` | `#FADBD3` | Error panels |
| `steel` | `#555A56` | Anvil and metal tools |

### Colour application

- Screen canvas: honey oak, with warm illustrated wooden planks. Avoid darkening the entire background into chocolate or black.
- Header / bottom navigation: walnut brown with deeper brown edges; light cream labels. Selected navigation item: brass yellow with deep brown text/icon.
- Cards: parchment cream, dark brown text, thin brown border, modest corners and small corner fasteners if appropriate.
- Primary CTA: brass yellow with deep brown text; a restrained amber shadow or pressed edge. Never use white text on yellow.
- Success banner: dark green with cream text, or pale green with dark green text. Connection status is always conveyed by an icon and wording as well as colour.
- Flashing: use a green or brass progress fill with readable percentage, and sparks as optional artwork. Orange is decorative or supplemental, not the only progress indicator.
- Error/destructive: use explicit red semantics, explanatory text and confirmations; do not confuse warm branding with a warning.
- Ensure WCAG AA contrast (4.5:1 normal text, 3:1 large text and essential non-text boundaries) against actual composited backgrounds. Test the texture treatment, not just solid token pairs. Use opaque backing behind text when needed.
- Use this fixed palette by default; disable dynamic wallpaper colour overrides for the branded theme. Do not automatically switch it to a black/dark theme based on system mode.

## 3. Asset list

This is a production checklist, **not a delivered asset pack**. Suggested master dimensions are starting points. All illustrated assets must be created/exported separately from the concept sheet so that no mockup labels or sample data become part of the app.

| ID / filename | Contents | Suggested master / format | Use | Priority |
|---|---|---|---|---|
| `meshforge_logo` | Full bear/anvil/bees emblem and MESH FORGE title | 1024×1024 transparent PNG | About, brand splash, promotional art | P0 |
| `launcher_foreground`, `launcher_background` | Compact bear, bee, hammer/anvil motif; no tiny text | Android adaptive icon layers, 108dp canvas; 432×432 raster master if needed | Launcher | P0 |
| `mascot_header` | Uncovered bear head, apron, hammer and assisting bees, compact silhouette | 768×512 transparent PNG/WebP | Home header | P0 |
| `wood_background_tile` | Seamless honey-oak / chestnut plank texture, restrained grain | 512×512 or 1024×1024 lossless | App background | P0 |
| `wood_bar_texture` | Darker walnut planks for header and navbar | 1024×256 seamless horizontal / WebP | App bars | P0 |
| `parchment_texture` | Clean pale paper/leather-like surface, extremely subtle grain | 512×512 seamless | Cards (optional, solid fallback) | P0 |
| `workshop_header` | Warm timber workshop interior with lit brick forge, tools and lantern | 1440×600 WebP | Decorative home/library header | P1 |
| `forge_scene` | Right-side bear striking connected golden mesh on anvil, bees holding left side; lit brick hearth | 1200×900 WebP/PNG | Flash screen hero | P0 |
| `forge_hearth` | Brick furnace with flame, independent illustration | 768×768 transparent PNG/WebP | Optional hero layer | P1 |
| `anvil` | Steel anvil with mesh triangle emblem | 768×512 transparent PNG/WebP | Hero/empty state | P1 |
| `bee_helper` | Sunglass-wearing bee with wings, holding net node | 384×384 transparent PNG | Empty state / animation | P1 |
| `hammer`, `sparks` | Straight-handled blacksmith hammer; independent spark burst | 384×384 transparent PNG each | Activity decoration | P2 |
| `tools_cluster`, `lantern` | Workshop hand tools and warm lamp | 512×512 transparent PNG each | Decorative accents | P2 |
| `brand_wordmark` | MeshForge title, workshop sign treatment where useful | 1200×400 transparent PNG or SVG | Branding | P1 |
| `ic_*` | USB, chip/device, hammer/flash, library, repository, settings, refresh, download, check, warning, error, repeater, companion, back, search, overflow | 24dp vector drawables; consistent 24×24 grid | Functional controls | P0 |

P0 = required for core visual implementation; P1 = supporting assets; P2 = optional polish. The approved logo and concept sheet are visual references; the compact mascot, textures and separate forge illustrations remain to be created. Keep the previously approved full logo as the brand reference; do not overwrite it with a new generated variation without approval.

## 4. Screen treatment

### Home / Flash

Warm wooden canvas. Compact illustrated brand header with bear and bees, readable MeshForge wordmark and settings action. Connection status banner below. A large parchment device card shows hardware, firmware role, repository, release, preservation options and primary gold Flash firmware button. Firmware library summary remains below. Wooden bottom navigation includes the existing destinations (concept: Flash, Library, Repos, Settings).

### Firmware library / Repositories

Wooden title bar, parchment search and filter controls and pale readable list cards. Display actual release version, device compatibility, firmware role, download/cache state and available actions. Use gold for selected filters or primary download/update actions; green for verified/local state when applicable. Do not bake these elements into images.

### Flash progress

Use the forge scene as a distinct hero illustration above an opaque parchment progress panel. Show real target name, installed/selected release, actual percentage when known and actual stages. Use indeterminate progress if no reliable percentage is available. Only display stages such as erase, write, verify and reboot when the active backend actually provides them. Keep disconnect warnings and recovery instructions visible. Never imply verification or configuration preservation unless supported by the real backend.

### Other screens

Settings, logs, dialogs, USB permission, empty and error states follow the same wood/parchment/brass palette. Dense diagnostic data must remain on opaque surfaces; decorative art is optional there. Keep destructive actions distinct from normal gold actions.

## 5. Implementation notes for Codex

- Inspect the existing Android application and its UI framework first; integrate its existing navigation, screens, theme and state models rather than creating a separate demo app. If it uses Compose, implement a central Material 3 colour scheme plus custom workshop surface components. Otherwise use equivalent Android resources/theme tokens. Do not migrate frameworks just for this visual work.
- Create reusable `WorkshopBackground`, `WoodenTopBar`, `ParchmentCard`, `BrassButton`, `ConnectionStatus`, `ForgeProgress` and `WorkshopNavigation` components (names are suggestions). Use theme tokens, not repeated hard-coded colours.
- Use layered assets: tileable textures, independent illustrations and real native text/controls. Avoid full-screen baked screenshots, text in backgrounds, excessive skeuomorphic ornament or redundant decorative panels.
- Keep buttons, settings, progress, device data and version strings driven entirely by application state. All example hardware, releases and percentages in the concept sheet are illustrative.
- Use readable Android typography, at least 14sp for essential labels and 48dp minimum touch targets. A heavier friendly title style may complement the logo; keep body text simple. Support text scaling and landscape/compact screens.
- Export transparent assets with clean edges; use lossless WebP/PNG where necessary and vector drawables for functional icons. Do not stretch illustrated textures disproportionately. Use density-aware resources and avoid loading multiple full-resolution hero assets at once.
- Use Android adaptive icon safe zone (central 66dp diameter of 108dp layers). Let the launcher apply its own mask; provide a monochrome themed icon when practical. Use a compact motif rather than shrinking the entire detailed sticker into the launcher.
- Optional forge animation: short restrained sparks or flicker only during active programming; stop on completion or error and respect reduced-motion settings. No continuous decorative animation required.
- Verify all states with screenshots on real Android screen sizes: waiting for USB, target detected, firmware selection, library, downloading, flashing, verified success, error/recovery and settings. Ensure text contrast, no clipping and recognizable theme at thumbnail size.

## 6. Acceptance criteria

1. Exposed screen background is unmistakably warm brown/orange wood, while technical cards are light parchment.
2. Forge Hearth imagery (anvil, furnace, hammer, sparks) is present in the hero visuals; no hood covers the bear's head, and the bees assist rather than just hover decoratively.
3. All screenshots share the same palette and reusable materials; no dark hacker, neon, purple or steel-blue theme substitutions.
4. Real firmware functionality, state handling and navigation remain intact. All important information remains readable, selectable and accessible.
5. Reference artwork is translated into separate reusable assets and live UI, not copied as a static screen image.

The generated sheet is an art-direction reference, not a pixel-perfect production layout or an authoritative firmware feature/version list.
