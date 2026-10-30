import unittest
from final_scoring import score_trial


class TrialAccounting(unittest.TestCase):
    def test_failed_normal_never_counted_correct(self):
        self.assertFalse(score_trial('normal_or_unknown',None,False)['correct'])

    def test_low_confidence_known_sound_is_wrong(self):
        self.assertFalse(score_trial('hammering',[.1,.1,.4,.1,.2,.1],True)['correct'])

    def test_probabilities_need_valid_contract(self):
        self.assertFalse(score_trial('normal_or_unknown',[0.]*6,True)['processing_success'])

    def test_threshold_fixed_at_point65(self):
        self.assertTrue(score_trial('hammering',[.07,.07,.65,.07,.07,.07],True)['correct'])


if __name__=='__main__':unittest.main()
