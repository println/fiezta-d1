# Phone user stories

The phone side of the app, as the user stated it. Ids are `PH-<n>` and follow the order the
stories were given, so `PH-6` is always the same story wherever it is cited. Each story carries
what already works today, which is the detail the app must not lose.

| Story | File |
| --- | --- |
| PH-1 Open the app the way Firefox Focus does | [Browsing](01-browsing.md) |
| PH-2 Open a web page in the car from a button | [Driving the car from the phone](05-car-from-phone.md) |
| PH-3 Erase the page from the bin button | [Browsing](01-browsing.md) |
| PH-4 See the connection and the blocker at a glance | [Address bar](02-address-bar.md) |
| PH-5 Tracker/ad blocking per site | [Privacy](03-privacy.md) |
| PH-6 See that the site is in desktop mode | [Address bar](02-address-bar.md) |
| PH-7 Bookmark the page and see that it is bookmarked | [Address bar](02-address-bar.md) |
| PH-8 Set the car's options from the phone, live | [Driving the car from the phone](05-car-from-phone.md) |
| PH-9 Set the home page from the phone | [Driving the car from the phone](05-car-from-phone.md) |
| PH-10 Back, forward, share and reload | [Browsing](01-browsing.md) |
| PH-11 Manage the bookmarks | [Bookmarks](04-bookmarks.md) |
| PH-12 Open in another browser | [Browsing](01-browsing.md) |
| PH-13 Have and manage plugins | [Plugins](06-plugins.md) |
| PH-14 Manage site data | [Privacy](03-privacy.md) |
| PH-15 Manage the privacy options | [Privacy](03-privacy.md) |
| PH-16 Legacy CarStream hack for unlocked phones | [Settings](07-settings.md) |
| PH-17 See the about screen | [Settings](07-settings.md) |
| PH-18 Browse without redirect errors | [Browsing](01-browsing.md) |
| PH-19 Speak to the assistant on the phone and have the car obey | [Driving the car from the phone](05-car-from-phone.md) |
| PH-20 Donate from Settings | [Settings](07-settings.md) |
| PH-21 Know when a new version is out | [Settings](07-settings.md) |
| PH-22 Be warned when the phone is set up in a way that stops the app | [App warnings](08-app-warnings.md) |
| PH-23 Be warned when an access the app needs is missing | [App warnings](08-app-warnings.md) |

## Not met yet

- **PH-6** — the desktop-mode icon in the address bar. The feature itself works and is shared
  with the car; only the indicator is missing.
- **PH-15** — the master switch. The global blocking switch is only a default for untouched
  sites today; it neither overrides nor freezes the per-site choices.
- **PH-18** — not verified. Nobody has checked ordinary browsing against it.
- **PH-19** — wanted for the future; nothing of it exists.
- **PH-20** — the donate page is not published on the site yet; the button already opens it.

## Shared with the car

Tracker/ad blocking per site (PH-5), desktop mode per site (PH-6) and bookmarks (PH-7) are one
set of data, not a phone copy: what the phone changes is what the car reads.
