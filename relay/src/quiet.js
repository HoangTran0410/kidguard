// "The kid's phone went quiet" alert (owner 2026-11-19): a force-stopped KidGuard runs nothing and FCM cannot wake it,
// so the worker is the only one that notices. Pure, tested in test/contract.test.mjs.
export const QUIET_ALERT = 30 * 60e3;       // the phone syncs every <= 10 min even asleep (watchdog): 30 min = something is wrong
export const QUIET_BED = 12 * 3600e3;       // at bedtime a phone switched off is normal: only after this long (same as the parent app)
export const QUIET_MAX = 7 * 86400e3;       // a phone gone for a week: no late alert

/**
 * [heard] = last sign of life (ms), [alerted] = the `heard` already alerted for (0 = none), [bedtime] = the last state said
 * bedtime. Returns {alert: send now?, next: when to look again (ms, 0 = no need)}.
 */
export function quietStep(now, heard, alerted, bedtime) {
  if (!heard || alerted === heard) return { alert: false, next: 0 };
  const limit = bedtime ? QUIET_BED : QUIET_ALERT, quiet = now - heard;
  if (quiet >= QUIET_MAX) return { alert: false, next: 0 };
  if (quiet >= limit) return { alert: true, next: 0 };
  return { alert: false, next: heard + limit + 5000 };
}
