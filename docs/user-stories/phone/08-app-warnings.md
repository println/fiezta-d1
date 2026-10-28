# App warnings

## PH-22 — Be warned when the phone is set up in a way that stops the app

> **As a** phone user
> **I want** the app to tell me when a phone setting will stop it from working
> **So that** I fix it before it cuts the music in the car, not after.

> **Given** battery saving restricts Fiezta D1
> **When** I open the app on the phone
> **Then** a warning shows on the home screen, right below the address bar, saying that battery
> saving can stop the music in long sessions
> **When** I tap it
> **Then** the phone's battery setting for Fiezta D1 opens.

> **Given** I removed the restriction
> **When** I come back to the app
> **Then** the warning is gone, without me closing it.

> **Given** the home screen shows a warning
> **Then** it has no button to close it: it leaves only when the setting is fixed.

> **Given** the menu on the address bar
> **Then** it has a warnings entry
> **When** I open it
> **Then** it lists every warning that is active now, each one opening its own setting
> **and** with none active it says everything is set up.

> **Given** a new kind of warning is added
> **Then** it shows on the home screen and in the warnings entry alike.

> **Given** I am driving
> **Then** no warning reaches me as a notification or on the car screen.

**There.** The battery warning opens the app's page in the phone's settings, where the battery
option lives on every phone, Xiaomi included. Battery saving is the first warning because it is
what stopped the music on 2026-10-25: after more than an hour of playback with the screen off,
the phone killed the page that plays.

## PH-23 — Be warned when an access the app needs is missing

> **As a** phone user
> **I want** the app to check every access it needs to work without errors and tell me which
> one is missing
> **So that** I grant it before the feature that depends on it fails.

> **Given** any access the app needs is denied or turned off
> **When** I open the app on the phone
> **Then** a warning shows on the home screen for each one, saying which access is missing and
> what stops working without it
> **When** I tap it
> **Then** the app asks for the access again, or opens its page in the phone's settings when it
> can no longer ask.

> **Given** I granted the access
> **When** I come back to the app
> **Then** that warning is gone.

> **Given** every access is granted
> **Then** no access warning shows.

> **Given** the app starts needing a new access
> **Then** it is checked and warned about like the others.

> **Given** a missing access
> **Then** its warning shows the same way as the warnings of PH-22, on the home screen and in the
> warnings entry.

**There.** The only access the user can take away today is the microphone, for voice search on
the phone and in the car; the app no longer asks for it on its own every time it opens; it
asks from the warning. The other permissions of the app are granted at install
and cannot be denied, and the playback notification needs no permission.
