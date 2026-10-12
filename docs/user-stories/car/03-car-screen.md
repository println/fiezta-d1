# What the car screen can do

Every option below that the phone also sets is one setting, not a copy: changing it on the
phone reaches the car screen while it is open, and changing it on the car screen reaches the
phone's settings screen. See PH-8.

## CR-7 — Zoom

> **As a** driver
> **I want** to zoom the page
> **So that** I can read it from the driver's seat.

> **And** it can be changed from the phone in real time.

**There.**

## CR-8 — Bookmark a page from the car

> **As a** driver
> **I want** to bookmark the page I am on
> **So that** I can come back to it without typing.

> **And** the bookmark is shared with the phone.

**There.** The car's settings panel carries the button, and it shows whether the page already
is a bookmark.

## CR-9 — Browse the bookmarks on the car screen

> **As a** driver
> **I want** a screen that lists my bookmarks
> **So that** I can open one with a tap.

> **And** the list is shared with the phone.

**There.**

## CR-10 — Make a bookmark the home page in one tap

> **As a** driver
> **I want** to set the home page from the bookmarks screen with one tap
> **So that** I do not have to go through settings.

**There.** Each row has the button, the current home page is marked, and setting it confirms
with a message.

## CR-11 — Show the bookmarks bar

> **As a** driver
> **I want** to turn the bookmarks bar on and off
> **So that** I choose between quick access and screen space.

> **And** it can be changed from the phone in real time.

**There.**

## CR-12 — Search by voice and by text, on YouTube, Google, Bing and DuckDuckGo

> **As a** driver
> **I want** to search from the car screen, by voice or by typing, on YouTube, Google, Bing and
> DuckDuckGo
> **So that** I find what I want without the phone.

> **Given** the search screen
> **Then** the engines sit in a column that scrolls up and down, the search box stands out at
> the top, and below it a voice tile and a keyboard tile start each mode; the tile of the mode
> in use is highlighted, and the voice panel opens over the tiles.
> **And** the engine column and the voice tile follow the steering wheel (CR-19).

**There.** Closing the voice panel goes back to the tiles instead of opening the keyboard.

## CR-13 — Choose where the toolbar sits

> **As a** driver
> **I want** to put the toolbar where it suits me
> **So that** it does not sit on the wrong side of the screen.

> **Given** the toolbar settings
> **Then** it can sit on the driver side, the default (CR-19), or on the left, the right, the top
> or the bottom, and those four win over the driver side.
> **And** it can be changed from the phone in real time.

**There.**

## CR-14 — Hide the toolbar

> **As a** driver
> **I want** to hide the toolbar
> **So that** the page gets the whole screen.

> **And** it can be changed from the phone in real time.

**There.**

## CR-15 — The buttons on the car toolbar

> **As a** driver
> **I want** the toolbar to carry the actions I use while driving
> **So that** each one is a single tap away.

> **Given** the car screen
> **Then** the toolbar has home, reload, back, search and settings.

**There.** Home goes to the home page set in PH-9 / CR-10, back walks the history, and the
search button opens the search screen already listening, with the engine that fits the site;
the same screen takes typed text and offers the other engines, and it is also reachable from
settings.

## CR-19 — Keep the controls on the driver's side

> **As a** driver
> **I want** the car screen to follow the side the steering wheel is on
> **So that** the buttons I use while driving are close to me, not on the passenger's side.

> **Given** the toolbar is set to the driver side, the default
> **When** the car says the steering wheel is on the right
> **Then** the toolbar sits on the right, and the screen mirrors: with the toolbar on top or at
> the bottom its buttons run from the driver's side, the settings panel opens on the driver's
> side and the bookmarks bar goes to the passenger's side; the fullscreen controls mirror too.

> **Given** the search screen, on either side
> **Then** the voice tile sits on the driver's side, because voice is the search used while
> driving.
> **And** the engines sit in a column on the driver's side, one large tile each, so an engine
> can be picked with one tap while driving.

> **Given** the settings on the phone
> **Then** the steering wheel side can be set to automatic, the default, to the left or to the
> right; the car settings do not have it.
> **And** left and right override what the car says, so the right-hand layout can be tried in a
> car with the steering wheel on the left, and everything that follows the steering wheel
> follows the side chosen.
> **And** it applies live: changing it on the phone mirrors the open car screen at once,
> without reconnecting the car.

> **Given** the video is in full screen and its controls are showing
> **Then** they sit in the top corner on the driver's side, with Exit at the end nearest the
> driver.
> **And** the aspect ratios read Contain, Fill, Cover on either side.

**Partly there.** The mirroring and the steering wheel side setting are there. With the
setting on automatic, a car that does not say keeps the last side it learned; with none learned,
the wheel is taken to be on the left, which is exactly the layout from before the option existed.

## CR-20 — Drag the full-screen controls out of the way

> **As a** driver
> **I want** to drag the full-screen controls out of the way
> **So that** they do not cover the part of the video I am watching.

> **Given** the video is in full screen and its controls are showing
> **Then** the controls carry a handle at the end away from Exit, big enough for a finger.
> **When** I drag the handle
> **Then** the controls follow my finger anywhere inside the video area and never leave it.
> **And** while I hold the handle nothing changes state: neither the controls nor the toolbar hide.
> Only when I let go do both start counting down to hide, together.
> **And** a tap on the video shows or hides the controls and the toolbar together, never one
> each way.
> **And** when they hide, the controls slide out through the edge of the video nearest to them,
> and come back in from there.
> **And** if the controls end up over anything else on the screen, such as the toolbar coming
> back, they jump out to the nearest free spot in the video area.
> **And** the buttons still only react to a tap.
> **And** the controls come back where I left them the next time the video goes full screen, even
> after the car reconnects or the app restarts, like the menu bubble; changing the driver side or
> the toolbar position sends them back to the driver's corner.

**Built, not yet confirmed.** Waiting for the user's check on the DHU; not tried in the car.

## CR-22 — The settings panel closes by itself

> **As a** driver
> **I want** the settings panel to close when I stop using it
> **So that** it does not stay over the page if I forget it open.

> **Given** the settings panel is open
> **When** a minute goes by with no touch and no steering wheel key on it
> **Then** it closes, and the toolbar goes back to hiding by itself if it is set to (CR-14).
> **And** every touch or key on the panel starts the minute again.
> **And** a screen opened from the panel, such as bookmarks or search, does not close by itself.

**Built, not yet confirmed.** Not tried on the DHU or in the car.

## CR-23 — Each site has its colour on the bookmarks bar

> **As a** driver
> **I want** every bookmark of a site to carry that site's colour
> **So that** I find a site on the bar by its colour, without reading.

> **Given** the bookmarks bar on the car screen
> **Then** all the bookmarks of one domain have the same colour, whatever their titles.
> **And** the colour is the accent of the site's icon, never its white, black or grey: red for
> YouTube, purple for Twitch, blue for Facebook.
> **And** a site whose icon has many colours and none that stands out, such as Google, is white,
> with dark initials.
> **And** a bright accent is dimmed so the white initials stay readable.
> **And** the colour is learned when the site is opened on the car screen and the bar picks it up
> at once; until then, or when the icon has no accent, the domain gets a colour of the app's own
> palette, always the same one.

**Built, not yet confirmed.** Seen on the emulator learning YouTube and Google; not tried in the
car. The phone's bookmark list keeps its colours by initials.
