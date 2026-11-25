# MeshForge — UI Art Direction & Asset Specification

**Status:** approved visual direction / implementation brief\
**Platform:** Android\
**Theme:** Carpenter Workshop × Forge Hearth\
**Reference:** [Approved concept sheet](meshforge_carpenter_forge_ui_board.png)\
**Brand:** MeshForge · Firmware forge for MeshCore\
**Author:** WarDaddy 🪖

[Brand logo](meshforge_logo.png) · [Sprite sheet](meshforge_spritesheet.png)

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
| `workshop_header` | Warm timber workshop interior with lit brick forge, tools and lantern | 1440×600 WebP | Compact illustrated title bars | P1 |
| `forge_scene` | Bear striking connected golden mesh on the anvil, with bees holding the mesh and a lit brick hearth | 1200×900 WebP/PNG | Configurator and Flash Progress hero | P0 |
| `forge-animation.webp` | Six-frame forge loop | 1860×824 WebP atlas | Full heroes and cropped title-bar artwork during active work | P0 |
| `forge_hearth` | Brick furnace with flame, independent illustration | 768×768 transparent PNG/WebP | Optional hero layer | P1 |
| `anvil` | Steel anvil with mesh triangle emblem | 768×512 transparent PNG/WebP | Hero/empty state | P1 |
| `bee_helper` | Sunglass-wearing bee with wings, holding net node | 384×384 transparent PNG | Empty state / animation | P1 |
| `hammer`, `sparks` | Straight-handled blacksmith hammer; independent spark burst | 384×384 transparent PNG each | Activity decoration | P2 |
| `tools_cluster`, `lantern` | Workshop hand tools and warm lamp | 512×512 transparent PNG each | Decorative accents | P2 |
| `brand_wordmark` | MeshForge title, workshop sign treatment where useful | 1200×400 transparent PNG or SVG | Branding | P1 |
| `ic_*` | USB, chip/device, hammer/flash, library, repository, settings, refresh, download, check, warning, error, repeater, companion, back, search, overflow | 24dp vector drawables; consistent 24×24 grid | Functional controls | P0 |

P0 = required for core visual implementation; P1 = supporting assets; P2 = optional polish. The approved logo and concept sheet are visual references; the compact mascot, textures and separate forge illustrations remain to be created. Keep the previously approved full logo as the brand reference; do not overwrite it with a new generated variation without approval.

## 4. Screen treatment

The app opens to Configurator and stays in portrait. The bottom bar shows **Configurator**, **Flash**, **Library**, and **Settings**. Android Back returns from Flash Progress to Flash and from the other main screens to Configurator.

### Configurator

The full MeshForge billboard and forge scene sit above the USB radio controls. Play the forge loop while MeshForge reads or saves radio settings. Group configuration fields under their task headings and show fields the attached firmware supports.

### Flash

Use a compact illustrated title bar with a readable title. Keep device and board selection, firmware source, release or local file selection, and OTAFix in this screen. Show OTAFix availability, selection, and confirmed installation as distinct states. The **Flash** button highlights missing selections and opens Flash Progress when the selection is complete. Flash Progress loads or downloads the selected image before confirmation.

### Firmware Library

Use a compact illustrated title bar, a search field, and parchment lists grouped by repository and version. Show file names and applicable actions within each group. Animate the cropped forge scene behind the stationary title while a download is active. Use the same cropped treatment for the Flash header during a download.

### Flash progress

Show the full forge scene above an opaque parchment progress panel. Keep the forge loop independent of the progress bar. Show the target name, selected release, actual percentage when known, and the stages the backend reports. Use indeterminate progress while the percentage is unknown. Keep disconnect warnings and recovery text visible.

### Settings

Show repository management and diagnostics inline under the compact illustrated title. Keep diagnostic data on opaque parchment surfaces. Use the same palette for dialogs, USB permission, empty states, and errors. Give destructive actions a distinct treatment.

## 5. Implementation notes for Codex

- Inspect the existing Android application and its UI framework first; integrate its existing navigation, screens, theme and state models rather than creating a separate demo app. If it uses Compose, implement a central Material 3 colour scheme plus custom workshop surface components. Otherwise use equivalent Android resources/theme tokens. Do not migrate frameworks just for this visual work.
- Create reusable `WorkshopBackground`, `WoodenTopBar`, `ParchmentCard`, `BrassButton`, `ConnectionStatus`, `ForgeProgress` and `WorkshopNavigation` components (names are suggestions). Use theme tokens, not repeated hard-coded colours.
- Use layered assets: tileable textures, independent illustrations and real native text/controls. Avoid full-screen baked screenshots, text in backgrounds, excessive skeuomorphic ornament or redundant decorative panels.
- Keep buttons, settings, progress, device data and version strings driven entirely by application state. All example hardware, releases and percentages in the concept sheet are illustrative.
- Use readable Android typography, at least 14sp for essential labels and 48dp minimum touch targets. Use simple body text and support text scaling on compact portrait displays.
- Export transparent assets with clean edges; use lossless WebP/PNG where necessary and vector drawables for functional icons. Do not stretch illustrated textures disproportionately. Use density-aware resources and avoid loading multiple full-resolution hero assets at once.
- Use Android adaptive icon safe zone (central 66dp diameter of 108dp layers). Let the launcher apply its own mask; provide a monochrome themed icon when practical. Use a compact motif rather than shrinking the entire detailed sticker into the launcher.
- Use the same six-frame forge loop for each animated surface. Stop on completion or error and use the static artwork when reduced motion is enabled.
- Verify all states with screenshots on real Android screen sizes: waiting for USB, target detected, firmware selection, library, downloading, flashing, verified success, error/recovery and settings. Ensure text contrast, no clipping and recognizable theme at thumbnail size.

## 6. Acceptance criteria

1. Exposed screen background is unmistakably warm brown/orange wood, while technical cards are light parchment.
2. Forge Hearth imagery (anvil, furnace, hammer, sparks) is present in the hero visuals; no hood covers the bear's head, and the bees assist rather than just hover decoratively.
3. All screenshots share the same palette and reusable materials; no dark hacker, neon, purple or steel-blue theme substitutions.
4. Real firmware functionality, state handling and navigation remain intact. All important information remains readable, selectable and accessible.
5. Reference artwork is translated into separate reusable assets and live UI, not copied as a static screen image.

The generated sheet is an art-direction reference, not a pixel-perfect production layout or an authoritative firmware feature/version list.

---

The following navigation references provide the design rules, accessibility guidance, and QA checklists used alongside the MeshForge-specific screen treatment above.

## Mobile Navigation UX — Implementation Instructions

> **Purpose:** Practical, implementation-ready instructions for product designers, developers, QA engineers, and AI coding assistants building or refactoring navigation in mobile applications and responsive mobile websites.
>
> **Scope:** Mobile information architecture, bottom navigation, top tabs, drawers, gestures, segmented controls, contextual actions, accessibility, platform behavior, testing, performance, and mobile-web discoverability.
>
> **Interpretation:** This is an original, implementation-oriented synthesis of the full source article, including its 14-item checklist, pattern catalog, examples, comparative guidance, and workflow. Engineering details, acceptance criteria, and accessibility clarifications are supplemental guidance—not quotations or claims that the article specifies the exact implementation.

### 1. Core Objective

Build mobile navigation that makes **frequent destinations visible, current location obvious, and movement predictable** without demanding precise touches, memorized gestures, or extra exploratory taps.

Every main navigation experience must make it clear:

1. **Where am I?** Which top-level destination and subview are active?
2. **Where can I go?** Which other destinations or task modes are available?
3. **How do I get there and back?** Which visible controls navigate, and what does Back do?
4. **What matters most?** Are frequent tasks easy to reach with one hand?

**Default design:** For an app with three to five frequently visited peer destinations, favor a persistent **labeled bottom navigation bar**. This is a starting point, not a requirement for every product. For websites, content-driven experiences, deep workflows, or small numbers of destinations, choose the pattern that best fits the information architecture and platform conventions.

**Priority order:** Clarity → task success → access and ergonomic comfort → consistency → performance → visual polish.

### 2. Mandatory Design Rules — All 14 Article Checklist Items

#### Rule 01 — Limit primary navigation to approximately 3–5 destinations

- Identify the few destinations supporting the user's most common goals.
- Give these destinations persistent, easy-to-find access when the chosen pattern supports it.
- Avoid squeezing six, seven, or more labeled destinations into a narrow bottom bar.
- Put uncommon destinations in context screens, account/settings areas, a drawer, or another clearly organized secondary structure.
- **Do not hide an essential, high-frequency task merely to satisfy an arbitrary item count.** Rework the architecture if more than five destinations seem equally essential.

**Acceptance test:** The main bar remains readable and tappable at the narrowest supported phone width, and users can find frequent destinations without opening an overflow menu.

#### Rule 02 — Pair recognizable icons with short, explicit labels

- Show both icon and text for primary navigation where practical.
- Use familiar labels based on the user's task or destination, such as `Home`, `Search`, `Messages`, `Saved`, or `Profile`.
- Avoid ambiguous or brand-internal terms such as `Sphere`, `Hub`, or `Discover` without supporting research.
- Keep icon meaning stable across the app; avoid visually similar symbols for different destinations.
- Do not use tooltips or onboarding as a substitute for self-explanatory primary labels.

**Acceptance test:** New users can predict what each primary destination contains without tapping it.

#### Rule 03 — Make interactive targets comfortably large

- Design target areas, not just visible glyph dimensions.
- As a practical starting point, use **at least 44 × 44 points on iOS** and **48 × 48 density-independent pixels (dp) on Android**, subject to current platform guidance and context.
- On the mobile web, design common touch controls generously—approximately **44 × 44 CSS px or larger when feasible**—while checking actual WCAG 2.2 criteria separately.
- Use padding to expand hit areas without making icons visually oversized.
- Prevent overlap between adjacent targets, including badges and floating controls.
- Do not confuse iOS points, Android dp, and CSS pixels; their units are not interchangeable.

**Acceptance test:** Each navigation item is easy to activate on a real phone, without frequent neighboring-item mis-taps.

#### Rule 04 — Put frequent actions within reasonable thumb reach

- Place frequently used top-level destinations and contextual actions near the lower portion of a phone screen when suitable.
- Favor bottom navigation, bottom sheets, or lower-screen action areas for frequent one-handed tasks.
- Do not force the user to stretch to a tiny top-corner icon for a frequent action.
- Keep high-risk actions separated from routine navigation.
- Check accessibility, device size, keyboard presence, orientation, and screen-reader use: no placement works equally well for everyone.

**Acceptance test:** Representative users can perform common journeys one-handed without hand repositioning becoming a consistent obstacle.

#### Rule 05 — Clearly identify the current destination

- Mark the active top-level tab with a distinct, persistent visual state.
- Use more than color alone: combine color with shape, underline, indicator, background treatment, weight, or another clear cue.
- Expose current-page/current-tab information to assistive technology.
- Keep the active indicator correct after deep links, refresh, browser Back, notification entry, and tab switching.
- Show local hierarchy where necessary through a title, parent label, or back affordance.

**Acceptance test:** A user can identify their current top-level location immediately, even after arriving through a deep link.

#### Rule 06 — Respect platform conventions, Back behavior, and safe areas

- On Android, integrate with system Back navigation (including gesture-based Back where applicable).
- On iOS, respect expected back-navigation affordances, interactive gestures, and tab navigation norms.
- On web, maintain valid URLs, browser history, browser Back/Forward, and direct-loadable routes.
- Keep controls clear of the OS gesture bar, device cutouts, home indicator, and on-screen keyboard.
- Account for bottom safe-area insets without creating excessive dead space or blocked content.
- Define what Back does for every nested screen, overlay, sheet, drawer, and deep-linked state.

**Acceptance test:** No major flow traps the user, conflicts with system gestures, or places a tap target behind a system UI region.

#### Rule 07 — Keep transitions understandable and restrained

- Use short, predictable transitions that reinforce movement between levels or destinations.
- Distinguish changing a top-level tab from drilling into a detail screen.
- Prevent contradictory motion, such as a screen moving sideways when hierarchy suggests a stacked detail view.
- Avoid long animations that slow navigation or conceal delayed content.
- Respect reduced-motion preferences and avoid making animation necessary to understand state.

**Acceptance test:** After every navigation action, users know whether they switched sections, entered a subview, or dismissed an overlay.

#### Rule 08 — Never require undiscoverable gestures to complete a core task

- Treat swipes, edge gestures, pull-to-refresh, and similar interactions as optional shortcuts.
- Provide visible controls for the same essential task.
- Avoid gesture conflicts with OS Back, scrolling, carousels, assistive technology, or drag-and-drop.
- If a gesture is unusually valuable, teach it contextually while retaining the visible alternative.
- Support users who have reduced dexterity or cannot perform multi-pointer movements.

**Acceptance test:** All core navigation tasks can be completed without relying on a memorized gesture.

#### Rule 09 — Provide meaningful accessibility names, roles, and focus order

- Every navigation control needs a descriptive accessible name.
- Distinguish navigation destinations from actions and disclosure controls semantically.
- Follow a logical focus or accessibility traversal order corresponding to the visual interface.
- Announce active/selected/expanded states where appropriate.
- Ensure opened navigation panels have sensible focus behavior and a dependable dismissal path.
- Prefer semantic HTML or native accessibility APIs over custom, improvised semantics.

**Acceptance test:** VoiceOver, TalkBack, and external-keyboard users can find, identify, activate, and leave every navigation destination.

#### Rule 10 — Keep navigation responsive and lightweight

- Keep the navigation shell and high-frequency controls responsive to input.
- Reserve layout space so the bar, header, or drawer does not jump into place after content loads.
- Cache suitable static assets and reuse icon resources.
- Prefetch likely next views or data **selectively**, based on benefit and network/device cost.
- Lazy-load noncritical resources while preserving immediate visibility of core controls.
- Do not place every navigation transition behind an unnecessary blocking network request.

**Acceptance test:** On typical mobile hardware and a constrained network, users can quickly recognize the response to a tap without major layout instability.

#### Rule 11 — Validate categories and labels with users

- List all destinations and test whether users group them as the information architecture expects.
- Use card sorting to investigate perceived groupings, and tree testing to test findability.
- Use realistic task statements, not leading prompts that repeat the answer label.
- Compare label comprehension across novice and experienced users where relevant.
- Change ambiguous labels based on observations, not on stakeholder preference alone.

**Acceptance test:** Representative users can locate key tasks in a tested hierarchy without repeated wrong turns.

#### Rule 12 — Use concise, consistent wording

- Choose one canonical name per destination and use it across navigation, headings, shortcuts, and related help content.
- Keep labels short enough to fit without relying on unreadably small fonts.
- Prefer predictable nouns for places and direct verbs for commands.
- Avoid truncating two labels into similar-looking abbreviations.
- Verify translated/localized labels at realistic text expansion lengths.

**Acceptance test:** Labels remain readable and unambiguous across supported screen widths, font sizes, and languages.

#### Rule 13 — Do not bury frequent tasks in drawers or overflow

- Surface common tasks in primary navigation, prominent contextual controls, or the destination where they naturally belong.
- Use drawers for infrequent settings, account-level tasks, help, administration, and other secondary items.
- Do not assume a hamburger icon is equivalent in discoverability to a visible tab.
- Avoid a `More` section that becomes the accidental home of multiple primary destinations.
- Let actual task frequency and discoverability testing drive promotion or demotion.

**Acceptance test:** The most common tasks are directly discoverable, rather than hidden behind a generic overflow control.

#### Rule 14 — Make empty states and onboarding support discovery

- When a destination has no content, explain what the area is for and how to begin.
- Include a relevant first action, such as `Find people`, `Create a list`, or `Explore items`.
- Offer brief, contextual onboarding for nonobvious features; do not block the whole interface behind a long tutorial.
- Preserve discoverability after onboarding is dismissed.
- Never show misleading empty states that make a route look broken or permanently unavailable.

**Acceptance test:** A first-time user who enters an empty section can understand its purpose and identify the next useful action.

### 3. Choose the Right Navigation Pattern

| Pattern | Best fit | Strengths | Main risks | Implementation rule |
|---|---|---|---|---|
| **Bottom navigation / tab bar** | 3–5 frequent peer destinations | Visible, scannable, thumb-friendly | Overcrowding; ambiguous icons | Use clear icons and labels; stable ordering; active state |
| **Top tabs / top navigation** | Subcategories, feeds, content sections | Makes section switching and scanning obvious | Harder to reach one-handed; horizontal overflow | Use concise labels; make selected state visible; differentiate from primary tabs |
| **Hamburger / drawer** | Secondary destinations, settings, long lists | Holds less frequent destinations without clutter | Weak discoverability for common tasks | Never use as the only home of frequent primary tasks without evidence |
| **Gesture-based shortcuts** | Expert workflows, lateral page switching | Fast when learned; low visual clutter | Hidden affordances; OS-gesture conflicts | Every essential gesture needs a visible alternative |
| **Segmented controls** | Two or three mutually exclusive views of the same content | Clear, compact local mode choice | Too many segments; confusion with global navigation | Use for local view state, not unrelated primary destinations |
| **Contextual bottom sheet** | Short task decisions, filters, progressive flows | Thumb-friendly, preserves task context | Occlusion, nested modal complexity | Provide dismissal, focus management, and predictable Back behavior |
| **Bottom bar + prominent action (FAB/CTA)** | Frequent creation, compose, scan, or capture action | Makes a key command easy to find | Competing emphasis; obstruction | Distinguish action from destination; avoid unnecessary duplication |
| **Full-screen guided flow** | Map-led, booking, checkout, onboarding, or task-first flows | Focused task progression | Losing global context, difficult escape | Maintain clear current step, Back, and exit/recovery path |

#### 3.1 Primary destinations versus local view switches

A **navigation destination** is a major area, such as `Messages` or `Orders`. A **local mode** is an alternate view of the same area, such as `List / Map` or `Day / Week`.

- Use bottom tabs or equivalent global controls for major peer destinations.
- Use top tabs or segmented controls for subcategories or local modes, not to duplicate global tabs.
- If switching modes preserves the same data context, do not present the modes as unrelated app sections.
- A prominent floating `Create` button is typically an **action**, not automatically a fourth or fifth destination.

#### 3.2 Hierarchy and disclosure

- Keep primary navigation shallow and stable.
- Place nested category detail under an appropriate parent destination.
- For content with many levels, offer visible parent labels, breadcrumbs when useful, and coherent drill-down paths.
- Do not build recursive drawers within drawers as a default solution to poor information architecture.
- Prefer an easily searchable or categorized destination directory over hidden, arbitrarily deep chains.

#### 3.3 When not to use a persistent bottom bar

Do not force a bottom bar onto a product when:

- Only one main activity exists and the remaining commands are contextual.
- A focused workflow needs the full screen and has a clear entry/exit model.
- A mobile website's architecture and browsing expectations favor a conventional header, search, or section index.
- A device is unusually small or used in landscape and a bar would obscure essential content.
- Evidence shows a more appropriate navigation system for the target audience and task environment.

If the bar is hidden during immersion or reading, establish an intuitive and accessible way to re-expose it; hiding must not strand the user.

### 4. Pattern Selection by Product Type

| Product type | Starting pattern | Important supporting elements | Avoid |
|---|---|---|---|
| E-commerce | Bottom destinations + prominent search | Cart, orders, categories, recently viewed | Hidden cart/checkout; deep category dead ends |
| Social/community | Bottom destinations + clear create action | Notifications, messaging, feeds | Overcrowded tabs; unlabeled actions |
| News/content | Stable top-level navigation plus local topic tabs where needed | Search, saved items, return-to-article | Treating every category as a global tab |
| Productivity/tools | Task-based primary navigation + local segmented controls | Recent work, create, search | Excess modes and unclear hierarchy |
| Banking/finance | Clear primary destinations; settings secondary | Payments, accounts, cards, trust indicators | Icon-only ambiguous finance commands |
| Travel/booking/map-led | Contextual or task-first flow with reachable controls | Search, filters, bottom sheets, trips | Blocking essential map/task content |
| Mobile website | Responsive header/primary links with appropriately visible key paths | Browser Back, URLs, search, local hierarchy | JS-only destinations and inaccessible links |

**These are starting heuristics, not universal prescriptions.** Choose using actual primary tasks, information architecture, device behavior, and test evidence.

### 5. Mobile App versus Mobile Website Requirements

#### Native mobile apps

- Follow iOS or Android conventions for tabs, stacks, system Back, gestures, and safe areas.
- Define whether each top-level tab preserves its own navigation history or resets on reselect; use one predictable model.
- Restore navigation state appropriately after backgrounding, process recreation, or returning from another app.
- Support deep links into detail views, with a usable route back to the corresponding section.
- Design offline/error behavior so navigation controls remain available where feasible.
- Use native accessibility APIs for selected tab, names, traits, heading structure, and announcements.

#### Responsive mobile websites / PWAs

- Use real links (`<a href="...">`) for actual page destinations; use buttons for commands and disclosures.
- Preserve addressable URLs, browser history, direct loading, refresh, and browser Back/Forward.
- Render or expose important navigation links without requiring avoidable, heavy client-side execution.
- Make the global menu operable by touch, keyboard, screen reader, and pointer—not hover alone.
- Ensure sticky navigation does not obscure headings, in-page anchors, or the on-screen keyboard.
- Provide appropriate landmarks and local context for deep content pages.
- Treat SEO and crawler discoverability as **mobile-web concerns**; they do not directly apply to native app tab bars.

#### Shared rule

Keep frequent destinations findable, current location clear, labels meaningful, and navigation behavior stable, regardless of technology stack.

### 6. Interaction and State Requirements

#### 6.1 State model

For every primary navigation item, define:

1. **Idle:** Discoverable label and icon; target available.
2. **Current/selected:** Persistent indicator and programmatic state.
3. **Focused:** Visible focus indicator for keyboard navigation.
4. **Pressed/activated:** Immediate, understandable input feedback.
5. **Loading (when necessary):** Progress does not make the current destination ambiguous.
6. **Unavailable (when applicable):** Disabled or permission-gated with a clear explanation; no dead tap.

For drawers and overlays, additionally define **closed, opening, open, closing**, and **dismissed by Back/Escape/selection/outside interaction** as appropriate for the platform and modality.

#### 6.2 Back-navigation contract

- **Nested detail → previous/parent context.** Preserve useful scroll and filter state if practical.
- **Drawer or transient modal → dismiss overlay first**, before navigating away, in accordance with platform conventions.
- **Top-level destination switch → preserve the product's documented tab/history behavior.** Do not invent inconsistent special cases.
- **Deep link → sensible parent/navigation path.** Avoid sending the user into a dead end with a Back button that has no meaningful destination.
- **Web → browser history is authoritative.** Do not hijack Back to simulate an inconsistent native flow.

#### 6.3 Keyboard and assistive-technology behavior

- All interactive destinations must have meaningful accessible names.
- The visual active tab must be announced as current or selected using the semantics appropriate to the actual component.
- On websites, use `<nav aria-label="Primary">` containing ordinary links for ordinary site navigation; **do not add `role="menubar"` and `role="menuitem"` by default**. Those widget roles imply specialized arrow-key and focus-management behavior.
- Use disclosure buttons with `aria-expanded` for collapsible web navigation sections.
- Use actual tabs (`role="tablist"`, `role="tab"`, `role="tabpanel"`) only when implementing a true tabbed interface **and** its expected keyboard behavior.
- Keep hidden drawer items out of the normal tab sequence until the drawer opens.
- For modal drawers/sheets, manage focus appropriately and restore it when dismissed; avoid trapping focus in nonmodal navigation.
- Screen reader order must be coherent; do not rely on icon-only cues or visual positioning alone.

#### 6.4 Example: straightforward mobile-web primary navigation

```html
<nav aria-label="Primary">
  <ul class="primary-nav">
    <li><a href="/home" aria-current="page">Home</a></li>
    <li><a href="/search">Search</a></li>
    <li><a href="/saved">Saved</a></li>
    <li><a href="/profile">Profile</a></li>
  </ul>
</nav>
```

- Add accessible icons as decorative `aria-hidden="true"` elements only when their labels are already exposed.
- Add an active visual treatment to the current link, synced to the actual route.
- Use correct hit areas and safe-area padding in CSS; do not infer target size from the icon's intrinsic dimensions.
- This example is **website navigation**, not a complete native app tab implementation or an ARIA tabs widget.

#### 6.5 Example: web bottom-bar sizing and safe area

```css
.mobile-primary-nav {
  position: fixed;
  inset-inline: 0;
  bottom: 0;
  padding-bottom: env(safe-area-inset-bottom, 0px);
  background: var(--nav-surface);
}

.mobile-primary-nav a {
  min-height: 48px;
  min-width: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.mobile-primary-nav a:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: -2px;
}
```

This is an **illustration only**. Real implementations also need horizontal layout, responsive behavior, contrast, state styling, viewport padding so content is not obscured, dynamic keyboard handling, and device testing. The `48px` example is a design choice, not a statement that CSS px and Android dp are equivalent.

### 7. Accessibility and Measurable Quality Gates

The following are supplemental standards and implementation checks; distinguish formal conformance requirements from design recommendations.

| Concern | Gate / recommended approach |
|---|---|
| Primary tab count | **3–5** is a useful default for bottom navigation, not a universal accessibility rule. |
| iOS touch controls | Use around **44 × 44 points** or more as a platform-oriented touch-design target. |
| Android touch controls | Aim for **48 × 48 dp** or more, accounting for expanded hit areas. |
| WCAG 2.2 AA pointer targets | SC **2.5.8** generally requires **24 × 24 CSS px**, with specified exceptions, including spacing and equivalent-target conditions. |
| WCAG enhanced pointer targets | SC **2.5.5** (AAA) uses **44 × 44 CSS px**, subject to its exceptions. Do **not** mislabel this as the WCAG AA minimum. |
| Text contrast | WCAG AA SC **1.4.3**: **4.5:1** for normal text and **3:1** for qualifying large text. |
| Non-text visual indicators | WCAG SC **1.4.11**: generally **3:1** contrast for relevant UI component boundaries and state indicators. |
| Orientation | Active/current state visible and programmatically conveyed where applicable. |
| Keyboard access | Every actionable item operable through keyboard; visible focus; no focus trap. |
| Screen readers | Meaningful labels, correct roles/states, sensible traversal, no duplicate or silent actions. |
| Gestures | Provide a simple visible alternative for essential interactions and avoid OS conflicts. |
| Motion | Avoid unnecessary motion; honor `prefers-reduced-motion` on web or the platform equivalent. |
| Text adaptation | Test localization, large text, increased display scaling, and available zoom modes. |
| Safe areas | No controls hidden behind the home indicator, gesture zone, cutouts, keyboard, or overlays. |

**Important distinction:** The source article gives a 44–48-size design recommendation and lists multiple platforms. Do not combine their units into a single purported compliance threshold. WCAG 2.2 AA and AAA have different success criteria; passing a target-size test alone does not establish WCAG conformance.

Reference guidance:

- W3C WCAG 2.2: https://www.w3.org/TR/WCAG22/
- W3C understanding target size minimum: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum
- W3C understanding target size enhanced: https://www.w3.org/WAI/WCAG22/Understanding/target-size-enhanced
- W3C WAI-ARIA landmark practices: https://www.w3.org/WAI/ARIA/apg/practices/landmark-regions/
- W3C WAI-ARIA tabs pattern: https://www.w3.org/WAI/ARIA/apg/patterns/tabs/
- Android Accessibility Help, touch target size: https://support.google.com/accessibility/android/answer/7101858

### 8. Performance and Resilience Requirements

- Do not hide or delay drawing the navigation skeleton while unrelated content fetches.
- Use compact icon assets and avoid redundant icon-font or image downloads.
- Preserve navigation height to minimize layout shifts on first render and route changes.
- Cache suitable static resources; avoid stale user-specific data appearing as if current.
- Prefetch only where it meaningfully improves a likely route without excessive data use.
- Maintain obvious feedback if a screen must load, and allow cancellation/recovery for stalled transitions.
- Provide purposeful offline and network-error states; keep available navigation operable.
- Avoid dropping state unexpectedly when a user switches between top-level destinations.
- Measure startup-to-navigation readiness, interaction responsiveness, screen transition duration, layout shifts, and failed route loads on real devices.

**Acceptance test:** The navigation remains usable under slow networks, slow CPU, missing imagery, long labels, and content-loading failures.

### 9. Mobile-Web Search and Content Discoverability

This section applies **only where the product has web pages and web-search discoverability matters**.

- Use descriptive real links for principal website destinations.
- Keep important page paths shallow and reasonably crawlable; do not bury cornerstone content in script-only interactions.
- Do not require a user or crawler to perform an undiscoverable gesture to expose critical links.
- Provide clear page headings, section context, and useful internal links for deep content pages.
- Keep in-page anchors and browser Back behavior functional.
- Reserve dimensions for images, banners, and sticky bars to avoid disruptive layout movement.
- Use relevant structured data only when it accurately describes page content; schema is not a substitute for navigation.
- Validate performance and navigation on real devices, in addition to lab tools such as Lighthouse.

**Avoid:** Treating SEO tactics as native app navigation requirements; hiding all major links behind JavaScript-only click handlers; or making a fixed bottom bar obscure the final article paragraph, form submit button, or cookie notice.

### 10. Examples from the Source — Lessons, Not Prescriptive Clones

The article discusses **12 app examples** to illustrate navigation choices. These are conceptual design references from the source, not guarantees about the apps' current interfaces or labels.

| Example in article | Reusable lesson | Risk to watch |
|---|---|---|
| YouTube | Stable sections for distinct consumption tasks, with a prominent creation route | Hiding common actions in overflow |
| Instagram | Combine visible primary destinations with optional gesture shortcuts | Icon-only ambiguity |
| Spotify | Keep a small core of frequently used destinations and contextual player access | Deeply hidden queue/device controls |
| Amazon | Make search and shopping tasks salient; keep secondary account items organized | Crowded navigation and cryptic labels |
| Uber | Build map-led, focused task sequences using lower-screen actions and sheets | Forcing irrelevant early choices |
| Pinterest | Put discovery and saved-content workflows near everyday browsing | Making board management too obscure |
| WhatsApp | Expose frequent communication destinations and a clear compose action | Burying common conversations/actions |
| Airbnb | Lead with search/discovery and fast paths to saved/trip contexts | Deep, confusing filter trees |
| Google Maps | Combine map, search, contextual filters, and nearby actions coherently | Hiding important map/offline settings |
| Twitter/X | Support quick movement among timelines, search, notifications, and messaging | Constantly changing/crowding the primary bar |
| Monzo | Emphasize unambiguous, confidence-building finance destinations | Icon-only money-related controls |
| Flipkart | Coordinate product search, category discovery, cart, and account/order access | Long hidden category trees |

**Implementation instruction:** Derive reusable patterns from these examples; do not reproduce branding, copy screens verbatim, or assume the example's current UI matches the article.

### 11. Design and Validation Workflow

1. **Inventory tasks and routes.** List frequent tasks, infrequent tasks, major destinations, local modes, deep links, and settings.
2. **Identify top-level jobs.** Use existing research or analytics to choose the few destinations most relevant to users' goals.
3. **Create the information architecture.** Separate primary destinations, secondary destinations, modes, contextual commands, and transient flows.
4. **Test groupings and labels.** Use card sorting for mental models and tree tests for practical findability.
5. **Pick the navigation pattern.** Start from task structure and platform context, not aesthetics.
6. **Prototype at actual device dimensions.** Include labels, target boxes, safe areas, scroll behavior, keyboards, and content states.
7. **Define state transitions.** Document selected/current states, Back behavior, tab history, deep links, overlays, gestures, and focus handling.
8. **Perform fast usability sessions.** Give representative participants realistic, unlabeled goals and observe without prompting.
9. **Review accessibility.** Test TalkBack/VoiceOver, keyboard, large text, contrast, target size, and gesture alternatives.
10. **Test performance.** Exercise real phones, slow networks, delayed routes, and failure states.
11. **Iterate using evidence.** Run controlled A/B tests when viable; avoid concluding success from tab-click volume alone.
12. **Document exceptions.** Explain any deviation from conventional patterns and the evidence supporting it.

#### Measurements to capture

| Metric | Interpretation | Caveat |
|---|---|---|
| Task completion rate | Whether users reach the intended destination and finish the task | Define a successful end state clearly |
| Time to first correct tap | Discoverability of starting action | Very fast wrong taps are not success |
| Taps to destination | Route depth / unnecessary detours | Fewer taps do not automatically mean better UX |
| Wrong turns / backtracks | Misleading hierarchy or labels | Distinguish exploration from confusion |
| Accidental taps | Target sizing and proximity issues | Observe on real touch devices |
| Time to task completion | Combined navigation and task efficiency | Control for task complexity |
| Drop-off by step | Where a funnel or route loses users | Drop-off may reflect intent, not only navigation |
| Accessible task success | Usability for screen-reader/keyboard users | Must be observed, not inferred from automated scans |
| Navigation interaction latency | Perceived performance | Separate input handling, fetching, and rendering |

**Small-sample usability tests** are good for finding issues, not for proving that a design works for all users. Use further testing, quantitative evidence, and diverse participants when the product's impact warrants it.

### 12. QA Checklist

#### Information architecture and priority

- [ ] Each top-level destination represents a distinct, user-meaningful area.
- [ ] Primary destinations correspond to frequent tasks and have stable placement.
- [ ] A bottom bar uses approximately 3–5 labeled items when appropriate.
- [ ] Frequent tasks are not buried in drawers or a generic `More` group.
- [ ] Local view modes are distinguished from top-level destinations.
- [ ] Secondary settings and infrequent tasks are predictably grouped.

#### Labels, orientation, and discovery

- [ ] Primary icons have clear text labels where practical.
- [ ] Labels are concise, familiar, and consistent with page titles.
- [ ] Current destination is visible and programmatically exposed.
- [ ] Deep-link entry gives usable section context.
- [ ] Empty destinations tell users what they can do next.
- [ ] No essential task requires a hidden gesture.
- [ ] Translations and large text do not create unreadable truncation.

#### Ergonomics and interaction

- [ ] Common actions are reasonably thumb-reachable.
- [ ] Tap hit areas are measured, not assumed from icon sizes.
- [ ] Adjacent controls do not overlap or cause repeated mis-taps.
- [ ] Back behavior is consistent for tabs, details, drawers, and sheets.
- [ ] Platform gesture areas, cutouts, and safe areas are respected.
- [ ] On-screen keyboards do not obscure a required navigation action.
- [ ] Motion is restrained and can be reduced where appropriate.
- [ ] Opening and closing drawers/overlays does not strand users.

#### Accessibility

- [ ] Correct semantic link/button/tab patterns are used for each interaction.
- [ ] Focus order or screen-reader traversal matches intended reading order.
- [ ] Selected, current, and expanded states are announced correctly.
- [ ] Keyboard-only access and focus visibility work on relevant platforms.
- [ ] Accessible names convey purpose without relying on icons alone.
- [ ] All essential swipe/gesture operations have accessible alternatives.
- [ ] Actual target areas and text/UI contrast are verified against relevant criteria.
- [ ] VoiceOver/TalkBack and large-text checks have been performed on devices.

#### Reliability and mobile web

- [ ] Navigation renders without disruptive layout shifts.
- [ ] Tab transitions do not unnecessarily block on unrelated network requests.
- [ ] Errors/offline states preserve useful navigation where possible.
- [ ] Navigation and content do not overlap or obscure one another.
- [ ] Native deep links or web URLs resolve correctly.
- [ ] On web, browser history and real page links work as expected.
- [ ] On web, important routes remain discoverable without hidden JS-only interactions.

### 13. Practical Test Scenarios

Run tests on a narrow phone, a larger phone, and a device with an on-screen keyboard; repeat essential journeys with screen readers and external keyboard support where applicable.

1. **First-time arrival:** Ask a user to find a core feature without explaining the menu.
2. **Routine switching:** Move between three primary destinations, confirming active states and expected history.
3. **Deep-link entry:** Launch directly into a detail screen and return to a meaningful parent or previous context.
4. **Secondary task discovery:** Find account settings or help without mistaking them for primary destinations.
5. **Ambiguous labels:** Ask users to predict what each destination contains before opening it.
6. **One-handed operation:** Complete the top task without awkwardly reaching to a distant corner.
7. **Gesture independence:** Complete the flow without using any hidden swipe shortcut.
8. **Screen-reader path:** Find and activate each destination; verify selected/expanded announcements and traversal.
9. **Large-text/localization:** Confirm the bar and labels remain readable without losing access.
10. **Interruption:** Open a drawer, receive a notification or rotate the device, and verify stable dismissal and return behavior.
11. **Poor network:** Tap a destination while content loads slowly; verify feedback, recoverability, and no dead ends.
12. **Web browser navigation:** Follow a deep URL, refresh, press Back/Forward, and confirm correct state.

**Observe:** Hesitation before the first tap, wrong destination selection, accidental activation, unnecessary Back movements, loss of place, inaccessible controls, and unexpected state reset.

### 14. Instructions for an AI Coding Assistant

When asked to implement or refactor mobile navigation:

1. **Inspect before editing.** Identify framework, route tree, current navigation, native/web target, screen hierarchy, and existing design system.
2. **Preserve behavior.** Keep existing destinations, access control, deep links, and meaningful user state unless the task specifically changes them.
3. **Classify UI elements.** Separate global destinations, subviews, actions, disclosure controls, and temporary workflows.
4. **Choose a conventional pattern.** Prefer labeled, visible primary controls for a small set of frequent destinations; do not force bottom tabs if the context does not fit.
5. **Implement correct semantics.** Use native platform navigation components or semantic web links/buttons; avoid inappropriate ARIA widget roles.
6. **Make location explicit.** Keep active visual state and accessibility state synchronized with routing, including deep links and Back.
7. **Define movement.** Establish consistent Back, tab-history, overlay-dismissal, keyboard, and gesture behavior.
8. **Size for touch.** Use the appropriate unit and target guidance for the platform; test actual hit regions, not only icon artwork.
9. **Respect system boundaries.** Handle safe areas, keyboard obstruction, display scaling, landscape widths, and reduced motion.
10. **Maintain discoverability.** Include text labels, visible alternatives to gestures, and useful empty states.
11. **Keep it fast.** Reuse assets, avoid layout shifts and unnecessary network gating, and preserve state where beneficial.
12. **Test and report.** Verify route selection, primary and secondary navigation, screen readers/keyboard, narrow widths, deep links, failure states, and any intentional exceptions.

**Do not:** add mystery-icon-only primary navigation; hide routine tasks in an overflow drawer without reason; misuse `role="menubar"` for a normal link list; break system Back or browser history; require swipe gestures; place controls under the keyboard/home indicator; change product terminology gratuitously; invent routes or permissions; or claim compliance based solely on touch-target dimensions.

### 15. Definition of Done

Mobile navigation is ready when:

- Users can immediately identify the current destination and the available next destinations.
- The important jobs are easy to reach and clearly named.
- The chosen pattern fits the product's actual hierarchy and platform conventions.
- Controls are reliably tappable, accessible, and unobstructed by system UI.
- Back, deep links, tabs, overlays, and gesture alternatives behave predictably.
- Large text, narrow screens, slow networks, and empty/error states do not leave users stranded.
- Relevant usability and accessibility checks have been completed, with exceptions recorded.
- The Section 12 checklist has been reviewed and blocking defects addressed.

## Navigation Menu Design — Implementation Instructions

> **Purpose:** A practical instruction file for designers, developers, and AI coding assistants implementing navigation in websites and applications.
>
> **Scope:** Global navigation, utility navigation, local/section navigation, footer navigation, dropdowns, mega menus, mobile navigation, and application sidebars.
>
> **Interpretation:** This is an original, implementation-oriented synthesis of all 17 guidelines in the source article. Accessibility specifications and engineering details are supplemental guidance, not claims that the source article prescribes those exact implementations.

### 1. Core Objective

Design navigation that answers three questions without forcing the user to guess:

1. **Where am I?** The current page, section, and relevant parent category are evident.
2. **Where can I go?** The available destinations are discoverable and understandable.
3. **How do I get there?** Opening, closing, selecting, and switching navigation options is predictable and physically easy.

**Default to familiar, visible, consistent navigation patterns.** Never prioritize novelty, animation, or visual cleverness over wayfinding, readability, or reliable interaction.

### 2. Mandatory Design Rules — All 17 Guidelines

#### A. Make navigation visible

##### Rule 01 — Show main navigation on larger screens

- Expose the primary destinations directly on desktop and other roomy layouts.
- Do **not** hide the entire primary navigation behind a hamburger icon when there is sufficient room to display it.
- Treat navigation as a description of the site's or product's scope, not merely a mechanism for moving between pages.
- On compact screens, a collapsible navigation control is acceptable when screen width requires it.

**Acceptance test:** A new desktop visitor can identify the main sections without opening a menu.

##### Rule 02 — Put each navigation type where users expect it

| Navigation type | Typical location | Implementation instruction |
|---|---|---|
| Website primary/global | Site header | Keep the main categories easy to find and consistent across pages. |
| Application primary/global | Left sidebar or rail | Use a stable position across major views. |
| Utility/secondary | Upper area, generally above or near global navigation/search | Place account, help, settings, and secondary destinations consistently. |
| Section/local | Left side on roomy desktop websites | Surface related pages and neighboring tasks. |
| Footer | Page bottom | Include supplementary, administrative, and less common audience-specific links. |

- On narrow screens, adapt placement to the device without losing clear grouping.
- Do not move familiar controls unpredictably between screens.

**Acceptance test:** A returning user finds each navigation type in the same expected area.

##### Rule 03 — Maintain clear text/background contrast

- Menu text must remain legible against every background and in every state.
- Avoid text over photographs, animated hero media, or transparent overlays unless a dependable solid/semitransparent surface preserves contrast.
- Verify default, hover, focus, selected, disabled, and expanded states.
- Do not rely on color alone to communicate meaning.

**Acceptance test:** Labels remain readable across background changes and meet the accessibility contrast criteria in Section 5.

##### Rule 04 — Keep desktop menus contextual, not full-screen

- On desktop, dropdowns and mega menus should occupy only the area needed to display their options.
- Keep enough of the original page visible to preserve orientation and make the menu feel temporary.
- Do not make an opened desktop submenu resemble a completely different page.
- Full-screen or near-full-screen navigation may be appropriate on phones because of space limitations.

**Acceptance test:** Opening a desktop menu does not hide the entire underlying interface.

#### B. Show where the user is

##### Rule 05 — Indicate the current location

- Visually distinguish the active destination in relevant global or local navigation.
- Use a persistent active-state marker such as an underline, highlight, weight change, side bar, or another unmistakable signifier.
- For nested sites, provide breadcrumbs where they help establish hierarchy.
- When a page belongs to a parent section, identify that parent as well as the current page where useful.
- Ensure the location is understandable for users who arrive directly via a deep link.
- Programmatically identify the current page, e.g. `aria-current="page"` on the appropriate link.

**Acceptance test:** A person landing on an internal page can identify its section and a sensible route to a related or higher-level page.

##### Rule 06 — Provide local navigation for related pages

- Within content-rich sections, expose nearby pages in a persistent local menu or equivalent section index.
- Prioritize lateral movement between related products, articles, settings, tools, or workflows.
- Avoid forcing users to navigate to a parent page and back down repeatedly to compare related content.
- Collapse or reposition local navigation on narrow screens without making related options inaccessible.

**Acceptance test:** A user can move between sibling pages without repeatedly returning to the section landing page.

#### C. Explain what choices are available

##### Rule 07 — Use specific, familiar destination labels

- Choose words the target audience already uses.
- Prefer clear nouns or task labels that accurately predict destination content.
- Avoid internal team names, jargon, unexplained acronyms, abstract marketing labels, and invented terms.
- Test ambiguous category names with actual users or representative tasks.
- Do not use humor or branding as a substitute for navigational meaning.

**Acceptance test:** Users can predict where a link leads without opening it.

##### Rule 08 — Make options easy to scan

- Left-align labels in vertical menus, particularly when label lengths vary.
- Put the most meaningful words at the beginning of a label.
- Keep wording concise but not so abbreviated that it becomes ambiguous.
- Group related choices and avoid visually dense, undifferentiated blocks.
- Use consistent capitalization, spacing, typography, and label structure.

**Acceptance test:** A user can locate a specific option by scanning rather than reading the menu word by word.

##### Rule 09 — Expose useful hierarchy for large information spaces

- If normal tasks require navigating multiple levels, allow users to see more than one level where appropriate.
- On desktop, use a well-structured mega menu when it reduces unnecessary steps through a deep hierarchy.
- On mobile, consider a sequential/drill-down menu with clear back navigation for complex hierarchies.
- Organize hierarchy by user mental models; do not expose every internal information-architecture layer indiscriminately.
- Ensure the user always understands which category or depth they are viewing.

**Acceptance test:** Users can reach common deep destinations without a lengthy series of confusing intermediate pages.

##### Rule 10 — Add restrained visual cues to long menus

- Use icons, small illustrations, or restrained color grouping when they materially improve recognition of unfamiliar choices.
- Treat visuals as supportive information, not as replacements for words.
- Prefer simple, distinguishable icons over detailed, nearly identical thumbnails.
- Keep labels visible in accessible text.
- Apply visuals consistently within a category system.

**Acceptance test:** Each option is understandable even when its icon or decorative image is hidden.

#### D. Make navigation easy to operate

##### Rule 11 — Make each item easy to activate

- Give links and buttons sufficiently large click/tap areas.
- Separate adjacent targets enough to prevent accidental selection.
- Make the entire intended row or control interactive instead of a tiny text fragment when appropriate.
- Maintain comfortable spacing for pointer and touch use, including on desktop.
- Use the measurable target-size standards in Section 5.

**Acceptance test:** People can reliably choose neighboring menu items using a finger, mouse, or trackpad.

##### Rule 12 — Signal which items expand

- Add an obvious caret or arrow to items that reveal submenus.
- Visually distinguish expandable controls from direct destination links.
- Keep the disclosure signifier's orientation and state consistent.
- Do not make an arrow the only discoverable way to open a submenu if the rest of the label appears actionable.
- If a category must both navigate and expand, provide two clear, separate affordances or a documented, consistently used interaction model.

**Acceptance test:** Users can identify whether selecting an item opens a page or reveals more choices before acting.

##### Rule 13 — Open submenus by click/tap, not hover only

- Use explicit click/tap activation as the dependable default.
- Support keyboard activation (`Enter`/`Space` for buttons) and touch input.
- Avoid menus that open or close unintentionally while the pointer passes over them.
- If hover previews are offered, they must not be the sole access path.
- Ensure focus and expanded state are managed deliberately.

**Acceptance test:** Every submenu is fully usable without hover.

##### Rule 14 — Avoid deep cascading flyout chains

- Avoid multi-level, horizontally cascading dropdowns requiring precise diagonal mouse movement.
- A one-level dropdown is generally manageable; multiple nested flyouts become fragile and error-prone.
- For deeper structures, prefer a categorized mega menu, an intermediate category page, or sequential mobile navigation.
- Keep the active submenu accessible while the user moves to an intended option.

**Acceptance test:** Reaching a deep destination does not require traversing a narrow chain of floating flyouts.

##### Rule 15 — Consider sticky navigation on long pages

- Where lengthy pages make returning to navigation burdensome, keep an appropriately sized header, sidebar, or partial navigation element available while scrolling.
- Prefer a compact sticky variant when a large fixed header would consume too much viewport space.
- Ensure sticky elements do not obscure content, headings, anchor targets, or focused controls.
- Do not introduce sticky behavior if it reduces usable space more than it helps.

**Acceptance test:** A user at the end of a long page can access important navigation without an excessive return scroll.

##### Rule 16 — Put high-frequency actions close to the opening point

- Position commonly used items near the control that opens a dropdown so that users make less pointer/finger travel.
- Consider both frequency and logical grouping: do not scramble a familiar sequence for marginal distance savings.
- Keep destructive or high-risk items sufficiently separated from common actions to reduce accidental activation.
- Verify placement with actual task frequency where possible.

**Acceptance test:** Common commands are among the easiest items to reach, without increasing error risk.

##### Rule 17 — Avoid navigation gimmicks

- Use established patterns unless user testing demonstrates a concrete benefit from a departure.
- Do not substitute dots, mystery icons, arbitrary carousels, elaborate animations, or unexpected gestures for clear navigation.
- Give any nonstandard control an obvious label, predictable outcome, and accessible fallback.
- Evaluate novel navigation against task completion and comprehension, not aesthetic novelty.

**Acceptance test:** A first-time user can operate the navigation without instructions or experimentation.

### 3. Responsive Navigation Strategy

| Concern | Wide desktop | Tablet / compact desktop | Phone |
|---|---|---|---|
| Global navigation | Visible header links or application sidebar | Retain visible primary links where feasible; selectively collapse overflow | Compact menu button or other space-appropriate recognizable pattern |
| Deep hierarchy | Mega menu or section landing page | Compact mega menu or clearly grouped disclosure | Sequential drill-down or collapsible sections with a clear return path |
| Submenu operation | Click-activated | Click/tap-activated | Tap-activated |
| Menu footprint | Partial overlay, underlying context visible | Bounded overlay or panel | Drawer/full-screen panel acceptable when needed |
| Section navigation | Left sidebar when appropriate | Reposition to available space | Inline section index, disclosure, or appropriately labeled local menu |
| Scrolling | Optional compact sticky navigation | Optional compact sticky navigation | Keep sticky controls minimal and content unobscured |

**Breakpoint principle:** Choose breakpoints based on actual content fit and interaction quality, not arbitrary device labels. Test responsive behavior at intermediate widths and browser zoom levels.

### 4. Interaction Requirements

#### Navigation links versus disclosure controls

- Use an **anchor/link** when activating an item directly navigates to a destination.
- Use a **button** when activating an item opens/closes a submenu without navigating.
- Prefer semantic HTML (`<nav>`, `<ul>`, `<li>`, `<a>`, `<button>`) for website navigation.
- Do not automatically add ARIA `role="menu"` or `role="menubar"` to conventional website navigation: those roles imply a specialized application-style keyboard interaction model.
- For submenu buttons, expose `aria-expanded` and associate the button with its submenu using `aria-controls` when appropriate.
- Keep hidden submenu items out of normal focus/navigation until expanded.
- Make `Escape` close an open submenu and return keyboard focus to its trigger when appropriate.
- Dismiss open overlays predictably when the user selects a destination or moves outside the interaction context.
- Avoid focus loss, focus traps in nonmodal menus, or unannounced major layout changes.

#### State model

Each expandable menu must have observable states:

1. **Closed:** Trigger is visible; descendants are not exposed as active controls.
2. **Open:** Trigger indicates expansion; descendants are visible and operable.
3. **Focused:** A visible focus indicator identifies the currently keyboard-focused control.
4. **Current:** The active route is identified independently of whether the menu is open.

Implement state consistently across mouse, touch, keyboard, and assistive technology use.

### 5. Accessibility and Measurable Quality Gates

These are supplementary implementation standards based on W3C guidance, beyond the specificity of the NN/g article.

| Criterion | Required or recommended gate |
|---|---|
| Text contrast | WCAG AA: **4.5:1** for normal-size text; **3:1** for qualifying large text. Check overlays and interactive states. |
| Pointer target size | WCAG 2.2 AA SC 2.5.8: generally **24 × 24 CSS px minimum**, subject to the standard's specific exceptions (including spacing). |
| Comfortable touch design | Aim for approximately **44 × 44 CSS px** for frequently touched controls, where possible; this also matches WCAG's enhanced target-size threshold, which is not the AA baseline. |
| Keyboard access | All links and disclosures reachable and operable without a mouse; clear focus indication; no keyboard trap. |
| Current-page identification | Expose clear visual current state; use `aria-current="page"` on the current page link where appropriate. |
| Disclosure state | Use a native button, `aria-expanded`, and a valid controlled submenu relationship where appropriate. |
| Input diversity | Do not rely on hover or precision pointer movement. |
| Responsive/zoom | Avoid hidden controls, obstructed content, or unusable menus under narrow layouts and zoom. |
| Motion | Keep animation brief and nonessential; honor reduced-motion preferences. |

Source guidance:
- W3C WCAG 2.2 target size: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum
- W3C WCAG 2.2 contrast: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum
- WAI-ARIA disclosure navigation example: https://www.w3.org/WAI/ARIA/apg/patterns/disclosure/examples/disclosure-navigation/

**Do not claim WCAG compliance merely because this checklist passes.** Full conformance requires checking all relevant success criteria, states, and content.

### 6. Recommended Design and Engineering Workflow

1. **Inventory destinations.** List pages, commands, common entry points, key audience groups, and frequent tasks.
2. **Classify the hierarchy.** Identify global, utility, section/local, and footer destinations; identify parent-child relationships.
3. **Validate language.** Replace organization jargon with labels users understand; test unclear category names.
4. **Choose the patterns.** Select visible desktop global navigation, relevant local navigation, and an appropriate mobile model. Use mega menus or routing pages only when structure warrants them.
5. **Design orientation.** Define active states, breadcrumbs where needed, and how deep-linked pages signal parent sections.
6. **Define interactions.** Document link-versus-disclosure behavior, opening and closing, keyboard access, touch access, focus, and submenu persistence.
7. **Apply readability and target requirements.** Check contrast, label scannability, spacing, hit areas, and consistency.
8. **Implement and test responsively.** Verify common screen widths, high zoom, long labels, localization, and nested content.
9. **Test realistic tasks.** Include locating a familiar item, discovering an unfamiliar item, moving between related pages, and entering directly on a deep page.
10. **Refine using evidence.** Track failed navigation attempts and usability observations rather than adding speculative visual effects.

### 7. QA Checklist

#### Visibility and placement

- [ ] Main desktop navigation is visible without an extra click when space permits.
- [ ] Primary, utility, local, and footer navigation are in predictable positions.
- [ ] Menu text is legible over every background and in every interactive state.
- [ ] Desktop expanded menus preserve page context instead of hiding the entire page.

#### Orientation and discoverability

- [ ] Current destination is unmistakably marked.
- [ ] Deep-linked pages indicate their section and parent hierarchy where relevant.
- [ ] Related content is reachable through section/local navigation when useful.
- [ ] Labels use audience terminology and predict their destinations accurately.
- [ ] Vertical menus are readily scannable, with key words early in labels.
- [ ] Deep hierarchies are exposed through manageable mega menus, drill-downs, or routing pages.
- [ ] Icons and images supplement—not replace—clear text.

#### Operation

- [ ] Interactive targets have adequate size and separation.
- [ ] Items with submenus visibly indicate expandability.
- [ ] Submenus work reliably through click, tap, and keyboard activation.
- [ ] Navigation does not rely on hover.
- [ ] Deep cascading hover flyouts are avoided.
- [ ] Sticky navigation helps on long pages without blocking content.
- [ ] High-frequency commands are reasonably close to their launch controls.
- [ ] Nonstandard navigation designs have clear task-based justification.

#### Accessibility and resilience

- [ ] Focus visibility, keyboard order, and submenu dismissal behave consistently.
- [ ] Navigation uses semantic links and buttons with appropriate states.
- [ ] Selected/current route states are accessible without relying solely on color.
- [ ] Mobile navigation and long labels remain usable at narrow widths and zoom.
- [ ] Touch targets and text contrast pass the applicable criteria.
- [ ] Reduced-motion preferences are respected where animation is used.
- [ ] The menu still works with a screen reader and without a pointer.

### 8. Practical Test Scenarios

Use a mixture of first-time and returning users, mouse, keyboard, touch, and assistive technology. Ask participants to:

1. Identify the site's main topic areas from a desktop entry page.
2. Enter on a deep article/product page, state their current section, and navigate one level up.
3. Find a specific task using only menu labels, without site search.
4. Compare two related sibling pages without returning to the home page.
5. Open a submenu using keyboard only, navigate its links, and close it safely.
6. Use the same navigation on a phone without accidental taps or hidden destinations.
7. Navigate a long page and reach another section without excessive scrolling.
8. Complete a common menu command and identify any unexpectedly difficult pointer travel.

**Observe:** Incorrect destination choices, missed categories, inability to identify current location, accidental menu dismissal, excessive clicks, target mis-taps, and keyboard/focus failures.

### 9. Instructions for an AI Coding Assistant

When asked to create or refactor navigation, follow this order:

1. Inspect existing routes, information architecture, breakpoints, layout, and component patterns before changing anything.
2. Preserve all existing destinations and permissions unless the task explicitly changes them.
3. Prefer visible desktop global navigation and familiar placement over novel interaction patterns.
4. Implement clear active states and section context for direct links and nested routes.
5. Use plain, specific labels and accessible, semantically correct controls.
6. Prefer click/tap disclosure over hover activation; do not introduce nested cascading flyouts.
7. Make desktop overlays contextual and mobile overlays intentionally responsive.
8. Implement keyboard, focus, `Escape`, expanded state, and screen-reader semantics.
9. Check contrast, touch-target sizing, clipping, long labels, and zoom behavior.
10. Test the relevant viewports and interaction types; report the acceptance checks performed.

**Do not:** invent new destinations; hide global desktop navigation solely for style; make menu items depend on hover; use icons without labels for important categories; introduce fullscreen desktop overlays without a demonstrated need; use ARIA `menu` roles for ordinary navigation without implementing that widget's interaction model; or replace standard navigation with decorative controls.

### 10. Definition of Done

A navigation implementation is complete when:

- Users can discover the main destinations, understand their current location, and move to related content.
- Navigation feels consistent across pages, widths, and input methods.
- All expandable items expose clear signifiers and work by keyboard, click, and touch.
- Readability and hit areas are verified, not assumed.
- Deep menus are manageable without hover traps or cascades.
- There are no known dead links, unintentional hidden options, obstructive sticky elements, or focus traps.
- The Section 7 checklist is reviewed, and any intentional exceptions are documented with rationale.
