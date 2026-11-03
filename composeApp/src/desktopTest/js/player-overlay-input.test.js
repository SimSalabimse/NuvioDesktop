const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const playerUi = path.join(__dirname, "../../desktopMain/resources/player-ui");
const input = require(path.join(playerUi, "player-overlay-input.js"));

test("vertical wheel scrolls a horizontal chip row", () => {
  assert.equal(input.horizontalRowScrollDelta(0, 48), 48);
  assert.equal(input.horizontalRowScrollDelta(0, -12), -12);
  assert.equal(input.horizontalRowScrollDelta(3, 40), 40);
  assert.equal(input.horizontalRowScrollDelta(40, 3), 0);
  assert.equal(input.horizontalRowScrollDelta(16, 0), 0);
  assert.equal(input.horizontalRowScrollDelta(0, 0), 0);
});

test("right click activates episode and source rows only", () => {
  assert.equal(input.contextMenuActivatesRow("track-row episode-row selected"), true);
  assert.equal(input.contextMenuActivatesRow("track-row stream-row source-row"), true);
  assert.equal(input.contextMenuActivatesRow("track-row audio-track-row"), false);
  assert.equal(input.contextMenuActivatesRow("track-row subtitle-language-row"), false);
  assert.equal(input.contextMenuActivatesRow(""), false);
});

test("arrows move focus for buttons and panel controls, not the volume slider", () => {
  assert.equal(input.arrowMovesFocus({ tagName: "BUTTON", inOpenPanel: false, isPanelControl: false, isTextEntry: false }), true);
  assert.equal(input.arrowMovesFocus({ tagName: "INPUT", inOpenPanel: true, isPanelControl: true, isTextEntry: false }), true);
  assert.equal(input.arrowMovesFocus({ tagName: "INPUT", inOpenPanel: false, isPanelControl: false, isTextEntry: false }), false);
  assert.equal(input.arrowMovesFocus({ tagName: "INPUT", inOpenPanel: true, isPanelControl: true, isTextEntry: true }), false);
  assert.equal(input.arrowMovesFocus({ tagName: "DIV", inOpenPanel: false, isPanelControl: false, isTextEntry: false }), false);
});

test("space and enter activate a focused button", () => {
  assert.equal(input.activatesFocusedControl("BUTTON"), true);
  assert.equal(input.activatesFocusedControl("a"), true);
  assert.equal(input.activatesFocusedControl("INPUT"), false);
  assert.equal(input.activatesFocusedControl("DIV"), false);
});

test("arrow and tab indexes wrap inside the open group", () => {
  assert.equal(input.focusStep("ArrowLeft"), -1);
  assert.equal(input.focusStep("ArrowUp"), -1);
  assert.equal(input.focusStep("ArrowRight"), 1);
  assert.equal(input.focusStep("ArrowDown"), 1);
  assert.equal(input.focusStep("KeyK"), 0);
  assert.equal(input.wrappedIndex(4, 0, -1), 3);
  assert.equal(input.wrappedIndex(4, 3, 1), 0);
  assert.equal(input.wrappedIndex(0, 0, 1), -1);
  assert.equal(input.tabTrapIndex(3, 2, false), 0);
  assert.equal(input.tabTrapIndex(3, 0, true), 2);
  assert.equal(input.tabTrapIndex(3, -1, false), 0);
  assert.equal(input.tabTrapIndex(3, -1, true), 2);
});

test("keyboard focus ring stays on buttons and action buttons are not opted out", () => {
  const css = fs.readFileSync(path.join(playerUi, "controls.css"), "utf8");
  assert.match(css, /button:focus-visible,\s*\n\.scrub:focus-visible,\s*\ninput:focus-visible:not\(\.volume-slider\)/);
  assert.match(css, /outline:\s*2px solid var\(--theme-focus\)/);
  assert.doesNotMatch(css, /button\.action:focus-visible\s*\{[^}]*outline:\s*none/);
});

test("overlay page loads the input helpers before controls", () => {
  const html = fs.readFileSync(path.join(playerUi, "controls.html"), "utf8");
  const helper = html.indexOf("player-overlay-input.js");
  const controls = html.indexOf("controls.js");
  assert.ok(helper > 0 && helper < controls);
  const bridge = fs.readFileSync(
    path.join(__dirname, "../../desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt"),
    "utf8",
  );
  assert.match(bridge, /player-overlay-input\.js/);
  const controlsJs = fs.readFileSync(path.join(playerUi, "controls.js"), "utf8");
  assert.match(controlsJs, /PlayerOverlayInput\.horizontalRowScrollDelta/);
  assert.match(controlsJs, /PlayerOverlayInput\.contextMenuActivatesRow/);
  assert.match(controlsJs, /PlayerOverlayInput\.tabTrapIndex/);
  assert.match(controlsJs, /PlayerOverlayInput\.arrowMovesFocus/);
});
