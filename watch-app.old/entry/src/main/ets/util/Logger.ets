import hilog from '@ohos.hilog';
import { BusinessError } from '@ohos.base';

const DOMAIN = 0xA1A1;
const TAG = 'GTAlarm';

/**
 * Extract diagnostic info from a thrown value. HarmonyOS BusinessError has
 * non-enumerable code/message that JSON.stringify drops — this helper
 * formats them explicitly so logs are actually useful.
 */
export function describeError(e: Object | null | undefined): string {
  if (e === null || e === undefined) return 'null';
  const be = e as BusinessError;
  if (typeof be.code === 'number') {
    return `code=${be.code} msg=${be.message ?? ''}`;
  }
  if (e instanceof Error) {
    return `${e.name}: ${e.message}`;
  }
  try {
    return JSON.stringify(e);
  } catch (_) {
    return String(e);
  }
}

export class Logger {
  static i(message: string): void {
    hilog.info(DOMAIN, TAG, '%{public}s', message);
  }
  static w(message: string): void {
    hilog.warn(DOMAIN, TAG, '%{public}s', message);
  }
  static e(message: string): void {
    hilog.error(DOMAIN, TAG, '%{public}s', message);
  }
  static d(message: string): void {
    hilog.debug(DOMAIN, TAG, '%{public}s', message);
  }
  /** Log an error with extracted code/message rather than `{}`. */
  static err(prefix: string, e: Object | null | undefined): void {
    hilog.error(DOMAIN, TAG, '%{public}s', `${prefix}: ${describeError(e)}`);
  }
  /** Log a warning with extracted code/message rather than `{}`. */
  static warn(prefix: string, e: Object | null | undefined): void {
    hilog.warn(DOMAIN, TAG, '%{public}s', `${prefix}: ${describeError(e)}`);
  }
}
