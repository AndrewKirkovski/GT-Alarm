// Pure last-write-wins helpers for the receive-side of the phone↔watch
// sync. Rules mirror the phone-side LwwResolver.kt; design-of-record is
// docs/sync-architecture.md §2.
//
// No HarmonyOS dependencies — keeps unit tests JVM-pure on the Hypium
// runner. Tombstone semantics live in Tombstones.isTombstonedPure;
// this class handles live-row LWW only.

export class LwwResolver {

  // Decide whether an incoming peer write should be applied to the local
  // row (or inserted, if no local row exists).
  //
  // Rules (sync-architecture §2):
  //   localEpoch undefined            → apply
  //   incoming > local                → apply
  //   tie (incoming == local)         → apply (incoming-wins tie-break)
  //   incoming < local                → ignore
  //
  // The tombstone tie-break ("local tombstone wins on tie") is enforced
  // separately by Tombstones.isTombstonedPure before this is consulted.
  static shouldApply(incomingEpoch: number, localEpoch: number | undefined): boolean {
    if (localEpoch === undefined) return true;
    return incomingEpoch >= localEpoch;
  }
}
