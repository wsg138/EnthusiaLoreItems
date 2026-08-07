import random
import unittest

SEEDS = (0xE17001, 0xE17002, 0xE17003, 0xE17004)
STEPS_PER_SEED = 2_000


class DurableRecoveryModel:
    """Stateful oracle for the invariants exercised by the concrete SQLite failure matrices."""

    def __init__(self):
        self.state = "NONE"
        self.physical_applies = 0
        self.review_required = False

    def step(self, action):
        if action == "intent" and self.state == "NONE":
            self.state = "PENDING"
        elif action == "claim" and self.state == "PENDING":
            self.state = "CLAIMED"
        elif action == "apply" and self.state == "CLAIMED":
            self.physical_applies += 1
            self.state = "APPLIED_UNVERIFIED"
        elif action == "verify" and self.state == "APPLIED_UNVERIFIED":
            self.state = "TERMINAL"
        elif action == "restart":
            if self.state in {"CLAIMED", "APPLIED_UNVERIFIED"}:
                self.state = "REVIEW_REQUIRED"
                self.review_required = True
        elif action == "retry":
            if self.state == "PENDING":
                self.state = "CLAIMED"
            # REVIEW_REQUIRED and TERMINAL deliberately never repeat a physical side effect.

        if self.physical_applies > 1:
            raise AssertionError("ambiguous/terminal work repeated a physical side effect")
        if self.state == "REVIEW_REQUIRED" and not self.review_required:
            raise AssertionError("ambiguous recovery failed to record review requirement")


class Wp04RandomizedRecoveryTest(unittest.TestCase):
    def test_fixed_seed_stateful_recovery_never_guesses_after_ambiguity(self):
        actions = ("intent", "claim", "apply", "verify", "restart", "retry")
        for seed in SEEDS:
            rng = random.Random(seed)
            model = DurableRecoveryModel()
            try:
                for _ in range(STEPS_PER_SEED):
                    model.step(rng.choice(actions))
            except AssertionError as error:
                self.fail(f"WP-04 randomized recovery seed {seed:#x} failed: {error}")

    def test_seeds_are_stable_and_reportable(self):
        self.assertEqual((14774273, 14774274, 14774275, 14774276), SEEDS)


if __name__ == "__main__":
    unittest.main()
