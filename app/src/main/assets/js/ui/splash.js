// The opening animation's second half. The first half is CSS: the logo assembles in the
// middle of the screen. Then this moves it to where the logo sits in the top bar, shrinking
// it to that size while the cover behind it clears, and hands over to the real logo.

/** When the circles have come to rest (see .splash-side in app.css). */
const ASSEMBLED_MS = 1050;
const LANDING_MS = 520;

/** The logo's drawing spans 312 of the animation's 512 units; both are centred on the same point. */
const LOGO_UNITS = 312;
const SPLASH_UNITS = 512;

export function playSplash() {
  const splash = document.getElementById('splash');
  const logo = document.getElementById('logoGlyph');
  if (!splash) return;
  if (!logo || !splash.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    splash.remove();
    return;
  }
  logo.style.opacity = '0';
  const finish = () => {
    logo.style.opacity = '';
    splash.remove();
  };
  setTimeout(() => {
    try {
      const drawing = splash.querySelector('svg');
      const from = drawing.getBoundingClientRect();
      const to = logo.getBoundingClientRect();
      const scale = (to.width / LOGO_UNITS) / (from.width / SPLASH_UNITS);
      const dx = (to.left + to.width / 2) - (from.left + from.width / 2);
      const dy = (to.top + to.height / 2) - (from.top + from.height / 2);
      const timing = { duration: LANDING_MS, easing: 'cubic-bezier(0.5, 0, 0.2, 1)', fill: 'forwards' };
      splash.animate([{ backgroundColor: getComputedStyle(splash).backgroundColor }, { backgroundColor: 'transparent' }], timing);
      const landing = drawing.animate(
        [{ transform: 'none' }, { transform: `translate(${dx}px, ${dy}px) scale(${scale})` }],
        timing
      );
      landing.finished.then(finish, finish);
    } catch (_) {
      finish();
    }
  }, ASSEMBLED_MS);
}
