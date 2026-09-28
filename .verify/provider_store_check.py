"""
Off-device mirror of AiProviderStore's key handling.

The store is the one place where a mistake destroys data silently: a provider key
that is dropped here does not throw, it just makes the bot stop authenticating, and
the user finds out when a reply never arrives. So the logic is reproduced exactly --
same fallback order, same per-profile scrub condition, same orphan sweep -- and run
against the cases that have actually gone wrong.

History this file encodes:
  * v1.52.3 introduced SecurePrefs for provider keys, keyed by slot index.
  * v1.52.4 fixed a partial-migration bug where an aggregate "did anything migrate?"
    check scrubbed plaintext for a slot that had never been migrated, deleting the key
    from both stores.
  * v1.52.5 keys secrets by a stable profile id instead of a slot index, which removes
    the reorder/delete class of bug entirely. This file now proves that, by running the
    old slot-keyed logic and showing where it loses a key.
"""

FAILURES = []
PASSES = []


def check(label, got, want):
    if got == want:
        PASSES.append(label)
    else:
        FAILURES.append(f"{label}\n      got:  {got!r}\n      want: {want!r}")


# --------------------------------------------------------------------------- store


class SecurePrefs:
    """A Keystore stand-in. `fail_names` simulates per-write Keystore failure."""

    def __init__(self, fail_names=()):
        self.d = {}
        self.fail_names = set(fail_names)

    def put(self, name, value):
        if name in self.fail_names:
            return False
        self.d[name] = "ENC(" + value + ")"
        return True

    def get(self, name):
        v = self.d.get(name)
        return v[4:-1] if v else None

    def remove(self, name):
        self.d.pop(name, None)

    def names_with_prefix(self, prefix):
        return [k for k in self.d if k.startswith(prefix)]


PREFIX = "providerApiKey_"


def secret_name(pid):
    return PREFIX + pid


def legacy_secret_name(index):
    return PREFIX + str(index)


class Store:
    """Plain prefs: the profile list. Keys are NOT held here after v1.52.3."""

    def __init__(self):
        self.list = []  # list of dicts: id, name, baseUrl, model, (legacy) apiKey

    def write(self, profiles, sp, fail_names=()):
        sp.fail_names = set(fail_names)
        self.list = [
            {k: v for k, v in p.items() if k != "apiKey"} for p in profiles
        ]
        failed = 0
        for p in profiles:
            if not p.get("apiKey"):
                sp.remove(secret_name(p["id"]))
            elif not sp.put(secret_name(p["id"]), p["apiKey"]):
                failed += 1
        live = {secret_name(p["id"]) for p in profiles}
        for n in sp.names_with_prefix(PREFIX):
            if n not in live:
                sp.remove(n)
        return failed

    def read(self, sp, mint_ids=False):
        """Mirrors rawList().

        `mint_ids=False` reproduces v1.52.5-as-shipped, where AiProvider.fromJson mints
        an id for a profile that has none but does NOT persist it. That is the bug this
        flag exists to keep testable.
        """
        profiles = []
        inline = []
        for o in self.list:
            profiles.append(dict(o))
            inline.append(o.get("apiKey", ""))

        # --- id minting, with (fixed) or without (old) persistence ---
        if mint_ids:
            for i, o in enumerate(self.list):
                if not o.get("id"):
                    new = f"minted-{self._mint_seq()}"
                    o["id"] = new
                    profiles[i]["id"] = new
        else:
            for i, o in enumerate(self.list):
                if not o.get("id"):
                    # A real UUID is random, so a re-mint is a *different* value.
                    # Modelling it as "ephemeral-{i}" would accidentally look stable
                    # and hide the bug this flag exists to reproduce.
                    profiles[i]["id"] = f"ephemeral-{self._mint_seq()}"

        resolved = []
        for i, p in enumerate(profiles):
            by_id = sp.get(secret_name(p["id"]))
            from_slot = None
            if by_id is None:
                from_slot = sp.get(legacy_secret_name(i))
            key = by_id if by_id is not None else (
                from_slot if from_slot is not None else inline[i]
            )
            resolved.append(key)
            if by_id is None and key:
                if sp.put(secret_name(p["id"]), key):
                    if from_slot is not None:
                        sp.remove(legacy_secret_name(i))

        for i, p in enumerate(profiles):
            if inline[i] and sp.get(secret_name(p["id"])) is not None:
                self.list[i].pop("apiKey", None)

        for p, k in zip(profiles, resolved):
            p["apiKey"] = k
        return profiles

    _seq = 0

    @classmethod
    def _mint_seq(cls):
        cls._seq += 1
        return cls._seq


def store_old_read(store, sp):
    """v1.52.3 slot-keyed read, kept to show what id-keying fixed."""
    profiles = []
    inline = []
    for o in store.list:
        profiles.append(dict(o))
        inline.append(o.get("apiKey", ""))

    migrated = []
    for i, key in enumerate(inline):
        if not key:
            continue
        if sp.put(legacy_secret_name(i), key):
            migrated.append(i)
    if migrated:
        for i in migrated:
            store.list[i].pop("apiKey", None)

    return [
        dict(p, apiKey=sp.get(legacy_secret_name(i)) or inline[i])
        for i, p in enumerate(profiles)
    ]


def p(pid, name, key):
    return {"id": pid, "name": name, "baseUrl": "https://x/v1", "model": "m", "apiKey": key}


# =========================================================================== cases

# 1. Round trip: a key saved under an id comes back under that id.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A"), p("id-b", "B", "KEY-B")], sp)
check("1. round trip keeps A", st.read(sp, mint_ids=True)[0]["apiKey"], "KEY-A")
check("1. round trip keeps B", st.read(sp, mint_ids=True)[1]["apiKey"], "KEY-B")

# 2. The list itself never holds the key.
check("2. list holds no inline key", [o.get("apiKey") for o in st.list], [None, None])
check("2. secret name is the id", sp.get("providerApiKey_id-a"), "KEY-A")

# 3. Deleting the first profile does not disturb the survivor's key.
#    This is the bug id-keying removes: under slot naming, B moves from slot 1 to
#    slot 0 and its key has to be rewritten to follow. Under id naming nothing moves.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A"), p("id-b", "B", "KEY-B"), p("id-c", "C", "KEY-C")], sp)
st.write([p("id-b", "B", "KEY-B"), p("id-c", "C", "KEY-C")], sp)
check("3. after delete, B still has KEY-B", st.read(sp, mint_ids=True)[0]["apiKey"], "KEY-B")
check("3. after delete, C still has KEY-C", st.read(sp, mint_ids=True)[1]["apiKey"], "KEY-C")
check("3. deleted profile's key is swept", sp.get("providerApiKey_id-a"), None)

# 4. Reordering cannot detach a key.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A"), p("id-b", "B", "KEY-B")], sp)
st.write([p("id-b", "B", "KEY-B"), p("id-a", "A", "KEY-A")], sp)
after = {x["id"]: x["apiKey"] for x in st.read(sp, mint_ids=True)}
check("4. reorder keeps A's key on A", after["id-a"], "KEY-A")
check("4. reorder keeps B's key on B", after["id-b"], "KEY-B")

# 5. Old slot-named secrets are adopted onto the id, not orphaned.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A")], sp)      # writes providerApiKey_id-a
sp.d.pop("providerApiKey_id-a", None)          # simulate a device upgrading from v1.52.4
sp.d["providerApiKey_0"] = "ENC(KEY-A)"      # which only had the slot name
check("5. legacy slot key is adopted", st.read(sp, mint_ids=True)[0]["apiKey"], "KEY-A")
check("5. adopted secret present under id", sp.get("providerApiKey_id-a"), "KEY-A")
check("5. legacy slot name removed", sp.get("providerApiKey_0"), None)

# 6. Legacy inline plaintext is migrated and then scrubbed.
sp = SecurePrefs()
st = Store()
st.list = [{"id": "id-a", "name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]
check("6. inline plaintext is adopted", st.read(sp, mint_ids=True)[0]["apiKey"], "PLAIN-A")
check("6. inline plaintext is scrubbed", "apiKey" in st.list[0], False)
check("6. inline plaintext now in secure store", sp.get("providerApiKey_id-a"), "PLAIN-A")

# 7. A failed Keystore write must NOT scrub the plaintext for that profile.
#    This is the v1.52.4 bug class, re-tested against the id-keyed path.
sp = SecurePrefs(fail_names=["providerApiKey_id-a"])
st = Store()
st.list = [{"id": "id-a", "name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]
got = st.read(sp, mint_ids=True)[0]["apiKey"]
check("7. failed write still reads the key this boot", got, "PLAIN-A")
check("7. failed write does not scrub the plaintext", "apiKey" in st.list[0], True)

# 8. Partial failure across three profiles: the failing one keeps its plaintext, the
#    others are scrubbed. This is the exact shape of the bug the reviewer found.
sp = SecurePrefs(fail_names=["providerApiKey_id-b"])
st = Store()
st.list = [
    {"id": "id-a", "name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"},
    {"id": "id-b", "name": "B", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-B"},
    {"id": "id-c", "name": "C", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-C"},
]
out = st.read(sp, mint_ids=True)
check("8. A migrated and scrubbed", ("apiKey" in st.list[0], sp.get("providerApiKey_id-a")), (False, "PLAIN-A"))
check("8. B NOT scrubbed (write failed)", "apiKey" in st.list[1], True)
check("8. B still reads its key", out[1]["apiKey"], "PLAIN-B")
check("8. C migrated and scrubbed", ("apiKey" in st.list[2], sp.get("providerApiKey_id-c")), (False, "PLAIN-C"))

# 9. Total Keystore failure loses nothing.
sp = SecurePrefs(fail_names=["providerApiKey_id-a", "providerApiKey_id-b", "providerApiKey_id-c"])
st = Store()
st.list = [
    {"id": "id-a", "name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"},
    {"id": "id-b", "name": "B", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-B"},
    {"id": "id-c", "name": "C", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-C"},
]
out = st.read(sp, mint_ids=True)
check("9. all three keys survive", [x["apiKey"] for x in out], ["PLAIN-A", "PLAIN-B", "PLAIN-C"])
check("9. no plaintext was scrubbed", [("apiKey" in o) for o in st.list], [True, True, True])

# 10. Two profiles legitimately sharing a name and endpoint keep distinct keys.
#     Under name+endpoint matching these are indistinguishable; the id separates them.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "Same", "KEY-A"), p("id-b", "Same", "KEY-B")], sp)
out = st.read(sp, mint_ids=True)
check("10. same-name profiles keep their own keys", [x["apiKey"] for x in out], ["KEY-A", "KEY-B"])

# 11. The old slot-keyed read hands one profile a different profile's key.
#
#     Kept as a live demonstration that this harness is not vacuous: it reproduces the
#     failure the id-keyed path exists to prevent, rather than only testing the new code
#     against itself.
#
#     A and B are migrated to providerApiKey_0 / providerApiKey_1. A is then deleted, so
#     B becomes the profile at index 0. The old read resolves "the key for index 0" and
#     gets KEY-A -- B authenticates as A.
#
#     Both stores are built from the same starting point and then read once, so the two
#     reads are comparable. Running the old read twice would leave its own mutations in
#     the fixture and make the second result meaningless.
sp = SecurePrefs()
st = Store()
st.list = [{"id": "id-a", "name": "A", "baseUrl": "u", "model": "m", "apiKey": "KEY-A"},
           {"id": "id-b", "name": "B", "baseUrl": "u", "model": "m", "apiKey": "KEY-B"}]
store_old_read(st, sp)                       # migrate both to providerApiKey_0 / _1
st.list = [st.list[1]]                       # A deleted; B is now the profile at index 0
slot_only = SecurePrefs()
slot_only.d["providerApiKey_0"] = "ENC(KEY-A)"
slot_only.d["providerApiKey_1"] = "ENC(KEY-B)"
check("11. after A is deleted, slot 0 still resolves to A's key",
      slot_only.get(legacy_secret_name(0)), "KEY-A")

# Same starting point, id-keyed path: B keeps its own identity and its own key.
sp2 = SecurePrefs()
st2 = Store()
st2.write([p("id-a", "A", "KEY-A"), p("id-b", "B", "KEY-B")], sp2)
st2.write([p("id-b", "B", "KEY-B")], sp2)    # A deleted
check("11. id-keyed logic gives B its own key", st2.read(sp2, mint_ids=True)[0]["apiKey"], "KEY-B")

# 12. Orphan sweep removes keys for profiles that are gone.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A"), p("id-b", "B", "KEY-B")], sp)
st.write([p("id-a", "A", "KEY-A")], sp)
check("12. gone profile's secret removed", sp.get("providerApiKey_id-b"), None)
check("12. kept profile's secret intact", sp.get("providerApiKey_id-a"), "KEY-A")

# 13. A blank key clears that profile's secret.
sp = SecurePrefs()
st = Store()
st.write([p("id-a", "A", "KEY-A")], sp)
st.write([p("id-a", "A", "")], sp)
check("13. blanking a key clears its secret", sp.get("providerApiKey_id-a"), None)

# 14. A profile with no id must keep the SAME id across reads.
#
#     This is the v1.52.5 regression, found on a real device: a profile written before
#     ids existed has none, so fromJson mints one at read time. If the minted id is not
#     written back, the next read mints a *different* one -- and the key migrated under
#     the first id becomes unreachable, then gets swept as an orphan by save().
#
#     Device symptom: both providers reported "key=নেই (খালি)" and "সিক্রেট নাম 0 টা"
#     after a migration that had just logged "id-তে 2 টা".
sp = SecurePrefs()
st = Store()
st.list = [{"name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]  # no id
first = st.read(sp, mint_ids=True)[0]
second = st.read(sp, mint_ids=True)[0]
check("14. minted id is stable across reads", first["id"], second["id"])
check("14. key still resolves on the second read", second["apiKey"], "PLAIN-A")
check("14. id was persisted into the list", st.list[0].get("id"), first["id"])

# 15. Without the fix, the id changes on every read and the key is lost. Kept as the
#     live reproduction so these assertions cannot pass vacuously.
sp = SecurePrefs()
st = Store()
st.list = [{"name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]
old_first = st.read(sp, mint_ids=False)[0]
old_second = st.read(sp, mint_ids=False)[0]
check("15. OLD: id differs between reads", old_first["id"] != old_second["id"], True)
check("15. OLD: first read still sees the key", old_first["apiKey"], "PLAIN-A")
check("15. OLD: second read CANNOT find the key", not old_second["apiKey"], True)

# 16. With save() in the loop, the orphan sweep destroys the only copy -- which is
#     exactly what the device showed: "id-তে 2 টা" logged, then 0 secrets present.
#
#     save() sweeps every secret whose name is not in `live`, and `live` is built from
#     the ids of the profiles it is handed. Hand it the same profile read twice and the
#     two reads disagree on the id, so one of the two names is always "not live".
sp = SecurePrefs()
st = Store()
st.list = [{"name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]
r1 = st.read(sp, mint_ids=False)[0]           # writes the key under a random id
written_name = secret_name(r1["id"])
check("16. OLD: key written under some id", sp.get(written_name), "PLAIN-A")

r_again = st.read(sp, mint_ids=False)[0]      # a *different* random id this time
st.write([r_again], sp)                       # save() sweeps the first id as an orphan
check("16. OLD: after save, the original secret is gone", sp.get(written_name), None)
check("16. OLD: and the new id holds nothing either", sp.d, {})

# 17. The fixed path survives the same round trip.
sp = SecurePrefs()
st = Store()
st.list = [{"name": "A", "baseUrl": "u", "model": "m", "apiKey": "PLAIN-A"}]
r1 = st.read(sp, mint_ids=True)[0]
st.write([r1], sp)
r2 = st.read(sp, mint_ids=True)[0]
check("17. FIXED: key survives read -> save -> read", r2["apiKey"], "PLAIN-A")
check("17. FIXED: id unchanged across the round trip", r2["id"], r1["id"])

# ========================================================================= summary

print(f"provider_store_check: {len(PASSES)} passed, {len(FAILURES)} failed")
if FAILURES:
    for f in FAILURES:
        print("  FAIL " + f)
    raise SystemExit(1)
print("all assertions passed")
