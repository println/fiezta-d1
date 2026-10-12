# Extending what the car can do

## CR-16 — Wire plain pages to the car's controls

> **As a** driver
> **I want** an ordinary web page to answer the car's controls
> **So that** a site that is not YouTube is usable while driving.

> **Given** a plugin for the site
> **Then** the car's controls reach the page through it.

**There, and it works.** This is what the plugin engine is for: the page's address picks the
plugin, and the plugin says what the wheel's events do there.

## CR-17 — Have other search engines

> **As a** driver
> **I want** to add search engines beyond the ones that ship
> **So that** I search where I want.

**The structure is there; the installer is not.** An engine is already a file and not code — an
OpenSearch description plus an entry in the lookup order — which is why `youtube`, `google`,
`bing` and `duckduckgo` are data today. What is missing is a way to put one there from outside: engines are read from
the APK only, never from the device, unlike plugins. Its name and icon are still bound to the
id inside the app, so an engine the app does not know is refused when loaded; that has to give
way too.

## CR-18 — Reach any site from the car

> **As a** driver
> **I want** to open any site from the car
> **So that** nothing is out of reach from the driver's seat.

> **Given** the car's search screen
> **Then** a full address opens that address,
> **and** a keyword searches it on the engine of my choice,
> **and** either can be given by typing or by voice.

**There.**

## CR-24 — The car tells me what a plugin just did

> **As a** driver
> **I want** a short notice on the car screen when a plugin acts on the page
> **So that** I know why the page changed without having touched it.

> **Given** YouTube in the car and plugin feedback on, the default
> **When** an ad is skipped
> **Then** the screen says so, once for that ad.
> **When** the page is scrolled back to the player after a track change
> **Then** the screen says so, once for that track.
> **When** a video that started muted gets its sound back
> **Then** the screen says so.
> **And** keeping YouTube from asking whether I am still watching (CR-21) is never announced.

> **Given** YouTube in the car and plugin feedback on
> **When** I press a key of the steering wheel and the plugin acts on it
> **Then** the screen says what it did: next or previous video, restarting the video, ten
> seconds forward or back, skipping the ad, and on shorts the next or the previous short.
> **And** a key that had nothing to do, like "next" on the last short, says nothing.

> **Given** plugin feedback is off (PH-13)
> **Then** the plugins act the same and the screen says nothing.

> **Given** a feature of a plugin is off, or its requirement is not met
> **Then** it does not act and there is no notice for it.

**There, not seen on the car yet.** The notices are in English whatever the language of the
app.
