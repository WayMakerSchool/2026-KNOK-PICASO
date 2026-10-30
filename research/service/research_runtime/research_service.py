"""Optional separate loopback research API; never updates the demo app or firmware."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

HERE=Path(__file__).resolve().parent;PROJECT=HERE.parents[2];sys.path.insert(0,str(PROJECT))
from analysis_service import AnalysisPipeline,create_server
from jsonl_worker import JsonLineWorker,SoundWorkerClient,FloorWorkerClient
from run_local_service import DEFAULT_AST_RUNTIME,DEFAULT_FLOOR_RUNTIME
from research_router import HybridSound

V6_RUNTIME=Path('/Users/yesung/Documents/ChatGPT/피카소/KNOK_origin_classifier/.venv/bin/python')


class ResearchAnalysisPipeline(AnalysisPipeline):
    """Keep the shared sound validation while allowing a full 2 MiB WAV's windows."""

    def analyze(self,wav_path,receiver_position='',event_id=''):
        result=super().analyze(wav_path,receiver_position,event_id)
        identity=json.dumps([result['analysis_id'],receiver_position],separators=(',',':')).encode()
        result['analysis_id']=hashlib.sha256(identity).hexdigest()
        return result

    @staticmethod
    def _validate_sound(raw):
        if not isinstance(raw,dict):
            raise RuntimeError('Sound model returned an invalid result')
        starts=raw.get('window_starts',[])
        try:count=int(raw.get('window_count',1))
        except (TypeError,ValueError):raise RuntimeError('Invalid analysis-window count')
        if (count<1 or count>33 or not isinstance(starts,list) or len(starts)!=count
            or any(not isinstance(start,int) or start<0 for start in starts)
            or any(a>=b for a,b in zip(starts,starts[1:]))):
            raise RuntimeError('Invalid analysis-window metadata')
        compact=dict(raw,window_count=1,window_starts=[starts[0]])
        result=AnalysisPipeline._validate_sound(compact)
        result.update(window_count=count,window_starts=starts)
        return result

    def _floor_result(self,wav_path,receiver_position,sound,audio):
        result=super()._floor_result(wav_path,receiver_position,sound,audio)
        if (receiver_position,result['label']) in {('lower','below'),('upper','above')}:
            result.update(label='uncertain',reason='inconsistent_with_receiver')
        return result


class Models:
    def __init__(self,v6,r1,floor):
        self.v6=v6;self.r1=r1;self.floor=floor
        self.pipeline=ResearchAnalysisPipeline(HybridSound(SoundWorkerClient(v6),SoundWorkerClient(r1)),FloorWorkerClient(floor))

    def close(self):
        for worker in (self.floor,self.r1,self.v6):worker.close()


def start_models():
    program=HERE/'research_worker.py'
    workers=[JsonLineWorker([str(runtime),'-u',str(program),kind]) for runtime,kind in
      ((V6_RUNTIME,'v6'),(DEFAULT_AST_RUNTIME,'r1'),(DEFAULT_FLOOR_RUNTIME,'floor'))]
    try:
        for worker in workers:worker.start()
        return Models(*workers)
    except BaseException:
        for worker in reversed(workers):worker.close()
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8766)
    parser.add_argument('--capture-dir',type=Path,help='optional separate local recording store')
    args=parser.parse_args();models=start_models();server=None
    try:
        server=create_server(('127.0.0.1',args.port),models.pipeline,capture_dir=args.capture_dir)
        print(f'Research-only API: http://127.0.0.1:{server.server_port}; stop with Ctrl-C.',flush=True)
        print('Models have not been validated on the miniature or current KNOK.',flush=True)
        server.serve_forever()
    except KeyboardInterrupt:pass
    finally:
        if server is not None:server.server_close()
        models.close()


if __name__=='__main__':main()
