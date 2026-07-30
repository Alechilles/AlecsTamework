# Command Radial Menu Graphics Refresh

## Goal

Update Tamework's command radial menu to use the active raster artwork from
`alecs-radial-menu` without adopting its abandoned vector experiment.

## Scope

- Copy the eight `Default/Cropped` slice triplets (`Default`, `Hover`, and
  `Pressed`) into Tamework's private UI asset namespace.
- Copy the `Default/CommandWheelCenterPanel.png` center graphic.
- Update `TameworkCommandRadialMenu.ui` to reference those assets, preserving
  existing Tamework event selectors, text, and command behavior.
- Map Tamework's logical wheel slots to the active artwork's exported order:
  `6, 7, 0, 1, 2, 3, 4, 5`.

## Non-goals

- Do not copy `Vector/` assets or add vector render-mode logic.
- Do not copy the uncropped `Default/CommandWheelSlice*` images. The standalone
  radial menu uses those only to detect a generic full-wheel texture set and to
  derive hit bounds; Tamework's fixed wheel anchors do not need them.
- Do not alter command-item configuration, selections, events, or labels.

## Validation

- Add a focused resource/UI test that asserts the new cropped textures and
  center panel exist and the Tamework UI references their expected paths.
- Run the focused test and then `./mvnw test`.
