import subprocess,sys,unittest
from pathlib import Path
import numpy as np
from calibrate_physical_model import LABELS,source_weights

class CalibrationGuardsTest(unittest.TestCase):
 def test_hundred_descendants_do_not_outvote_their_original_source(self):
  rows=[{'label':l,'collection':'physical_replay','parent_source_id':l+'-a'} for l in LABELS]
  rows.append(dict(rows[0],parent_source_id=LABELS[0]+'-b'))
  base=source_weights(rows,600)
  duplicated=rows+[dict(rows[0]) for _ in range(199)]
  changed=source_weights(duplicated,600)
  self.assertAlmostEqual(base[0],sum(w for r,w in zip(duplicated,changed) if r['parent_source_id']==rows[0]['parent_source_id']))
  self.assertAlmostEqual(sum(base),sum(changed))
 def test_held_original_is_rejected_even_when_python_assertions_are_disabled(self):
  code='''import sys,numpy as np
sys.path.insert(0,TOOLS)
from calibrate_physical_model import fit,LABELS
rows=[dict(label=l,collection='user',parent_source_id='source'+str(i)) for i,l in enumerate(LABELS)]
fit(np.eye(6,dtype='float32'),rows,6,held=['source0'])
'''.replace('TOOLS',repr(str(Path(__file__).resolve().parent)))
  result=subprocess.run([sys.executable,'-O','-c',code],capture_output=True,text=True)
  self.assertNotEqual(0,result.returncode,'A held original entered fitting under python -O')
  self.assertIn('Source leakage',result.stderr)
if __name__=='__main__':unittest.main()
