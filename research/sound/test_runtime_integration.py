"""Opt-in actual-model HTTP tests, stored WAVs only; excluded from generic unit discovery."""
import hashlib
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest
import wave
import numpy as np
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE/'delivery'))


@unittest.skipUnless(__import__('os').environ.get('KNOK_RUN_SAVED_WAV_HTTP')=='1','Explicit stored-WAV runtime check only')
class ActualModelService(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import run_service
        cls.api=run_service;cls.reference=json.loads((HERE/'expanded_semantic/report.json').read_text())['regression'];cls.models=run_service.start_models();cls.folder=HERE/'runtime_validation';cls.folder.mkdir(exist_ok=True);cls.capture=cls.folder/'captures';cls.server=run_service.create_server(('127.0.0.1',0),cls.models.pipeline,capture_dir=cls.capture);cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start();cls.events=[]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.thread.join(timeout=3);cls.models.close();(cls.folder/'verification.json').write_text(json.dumps({'scope':'Stored-WAV model/HTTP parity and persistence only; no physical recording or AndroidUI','requests':cls.events,'new_physical_capture':False,'sound_playback':False,'old_app_or_firmware_modified':False,'workers_closed':not cls.models.sound.ready and not cls.models.floor.ready},ensure_ascii=False,indent=2))

    def post(self,path,event):
        raw=Path(path).read_bytes();c=http.client.HTTPConnection(*self.server.server_address,timeout=120);c.request('POST','/v1/analyze',body=raw,headers={'Content-Type':'audio/wav','X-KNOK-Event-ID':event});response=c.getresponse();body=json.loads(response.read());status=response.status;c.close();self.events.append({'event_id':event,'status':status,'source_sha256':hashlib.sha256(raw).hexdigest(),'prediction':body.get('sound',{}).get('label'),'analysis_id':body.get('analysis_id')});return status,body

    def test_01_three_desk_replays_http_parity_and_duplicate(self):
        labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner'];self.assertTrue(self.models.pipeline.health()['ready'])
        for i,r in enumerate(self.reference):
            status,out=self.post(r['path'],'DESK-REPAIR-PARITY-'+str(i));self.assertEqual(status,200,out);self.assertEqual(out['sound']['label'],'hammering');self.assertFalse(out['sound']['score_is_accuracy']);self.assertFalse(out['ground_truth_used']);self.assertEqual(out['floor']['label'],'uncertain');q=np.array([out['sound']['probabilities'][l] for l in labels]);self.assertLess(float(abs(q-np.array(r['probabilities'])).max()),1e-5)
        before=len(list(self.capture.glob('*.wav')));a=self.post(self.reference[0]['path'],'DESK-REPAIR-PARITY-0');b=self.post(self.reference[0]['path'],'DESK-REPAIR-PARITY-0');self.assertEqual(a[1]['analysis_id'],b[1]['analysis_id']);self.assertEqual(before,len(list(self.capture.glob('*.wav'))))

    def test_02_worker_failure_preserves_wav_then_retry(self):
        source=self.reference[0];before=len(list(self.capture.glob('*.wav')));self.models.sound.close()
        try:
            status,out=self.post(source['path'],'DESK-REPAIR-RETRY');self.assertEqual(status,503);self.assertEqual(before+1,len(list(self.capture.glob('*.wav'))))
        finally:self.models.sound.start()
        status,out=self.post(source['path'],'DESK-REPAIR-RETRY');self.assertEqual(status,200,out);self.assertEqual(out['sound']['label'],'hammering');self.assertEqual(before+1,len(list(self.capture.glob('*.wav'))));self.assertEqual(out['audio']['file_sha256'],hashlib.sha256(Path(source['path']).read_bytes()).hexdigest())

    def test_03_silence_full_window_coverage_defers_floor(self):
        path=self.folder/'synthetic_silence20s.wav';count=320000
        with wave.open(str(path),'wb') as w:w.setframerate(16000);w.setnchannels(1);w.setsampwidth(2);w.writeframes(b'\0\0'*count)
        status,out=self.post(path,'DESK-REPAIR-SILENCE');self.assertEqual(status,200,out);self.assertEqual(out['sound']['signal_quality'],'low_signal');self.assertEqual(out['floor']['label'],'uncertain');starts=out['sound']['window_starts'];self.assertEqual(starts[0],0);self.assertGreaterEqual(starts[-1]+64000,count);self.assertTrue(all(b-a<=32000 for a,b in zip(starts,starts[1:])))


if __name__=='__main__':unittest.main()
