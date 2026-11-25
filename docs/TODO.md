# Follow-ups

- [ ] Generate a Trainer review after a workout is saved when Trainer is enabled. Persist the result by workout ID and show it on revisit. Retain **Regenerate** and keep the previous review if generation fails. Run generation independently of local saving and workout upload.

## Watch menu and media controls

- [x] Replace the main Down shortcut with a menu for History, Settings and Media Control.
- [x] Move walk/run auto-detection to Settings and keep workout controls on Select.
- [x] Connect Previous, Play/Pause and Next to the phone's media session through PebbleKit 2.
- [x] Rebuild and bundle watch app v1.9.4 for Aplite, Basalt, Chalk, Diorite and Emery.
- [x] Android debug build and 25 unit tests passed.
- [x] Emery emulator renders Home, Menu, History, Settings and Media Control. Back returns to the menu and then Home. Simulated workout navigation also returns from Media Control to the workout screen.
- [ ] Verify Next, Play/Pause and Previous with a paired Pebble and an active Android player during a workout. Install the matching phone companion and v1.9.4 watch bundle together. The bundled watch app declares the production package `org.tdgs.ph`.
