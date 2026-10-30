import json
from replay_evidence import stage_outcome, TrialJournal

BEFORE={'deviceId':'104e6f858428','bootId':'0123456789abcdef','startedEvents':2}
AFTER=dict(BEFORE,startedEvents=3)
EVENT='104e6f858428-0123456789abcdef-00000003'

def test_device_capture_without_import_is_a_transfer_failure():
    out=stage_outcome(BEFORE,AFTER,[],'footstep')
    assert out['device_detected'] is True and out['import_succeeded'] is False
    assert out['classification_correct'] is None and out['failure_stage']=='import_timeout'

def test_pending_is_not_a_wrong_class_and_all_attempts_keep_end_to_end_denominator():
    record={'deviceEventId':EVENT,'deviceClassificationPending':1,'classificationLabel':'unclassified'}
    out=stage_outcome(BEFORE,AFTER,[record],'footstep')
    assert out['import_succeeded'] and not out['classification_completed']
    assert out['classification_correct'] is None and not out['end_to_end_success']
    record.update(deviceClassificationPending=0,classificationLabel='footstep')
    done=stage_outcome(BEFORE,AFTER,[record],'footstep')
    assert done['classification_correct'] and done['end_to_end_success']

def test_classifier_error_is_separate_from_a_completed_wrong_label():
    record={'deviceEventId':EVENT,'deviceClassificationPending':0,'classificationLabel':'analysis_failed'}
    out=stage_outcome(BEFORE,AFTER,[record],'footstep')
    assert out['failure_stage']=='analysis_failed' and out['classification_correct'] is None

def test_boot_reset_does_not_become_a_false_negative():
    out=stage_outcome(BEFORE,dict(AFTER,bootId='fedcba9876543210',startedEvents=0),[],'footstep')
    assert out['device_detected'] is None and out['failure_stage']=='boot_changed'

def test_started_trial_is_journalled_even_when_capture_poll_raises(tmp_path):
    row={'trial':1,'playback_start_epoch':100}
    try:
        with TrialJournal(tmp_path,row):raise OSError('network timeout')
    except OSError:pass
    saved=[json.loads(s) for s in (tmp_path/'trials.jsonl').read_text().splitlines()]
    assert len(saved)==1 and saved[0]['error']=='network timeout'
