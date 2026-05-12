// Pure LWW (last-write-wins) helpers — no system imports, no async.
// Mirrors the phone-side resolver: ties go to the incoming side per the
// sync-architecture §2 rule.
export default {
    shouldApply: function (incomingEpoch, localEpoch) {
        if (localEpoch === undefined || localEpoch === null) return true;
        return incomingEpoch >= localEpoch;
    },
    shouldSuppressForTombstone: function (incomingEpoch, tombstoneEpoch) {
        if (tombstoneEpoch === undefined || tombstoneEpoch === null) return false;
        return tombstoneEpoch >= incomingEpoch;
    },
};
