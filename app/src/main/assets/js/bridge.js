export function callBridge(method, ...args) {
  const bridge = window.Android;
  if (!bridge || typeof bridge[method] !== 'function') return undefined;
  try {
    return bridge[method](...args);
  } catch (error) {
    console.warn(`Android.${method} failed`, error);
    return undefined;
  }
}
