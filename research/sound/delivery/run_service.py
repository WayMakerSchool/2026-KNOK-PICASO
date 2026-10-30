"""Separate local research API, user-started; does not open Android or touch KNOK."""
import argparse
import json
from pathlib import Path
import sys
HERE=Path(__file__).resolve().parent;CFG=json.loads((HERE/'model-manifest.json').read_text());STUDY=Path(CFG['study_root']);OLD=STUDY/'tuesday_public_20261027';sys.path[:0]=[str(STUDY),str(OLD/'round8/runtime')]
from analysis_service import create_server
from jsonl_worker import JsonLineWorker,SoundWorkerClient,FloorWorkerClient
from run_local_service import DEFAULT_AST_RUNTIME,DEFAULT_FLOOR_RUNTIME
from research_service import ResearchAnalysisPipeline


class Models:
    def __init__(self,sound,floor):self.sound=sound;self.floor=floor;self.pipeline=ResearchAnalysisPipeline(SoundWorkerClient(sound),FloorWorkerClient(floor))
    def close(self):self.floor.close();self.sound.close()


def start_models():
    sound=JsonLineWorker([str(DEFAULT_AST_RUNTIME),'-u',str(HERE/'sound_worker.py')]);floor=JsonLineWorker([str(DEFAULT_FLOOR_RUNTIME),'-u',CFG['preserved_floor_worker'],'floor'])
    try:sound.start();floor.start();return Models(sound,floor)
    except BaseException:floor.close();sound.close();raise


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',type=int,default=8768);parser.add_argument('--capture-dir',type=Path,default=HERE/'runtime/captures/unlabeled');args=parser.parse_args();models=None;server=None
    try:
        models=start_models();server=create_server(('127.0.0.1',args.port),models.pipeline,capture_dir=args.capture_dir);print('Separate KNOK research API:http://127.0.0.1:'+str(args.port)+'; emulator useshttp://10.0.2.2:'+str(args.port),flush=True);print('Public file test114/120; previously seen desk replays3/3. New KNOK/ceiling/miniature accuracy remains unverified. Existing app and firmware unchanged.',flush=True);server.serve_forever()
    except KeyboardInterrupt:pass
    finally:
        if server:server.server_close()
        if models:models.close()


if __name__=='__main__':main()
