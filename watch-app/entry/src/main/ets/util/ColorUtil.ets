import { ColorMetrics } from '@kit.ArkUI';
import { Logger } from './Logger';

/**
 * `ColorMetrics.resourceColor(...)` is declared as throwing (BusinessError
 * 180003 + 401) — every call site inside an object-literal initializer (e.g.
 * `ArcButtonOptions`) trips the ArkTS strict "Function may throw exceptions.
 * Special handling is required" warning. Wrapping at the call site is
 * verbose; this helper centralises the fallback.
 *
 * On parse failure the helper logs and returns a fully transparent
 * ColorMetrics constructed via `ColorMetrics.rgba(0, 0, 0, 0)`. We use
 * `rgba` (not another `resourceColor` call) for the fallback specifically
 * because `rgba` does not declare any throws — otherwise the catch branch
 * itself would re-trigger the same warning we're trying to silence.
 */
export function safeResourceColor(hex: string): ColorMetrics {
  try {
    return ColorMetrics.resourceColor(hex);
  } catch (e) {
    Logger.warn('safeResourceColor: invalid hex=' + hex, e);
    return ColorMetrics.rgba(0, 0, 0, 0);
  }
}
