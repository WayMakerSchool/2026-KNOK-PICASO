import unittest
import numpy as np
from train_pair import pair_weights


class PairBalance(unittest.TestCase):
    def test_pair_mass_and_microphone_mass(self):
        labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner'];rows=[]
        for l in labels:
            for d in ['old_mic','FSD']:
                for g in range(2):rows.append({'label':l,'domain':d,'group':l+d+str(g),'parent':l+d+str(g)})
        w=pair_weights(rows);w=w/w.sum()
        for l in labels:
            expected=.25 if l in ('footstep','hammering') else .125
            self.assertAlmostEqual(sum(w[i] for i,r in enumerate(rows) if r['label']==l),expected)
            self.assertAlmostEqual(sum(w[i] for i,r in enumerate(rows) if r['label']==l and r['domain']=='old_mic'),expected*.5)


if __name__=='__main__':unittest.main()
