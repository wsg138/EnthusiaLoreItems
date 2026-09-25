import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/wp05-protection-acceptance.yml"


class Wp05ProtectionAcceptanceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_environmental_exposure_player_waits_in_prepared_air_cell(self):
        outer = "echo 'fill 18 69 -2 22 74 2 minecraft:stone' >&3"
        inner = "echo 'fill 19 70 -1 21 73 1 minecraft:air' >&3"
        teleport = "echo 'tp Wp05Protect 20 71 0' >&3"
        self.assertIn(outer, self.workflow)
        self.assertIn(inner, self.workflow)
        self.assertIn(teleport, self.workflow)
        self.assertLess(self.workflow.index(outer), self.workflow.index(teleport))
        self.assertLess(self.workflow.index(inner), self.workflow.index(teleport))


if __name__ == "__main__":
    unittest.main()
