(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) root.PlayerOverlayInput = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const rowMarkers = ["episode-row", "source-row"];

  function classTokens(className) {
    return String(className || "").split(/\s+/).filter(Boolean);
  }

  function contextMenuActivatesRow(className) {
    const tokens = classTokens(className);
    return rowMarkers.some(marker => tokens.includes(marker));
  }

  // Vertical wheel over a horizontal chip row. A mostly-horizontal gesture
  // returns 0 so the row's own overflow-x scroll can handle it.
  function horizontalRowScrollDelta(deltaX, deltaY) {
    const x = Number(deltaX) || 0;
    const y = Number(deltaY) || 0;
    if (y === 0) return 0;
    if (Math.abs(y) < Math.abs(x)) return 0;
    return y;
  }

  function arrowMovesFocus(options) {
    const opts = options || {};
    if (opts.isTextEntry) return false;
    const tag = String(opts.tagName || "").toUpperCase();
    if (tag === "BUTTON") return true;
    return Boolean(opts.inOpenPanel && opts.isPanelControl);
  }

  function activatesFocusedControl(tagName) {
    const tag = String(tagName || "").toUpperCase();
    return tag === "BUTTON" || tag === "A";
  }

  function focusStep(code) {
    if (code === "ArrowLeft" || code === "ArrowUp") return -1;
    if (code === "ArrowRight" || code === "ArrowDown") return 1;
    return 0;
  }

  function wrappedIndex(length, index, step) {
    const count = Number(length) || 0;
    if (count <= 0 || !step) return -1;
    const current = index < 0 ? (step > 0 ? -1 : 0) : index;
    return (current + step + count) % count;
  }

  function tabTrapIndex(length, index, shiftKey) {
    const count = Number(length) || 0;
    if (count <= 0) return -1;
    if (index < 0) return shiftKey ? count - 1 : 0;
    return wrappedIndex(count, index, shiftKey ? -1 : 1);
  }

  return {
    contextMenuActivatesRow,
    horizontalRowScrollDelta,
    arrowMovesFocus,
    activatesFocusedControl,
    focusStep,
    wrappedIndex,
    tabTrapIndex,
  };
});
