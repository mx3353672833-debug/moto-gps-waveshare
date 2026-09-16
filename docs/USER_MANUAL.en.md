> **Language:** English · [中文](USER_MANUAL.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS Waveshare edition features and user manual

This manual corresponds to the repository's current Waveshare 1.75C firmware and iOS App. For a first
build, read the [buying and installation guide](WAVESHARE_DIY_GUIDE.en.md) first; for service
configuration see the [route gateway guide](GATEWAY_SETUP.en.md).

## 1. What the phone and the round display each do

The iPhone handles position, search, route preview and navigation computation; the round display
shows navigation, speed and heading over Bluetooth and sends touch actions back. Normal use requires
the phone to be nearby. Real search, route planning and rerouting use the phone's network connection,
and a personal hotspot is not required. The standalone GNSS and hotspot connectivity of the in-house
main board design are not part of this manual's Waveshare edition.

Choose the route on the phone before setting off; the round display is for checking the next action
and the distance. The current route source is the ordinary driving service, and it does not promise
automatic avoidance of motorcycle-prohibited roads; do your debugging and interaction while stopped.
Background reconnection and some route issues are still being verified; see
[Known issues](KNOWN_ISSUES.en.md) for details.

## 2. Power on, connection and power off

Power the device on; the round display plays the black-background white MOTO GPS boot screen. Open
MOTO GPS on the iPhone and the App scans for the device automatically; on the first connection, pair
and grant Bluetooth permission following the iOS prompts.

| Round-display state | Meaning | Next step |
| --- | --- | --- |
| Waiting for phone / CONNECT PHONE / WAITING FOR PHONE | No usable phone session has been established; the wording varies slightly with the firmware version | Open the App, check the Bluetooth permission, move closer to the device |
| CONNECTING / Establishing the connection | Discovering services and completing the handshake | Wait; if it does not change for a long time, look at the error in the App and retry |
| CONNECTED / Connection succeeded | A brief connection-success indication | Then it moves on to ready to ride or navigation |
| READY TO RIDE / Choose a destination on the phone | Connected, with no route currently displayed | Choose a destination on the phone |
| BUILDING ROUTE / Building the route | A route is being obtained | Wait for the phone to respond; if it fails, read the prompt on the phone |
| Navigation screen | A valid navigation state has been received | Check the selected route and the next action |

These states do not necessarily all stay on screen during every operation; the route preview can also
complete while the round display stays at ready to ride. The phone's physical Bluetooth connection and
the complete protocol handshake are two stages, so the standard is that both the App and the round
display are ready.

To stop using it, first end navigation in the App, then hold the device's PWR button for about
3 seconds to request a software power-off. The firmware also configures a PMIC hardware long-press
fallback at about 4 seconds; the actual power-down / wake behaviour on USB, battery and combined
supply still awaits full acceptance. To power it on again, follow Waveshare's device instructions.
BOOT is the download / debug button, not a page-switching or everyday power-off button.

## 3. Search and recent places

Open "Choose destination" and type at least two characters in "Where do you want to go?", for example
a mall, a station or an address. The App waits briefly for the input to settle before searching, so
you do not have to tap again after every character.

When a phone position is available, results prefer nearby places; when there are no nearby results it
falls back to a regional / nationwide search. When location authorisation is missing or location is
temporarily unavailable, the interface explains the search scope, and you must not read every result
as nearby. For places with the same name, confirm using the city, the address and the distance, then
tap "Set as destination".

"Recent searches" keeps at most 8 places, and selecting one plans a route to it again; "Clear" removes
the recent entries. What is saved is the place, not a permanently cached live distance or an old
route. If you pick the wrong destination, use "Change" to go back and select again.

## 4. Review candidate routes and start navigation

After you select a destination, the App shows "Generating candidate routes" and then moves on to
"Route overview". The whole-route map and the candidate cards list at most 3 routes, each with the
estimated time, the distance and a traffic summary. One or two routes returned is also a normal
result; it does not invent routes to fill the list.

1. First confirm the two ends of the map and the selected destination.
2. Tap different candidate cards to compare routes; "Full route" returns to the whole-route view.
3. Confirm the selected route, then tap "Start navigation". Selecting a place or browsing candidates
   alone does not send the route to the device as an active navigation in advance.
4. Once started, check the phone's navigation state and the round display's white route, action and
   distance.
5. If "No route was generated" appears, use "Retry" and check the location, the gateway and the
   network.

The first navigation currently uses the candidate you selected; later off-route and traffic updates
may request a new route. Some cross-city route and preview consistency issues have been recorded; if a
route obviously detours, stop relying on that result and record the reproduction conditions.

## 5. How to read the round-display navigation page

| Element on screen | What it expresses |
| --- | --- |
| Vehicle arrow in the middle | Reference for the current heading of the bike; the map moves and rotates around it |
| White line | The currently selected navigation route |
| Grey roads and buildings | Reference for the surroundings, sent by the phone as a local base-map window |
| Left-turn, right-turn, U-turn and similar icons | The next navigation action |
| Large numbers and m / km | The distance to the next action; not the Bluetooth distance between the round display and the phone |
| Circular route progress | A trip-progress indication when there is a valid route |
| Speed-limit sign | Shown only when trustworthy speed-limit data exists; its absence does not mean the road has no speed limit |

With no valid route, the action, the distance, the progress and the speed limit are hidden, to avoid
showing old guidance. Route progress, off-route confirmation, rerouting and the arrival state are
updated by the phone; a brief GPS drift does not necessarily trigger a reroute immediately. Traffic
conditions refresh on the shared core's cycle, and a route may be fetched again after the network
recovers; this is not a traffic stream that updates live every second.

The grey base map currently bundles Jinan data. Outside Jinan there may be only the selected white
route, with no equivalent road / building background; this does not mean that city cannot plan routes
at all. The offline base map also does not provide nationwide offline search and route computation.

## 6. Speedometer, heading and touch

Swipe left and right on the round display to switch pages. Swiping left cycles navigation →
speedometer → heading → music, and swiping right goes the other way; when the music capability is not
enabled the music page is skipped. The page indicator dots appear after a touch / page change and
retract after about 5 seconds of no operation. Use a fairly definite horizontal swipe; operating in
the middle of the round display is easier to recognise.

The speedometer page shows the speed and `km/h`. The speed depends on a valid position fix or on demo
input, and is not the same as the result of calibrating the vehicle's original instrument. When the
phone provides no valid data, do not treat a retained screen as the latest speed.

The heading page shows the angle and the eight-point compass direction; while riding, the phone's
positioning heading is the reference and the on-board sensor assists with relative turning. The
QMI8658 has no magnetometer, so when you are stopped and turn the device in place it must not be
treated as a calibrated true-north compass.

A long press on the screen no longer starts the on-device demo. The long-press demo behaviour in
older version records has been removed.

## 7. Apple Music control

First play a song that the current account can play in the iPhone's system "Music" app and allow MOTO
GPS to access the media library, then return to MOTO GPS and connect the round display. The music page
can show the track state and use the following buttons:

| Action | Result |
| --- | --- |
| Previous track | Sends a previous-track command to the system "Music" player |
| Play / pause | Toggles the current playback state |
| Next track | Sends a next-track command to the system "Music" player |

The round display is a remote control; it does not download songs, carry audio or replace headphones.
Whether playback is possible depends on the phone's music source, account, authorisation and playback
queue. Generic control of third-party players such as NetEase Cloud Music is not supported at
present, and there is no favourite / like action. If a button does not respond, first check whether
the phone's "Music" app can play at all, then check the media permission and the BLE state.

## 8. Demo navigation and ending navigation

On the phone's home page tap "Demo navigation". The demo uses the scenario from near Building D of
the Jinan Big Data Industry Base to near the Inspur headquarters, requests a live route first when
online and falls back to the built-in OSM track when that fails. Once you are in, the phone keeps
showing "demo", and the movement, speed and distance on screen are simulated input.

The demo suits checking turns, touch, the map and the connection at a desk. It cannot demonstrate
real-road accuracy, background operation with the screen locked or live traffic. To use a real
destination, first tap "End navigation" to leave the demo, then search again, select a route and
start.

After live navigation arrives, look at the phone's "You have arrived" prompt and tap
"End navigation" to end the current session; you can also end it manually partway. After stopping,
confirm that the round display has left the active-route state. Before leaving the phone, end any
navigation you no longer need, so that it does not keep consuming location and battery.

## 9. Screen lock, loss of network and disconnection

The App configures background location and BLE, but the current version still has known issues such
as waiting for the phone after leaving the original network environment, and background recovery. For
first use, do a short stationary verification and then extend the time gradually; do not infer
long-term stability from one success.

| Situation | Suggested action |
| --- | --- |
| The round display stops updating after the phone's screen locks | Stop, open the App and see whether it recovers in the foreground; check the "Always" location, precise location and Bluetooth permissions |
| The phone has no network | Online search / planning / rerouting are affected; do not assume the built-in Jinan base map replaces the route service |
| The round display loses power or goes out of Bluetooth range | Power it on again, move closer to the phone and wait for reconnection; if it is stuck, use "Retry" in the App |
| Force-quitting the App | Location and the connection may stop and you need to open it again; do not rely on automatic recovery after a force quit |
| Changing phones or hitting an old-pairing problem | Close the App / connection on the original phone first; handle the old pairing following the actual system prompts and do not erase the whole Flash at will |

## 10. First-use checklist

- [ ] The App opens normally on your own physical iPhone and its signing is valid.
- [ ] The round display can be connected after power-on, and the App and the round display states
  agree.
- [ ] The demo can be started, paged through and ended, and after exiting it does not keep showing the
  demo route.
- [ ] You have switched to your own HTTPS gateway and can search real places and get a route.
- [ ] The candidate destination, the selected route and the navigation after starting agree.
- [ ] You have checked recovery after a short screen lock, a device restart and a phone network
  switch.
- [ ] The music buttons you intend to use have been verified on both the phone and the round display.
- [ ] The power cable and the mount are reliable in the actual installation position and do not
  interfere with operating the vehicle.

Problem records are in [Known issues](KNOWN_ISSUES.en.md); for installation and signing troubleshooting
see the [DIY guide](WAVESHARE_DIY_GUIDE.en.md#8-frequently-asked-questions). This manual describes the
current implementation; resources such as the microphone and speaker that come with the hardware do
not mean this App already supports voice navigation or a voice assistant.
