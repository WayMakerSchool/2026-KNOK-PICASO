"""Package the selected research model separately, never installing or replacing an app."""
import json
from pathlib import Path
import shutil
from final_helpers import sha,verify
HERE=Path(__file__).resolve().parent;STUDY=HERE.parent/'wednesday_two_models_20261028'


def main():
    lock=json.loads((HERE/'final-lock.json').read_text());verify(lock);result=json.loads((HERE/'evaluation/ast-results.json').read_text())['models']['selected'];dest=HERE/'delivery';(dest/'models').mkdir(parents=True,exist_ok=True);target=dest/'models/sound.pt'
    if target.exists() and sha(target)!=sha(lock['model_path']):raise RuntimeError('Preserve existing packaged model')
    if not target.exists():shutil.copy2(lock['model_path'],target)
    olddelivery=STUDY/'tuesday_public_20261027/continuation_2100/delivery';manifest={'research_only':True,'model_version':'knok-sound-desk-repair-20261028','model_file':'models/sound.pt','model_sha256':sha(target),'source_root':str(HERE),'study_root':str(STUDY),'backbone_root':str(HERE.parent/'silent_root_cause_20261025'),'preserved_floor_worker':str(olddelivery/'selected_worker.py'),'preserved_floor_worker_sha256':sha(olddelivery/'selected_worker.py'),'preserved_floor_model_sha256':sha(olddelivery/'models/floor-svm.joblib'),'final_lock_sha256':sha(HERE/'final-lock.json'),'frozen_artifacts':lock['artifacts'],'public_correct':result['correct'],'public_count':120,'public_accuracy':result['accuracy'],'evaluation_scope':lock['scope'],'desk_regression_correct':3,'desk_regression_parent_count':1,'actual_KNOK_new_accuracy_over85_verified':False,'existing_app_replaced':False,'firmware_changed':False,'threshold':.65,'score_is_accuracy':False,'auto_start':False}
    path=dest/'model-manifest.json'
    if path.exists():raise RuntimeError('Preserve packaged manifest')
    path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2));print('SEPARATE_RESEARCH_MODEL',target,sha(target),flush=True)


if __name__=='__main__':main()
