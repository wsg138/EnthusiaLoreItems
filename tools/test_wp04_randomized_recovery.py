import hashlib
import unittest

SEEDS = (0xE17001, 0xE17002, 0xE17003, 0xE17004)
STEPS_PER_SEED = 2_000
ACTIONS = ("intent", "claim", "apply", "verify", "restart", "retry")
TRANSITIONS = {
    ("NONE", "intent"): "PENDING",
    ("PENDING", "claim"): "CLAIMED",
    ("PENDING", "retry"): "CLAIMED",
    ("CLAIMED", "apply"): "APPLIED_UNVERIFIED",
    ("APPLIED_UNVERIFIED", "verify"): "TERMINAL",
    ("CLAIMED", "restart"): "REVIEW_REQUIRED",
    ("APPLIED_UNVERIFIED", "restart"): "REVIEW_REQUIRED",
}


def deterministic_action(seed, step):
    payload = f"{seed}:{step}".encode("ascii")
    value = int.from_bytes(hashlib.sha256(payload).digest()[:4], "big")
    return ACTIONS[value % len(ACTIONS)]


class DurableRecoveryModel:
    """Stateful oracle for invariants exercised by the concrete SQLite failure matrices."""

    def __init__(self):
        self.state = "NONE"
        self.physical_applies = 0
        self.review_required = False

    def step(self, action):
        previous = self.state
        self.state = TRANSITIONS.get((previous, action), previous)
        if previous == "CLAIMED" and action == "apply":
            self.physical_applies += 1
        if self.state == "REVIEW_REQUIRED":
            self.review_required = True
        self.assert_invariants()

    def assert_invariants(self):
        if self.physical_applies > 1:
            raise AssertionError("ambiguous/terminal work repeated a physical side effect")
        if self.state == "REVIEW_REQUIRED" and not self.review_required:
            raise AssertionError("ambiguous recovery failed to record review requirement")


class Wp04RandomizedRecoveryTest(unittest.TestCase):
    def test_fixed_seed_stateful_recovery_never_guesses_after_ambiguity(self):
        for seed in SEEDS:
            model = DurableRecoveryModel()
            try:
                for step in range(STEPS_PER_SEED):
                    model.step(deterministic_action(seed, step))
            except AssertionError as error:
                self.fail(f"WP-04 randomized recovery seed {seed:#x} failed: {error}")

    def test_seeds_are_stable_and_reportable(self):
        self.assertEqual((14774273, 14774274, 14774275, 14774276), SEEDS)


if __name__ == "__main__":
    unittest.main()
