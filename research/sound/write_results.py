import csv
from datetime import datetime
import json
from pathlib import Path
import shlex
from zoneinfo import ZoneInfo
from final_helpers import sha,verify
from final_scoring import LABELS
HERE=Path(__file__).resolve().parent
KOREAN={'dragging_furniture':'가구 끌기','footstep':'발걸음','hammering':'반복 타격','instant_impact':'물건 낙하·단발 충격','normal_or_unknown':'일반·미지 소음','vacuum_cleaner':'청소기'}


def csvfile(name,rows):
    with (HERE/name).open('w',newline='',encoding='utf-8-sig') as f:w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)


def main():
    lock=json.loads((HERE/'final-lock.json').read_text());verify(lock);a=json.loads((HERE/'evaluation/ast-results.json').read_text());v=json.loads((HERE/'evaluation/v6-results.json').read_text());models={**a['models'],**v['models']};new=models['selected'];old=models['R21'];runtime=json.loads((HERE/'runtime_validation/verification.json').read_text())
    manifest=[r for r in json.loads((HERE/'data/new_public/manifest.json').read_text())['rows'] if r['role']=='sound_test'];names={'selected':'10월28일 책상 개선 후보','R21':'보존한 R21 연구후보','AST_legacy':'보존한 초기 AST 연구모델','V6':'보존한 V6 후보(ace8c5c9)'}
    for result in models.values():
        if len(result['rows'])!=120 or [r['file_hash'] for r in result['rows']]!=[r['file_hash'] for r in new['rows']]:raise RuntimeError('Compared WAV identities or denominators differ')
    perclass=[{'소리':KOREAN[l],'시험수':20,'R21_정답':old['per_class'][l]['correct'],'R21_정답률':old['per_class'][l]['recall'],'개선_정답':new['per_class'][l]['correct'],'개선_정답률':new['per_class'][l]['recall']} for l in LABELS];csvfile('종류별_비교.csv',perclass)
    records=[];errors=[]
    for i,r in enumerate(new['rows']):
        meta=manifest[i];row={'번호':i+1,'파일ID':r['track_id'],'원본ID':r['parent'],'자료':r['kind'],'원출처종류':r['family'],'정답':KOREAN[r['truth']],'기존_R21':KOREAN.get(old['rows'][i]['prediction'],'처리실패'),'개선_판정':KOREAN.get(r['prediction'],'처리실패'),'개선_정답여부':r['correct'],'처리성공':r['processing_success'],'모델점수_정답률아님':r['score_not_accuracy'],'file_sha256':r['file_hash'],'pcm_sha256':meta['pcm'],'출처':meta['attribution_url'],'이용허락':meta['license'],'오류':r['error'] or ''};records.append(row)
        if not r['correct']:errors.append(row)
    csvfile('새평가_120개_시도별.csv',records);csvfile('오분류_6개.csv',errors)
    csvfile('개선후보_혼동표.csv',[{'실제소리':KOREAN[l],**{KOREAN.get(k,'처리실패'):v for k,v in new['confusion'][l].items()}} for l in LABELS])
    comparison=[]
    for n in ('V6','AST_legacy','R21'):
        dev=lock['baseline_common1779'] if n=='R21' else None;comparison.append({'모델':names[n],'동일개발1779_원본환경균형점수':dev['domain_class_parent_balanced'] if dev else '', '동일개발1779_종류균형점수':dev['class_balanced'] if dev else '', '책상_같은원본회귀':0 if n=='R21' else '이번미실행','새공개평가_정답':models[n]['correct'],'새공개평가_전체':120,'새공개평가_정답률':models[n]['accuracy'],'채택':'비교기준','범위':'공개파일;실제KNOK새녹음아님'})
    for c in lock['all_adaptive_candidates']:
        selected=c['name']==lock['selected']['name'];comparison.append({'모델':c['name'],'동일개발1779_원본환경균형점수':c['metrics']['domain_class_parent_balanced'],'동일개발1779_종류균형점수':c['metrics']['class_balanced'],'책상_같은원본회귀':c['desk_regression_same_parent'],'새공개평가_정답':new['correct'] if selected else '', '새공개평가_전체':120 if selected else '', '새공개평가_정답률':new['accuracy'] if selected else '', '채택':'별도 연구후보 선정' if selected else '최종평가 미사용', '범위':'개발재사용/책상1원본3재생;선정후보만새평가'})
    csvfile('모델별_비교.csv',comparison)
    sensitivity=set(lock['sensitivity_FSD_unanimous_ids']);groups={g:{'count':sum(r['kind']==g for r in new['rows']),'correct':sum(r['correct'] for r in new['rows'] if r['kind']==g)} for g in ('FSD','APT')};sens=[r for r in new['rows'] if r['track_id'] in sensitivity];wins=sum(n['correct'] and not o['correct'] for n,o in zip(new['rows'],old['rows']));losses=sum(o['correct'] and not n['correct'] for n,o in zip(new['rows'],old['rows']))
    outcome={'scope':lock['scope'],'selected_model':lock['selected']['name'],'public_target_over85_met':new['accuracy']>.85,'public_correct':new['correct'],'public_count':120,'public_accuracy':new['accuracy'],'actual_KNOK_new_accuracy_over85_verified':False,'actual_three_way_floor_accuracy_verified':False,'desk_regression_count':3,'desk_regression_parent_count':1,'desk_regression_correct':3,'same_files_baselines':{n:{'correct':r['correct'],'count':120,'accuracy':r['accuracy']} for n,r in models.items()},'source_slices':groups,'predeclared_unanimous_FSD_sensitivity':{'count':len(sens),'correct':sum(r['correct'] for r in sens),'does_not_replace_primary120':True},'paired_R21_to_new':{'fixed':wins,'regressed':losses,'net_correct_change':wins-losses},'runtime_functional_tests':'3/3passed,storedWAVHTTPonly','runtime_workers_closed':runtime['workers_closed'],'new_physical_capture':False,'existing_app_replaced':False,'firmware_changed':False,'final_lock_sha256':sha(HERE/'final-lock.json'),'selected_model_sha256':sha(HERE/'delivery/models/sound.pt')};(HERE/'결과_명세.json').write_text(json.dumps(outcome,ensure_ascii=False,allow_nan=False,indent=2))
    lines=['KNOK 책상 소리 개선 — 무음 검증 결과',datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),'',
      '결론: 소리 파일 분류는 사전에 고정한 새 공개 음원120개 중114개(95.0%) 정답. 기존 R21은 같은120개에서106개(88.3%) 정답. 기존 책상 회귀 녹음은0/3→3/3. 실제 KNOK로 새로 녹음한 정확도95%를 뜻하지 않는다.',
      '기존 앱·로그인·WAV·펌웨어·V6·작동하던 책상 모델·R21을 보존했다. 새 모델은 별도 Mac 연구 분석용이며 현재 앱에 설치하거나 덮어쓰지 않았다. 층 방향 모델은 이번 작업에서 바꾸거나 새 실물 검증하지 않았다.','',
      '무엇을 고쳤나',
      '- 대표 원본만 뽑는 학습 목록에서 이전 보정에 쓰인 책상 마이크 녹음3개(1a/1b/1d)가 빠져 있었다. 대표 직접 녹음1개만 남아, 스피커·방·마이크를 다시 거친 녹음의 주파수 분포를 충분히 학습하지 못했다.',
      '- 빠진 실제 학습 녹음3개의 파일/PCM/부모 관계를 확인해 복원했다. 같은 원본으로 묶고 가중치를 나눠,3개가 독립 원본3개처럼 비중을 늘리지 않도록 했다.',
      '- 실패했던 평가 녹음3개(1f/20/21)는 학습에 넣지 않았다. 단, 같은 책상 원본의 기존 스피커 재생 녹음이며 후보 선택에 반복 사용한 회귀 자료다. 새 독립 책상 시험이 아니다.',
      '- 새 공개 학습 원본112개를 추가했다. 나무/문 두드림·망치32,동전 낙하16,물/말소리16,가구16,청소기16,아파트 낙하16. 증강을112개 독립 녹음으로 센 것이 아니다.',
      '- AST 본체를 고정하고 전체 녹음의4초 구간을 최대2초 간격으로 분석했다. 학습부에는 원본별 가중치와 발걸음/반복 타격 우선 비중을 적용했다. 최종 후보는 사전학습 AudioSet 의미 정보를 제한된 잔차로 더한다. 판정 기준0.65는 유지했다.',
      '- 파형 추가,고정 평균 앙상블,충격용 별도 SVM도 비교했지만 정해 둔 원본·환경 균형 기준이 낮아 채택하지 않았다. 이 충격 SVM은 층 방향 SVM과 다른 실험이다.','',
      '비교를 어떻게 했나',
      '- 과거 개발 자료1779개는 원본·해시·PCM·제작자·위치 묶음을 유지한3분할로 비교했다. 표준화/분류부는 해당 학습 분할만 사용했다. 과거 D/E/new36 및10/27최종72/48은 새 학습/후보 선택에서 제외했다.',
      '- 동일 개발 자료의 원본·환경·종류 균형 점수:R21 83.61%→선정 후보84.58%. 개발 자료의 단순 종류 평균과 다른 수치다. 새120개 결과95%와 섞어 해석하면 안 된다.',
      '-8개 후보를 개발 자료로 비교한 뒤 모델·코드·전처리·평가 파일 해시를 고정했다.120개는 종류마다20개,후보 선택 후 한 번만 분석했다. 분석 실패도 분모에서 빼지 않았고,처리 실패는 정답으로 세지 않았다(이번 처리 성공120/120). 평가 시작 후 학습을 코드로 차단했다.',
      '-70개 FSD는 기존/새 학습과 제작자가 겹치지 않는다.50개 아파트 파일은 새 이벤트지만 기존 학습과 건물뿐 아니라 소리 발생 위치도 같다. 천장 설치·새 건물·현재 KNOK 성능을 증명하지 않는다.',
      '-공개 음원의 정답은 원출처의 사람 주석/실험 메타데이터를 사용했다. FSD의 정확한 표 선별 규칙과 상반된 표,아파트의 위치 공유는 DATA_QUALITY_CORRECTIONS.txt에 기록했다. 정답을 모델 예측으로 만들거나 결과를 본 뒤 파일을 교체하지 않았다.','',
      '같은 새 공개 음원120개로 비교',
      '보존한 V6 후보(ace8c5c9):33/120=27.5%','보존한 초기 AST 연구모델:69/120=57.5%','보존한 R21 연구후보:106/120=88.3%','10월28일 책상 개선 후보:114/120=95.0%',
      '주의:27.5%는 knok_v6_candidate.tflite의 결과다. 별도로 보존한 knok_desk_calibrated.tflite나 현재 설치된 앱 전체의 정확도라고 부르면 안 된다.10/27의72개 결과와 이번120개도 모집단이 달라 직접 상승률로 비교하지 않는다.','',
      '소리별 새 평가 결과(각20개)',
      *[f'{r["소리"]}:R21 {r["R21_정답"]}/20 → 개선 {r["개선_정답"]}/20 ({r["개선_정답률"]*100:.0f}%)' for r in perclass],
      f'원출처별:제작자 분리 FSD {groups["FSD"]["correct"]}/{groups["FSD"]["count"]};기존 발생 위치의 새 아파트 이벤트 {groups["APT"]["correct"]}/{groups["APT"]["count"]}.',
      f'R21대비 새로 맞힌 파일{wins}개,이전에 맞혔지만 틀린 파일{losses}개. 정답 순증{wins-losses}개. 오답6개는 별도CSV에 모두 남겼으며 이후 재학습하지 않았다.','',
      '무음 실행 확인',
      '-핵심 계약/분할/가중치/실패 집계 테스트22개 통과.',
      '-저장된 WAV를 실제 분석 서비스에 전송한3개 기능 테스트 통과:책상3개 판정과 수치 일치,중복 수신 저장 중복 방지,분석 프로세스 중단 후 WAV보존과 재시도,20초 무음 구간 전체 분석과 방향 판단 보류.',
      '-Mac서비스 수신·분석·저장 경로를 확인한 것이다. Android 화면/실제KNOK 감지·녹음·Wi-Fi통신은 이번에 실행하지 않았다. 시험 서버와 분석 프로세스는 종료했다.','',
      '사용과 남은 검증',
      '-별도 모델:delivery/models/sound.pt. 실행:delivery/KNOK 책상 개선 연구모델 실행.command. 로컬 API는127.0.0.1:8768,가상폰이 나중에 연결할 주소는10.0.2.2:8768이다. 이번에는 앱 설정을 바꾸지 않았다.',
      '-기존 설치 앱이 저절로 새 모델로 바뀌지는 않는다. 모델은 Mac에서 실행하며 Android/ESP32에 직접 탑재한 TFLite 모델이 아니다.',
      '-다음에는 이 고정 후보로 실제KNOK에서 새 동작6종×10회=60회 감지→녹음→수신→분류→저장을 확인해야 한다. 실제85%초과 기준은52/60이상이다. 기기의 거리·방·천장 설치 조건을 기록하고 기존 책상 회귀와 구분해야 한다.',
      '-층 방향의 실제 미니어처3방향 검증,센서 융합,다기기 위치 계산은 이번 무음 개선의 완료 범위에 포함하지 않는다.','',
      '자료 출처: FSD50K https://zenodo.org/records/4060432 ; AudioSet Knock https://research.google.com/audioset/dataset/knock.html ; indoor-noise https://github.com/yodacatmeow/indoor-noise .각 파일의 라이선스/표시 저작자는 시도별CSV에 보존했다. 외부 게시나 원본 음원 재배포는 하지 않았다.']
    (HERE/'결과_요약.txt').write_text('\n'.join(lines)+'\n')
    python='/Users/yesung/Documents/ChatGPT/피카소/KNOK_origin_classifier/.venv/bin/python';command=HERE/'delivery/KNOK 책상 개선 연구모델 실행.command';command.write_text('#!/bin/zsh\nset -e\n'+shlex.quote(python)+' '+shlex.quote(str(HERE/'delivery/run_service.py'))+'\n')
    command.chmod(command.stat().st_mode|0o111)
    (HERE/'delivery/사용_안내.txt').write_text('별도 Mac 연구모델입니다.기존 앱/펌웨어 교체 없음.\n공개파일114/120(95%),기존 책상1원본의3재생회귀3/3.실제KNOK새녹음/천장/층3방향 정확도 미검증.\n실행.command를 사용자가 열면127.0.0.1:8768에서만 분석 서비스를 시작합니다.앱이나 가상폰은 자동 실행하지 않습니다.종료는Control-C입니다.\n현재Mac의원래AST모델/전용Python경로를 재사용하므로 이폴더만 다른컴퓨터로 옮겨 실행할 수는 없습니다.원본은보존하세요.\n자세한근거는상위폴더 결과_요약.txt와120개시도별CSV에있습니다.\n')
    print('REPORTS_WRITTEN','public114/120','desk3/3sameparent','actualKNOKunverified',flush=True)


if __name__=='__main__':main()
