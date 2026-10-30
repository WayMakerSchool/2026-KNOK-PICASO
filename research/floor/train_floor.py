"""Spatially held-out SNU floor classification; no app or firmware integration."""
import argparse
import csv
from datetime import datetime, timezone, timedelta
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import time

import joblib
import numpy as np
import soundfile as sf
from sklearn.ensemble import ExtraTreesClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC

ROOT = Path(__file__).resolve().parent
LABELS = ['below', 'same', 'above']
LABELS_KO = {'below': '아래층', 'same': '같은 층', 'above': '위층'}
CANDIDATES = ['logistic-c0.1', 'logistic-c1', 'logistic-c10',
              'svm-c1', 'svm-c10', 'svm-c100', 'trees-leaf1', 'trees-leaf4']


def position_parts(position):
    match = re.fullmatch(r'([123])F(\d+)m', position)
    if not match:
        raise ValueError(f'Unknown source position: {position}')
    return tuple(map(int, match.groups()))


def floor_label(position):
    floor, _ = position_parts(position)
    return {1: 'below', 2: 'same', 3: 'above'}[floor]


def partition_records(rows):
    parts = {key: [] for key in ['development', 'test', 'extra_upper', 'excluded']}
    if len({r['track_id'] for r in rows}) != len(rows):
        raise ValueError('Duplicate recording identifiers')
    for row in rows:
        floor, distance = position_parts(row['position'])
        if row['type'] in {'MB', 'HD', 'HH', 'CD'} and distance in {0, 6, 12}:
            key = 'test' if distance == 12 else 'development'
        elif floor == 3 and row['type'] in {'MB', 'HH'} and distance not in {0, 6, 12}:
            key = 'extra_upper'
        else:
            key = 'excluded'
        parts[key].append(row)
    return parts


def spatial_folds(rows):
    distance = np.array([position_parts(r['position'])[1] for r in rows])
    if set(distance) != {0, 6}:
        raise ValueError('Spatial development folds must contain distances 0 and 6 only')
    return [(np.flatnonzero(distance == a), np.flatnonzero(distance == b))
            for a, b in [(0, 6), (6, 0)]]


@lru_cache(maxsize=1)
def zip_extractor():
    path = ROOT / 'upstream/train_export.py'
    spec = importlib.util.spec_from_file_location('supplied_oneclass_training', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def record_features(path):
    audio, rate = sf.read(path, dtype='float32', always_2d=True)
    if rate != 16000 or audio.shape[1] != 1 or len(audio) < 16000:
        raise ValueError('Use mono 16000 Hz WAV with at least one second of audio')
    if not np.isfinite(audio).all():
        raise ValueError('Non-finite audio samples')
    if float(np.max(np.abs(audio))) < 1e-7:
        raise ValueError('Cannot classify silent audio')
    # The supplied 80-dimensional window extractor is preserved unchanged.
    # Pool windows at recording level so one recording is one training example.
    vector = zip_extractor().features(path).mean(axis=0).astype(np.float32)
    if vector.shape != (80,) or not np.isfinite(vector).all():
        raise ValueError('Invalid feature vector')
    return vector


def fit_candidate(candidate, x, y):
    if candidate.startswith('logistic-c'):
        classifier = LogisticRegression(C=float(candidate.split('-c')[1]), max_iter=3000)
    elif candidate.startswith('svm-c'):
        classifier = SVC(C=float(candidate.split('-c')[1]), kernel='rbf', gamma='scale')
    elif candidate.startswith('trees-leaf'):
        classifier = ExtraTreesClassifier(n_estimators=256,
            min_samples_leaf=int(candidate.split('leaf')[1]), random_state=42, n_jobs=2)
    else:
        raise ValueError(f'Unknown candidate: {candidate}')
    return Pipeline([('scaler', StandardScaler()), ('classifier', classifier)]).fit(x, y)


def wav_path(row):
    name = f"{int(row['track_id']):06d}"
    return ROOT / 'data/wav' / name[:3] / f'{name}.wav'


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False))


def feature_cache(rows):
    path = ROOT / 'artifacts/features.npz'
    fingerprint = hashlib.sha256((ROOT / 'upstream/train_export.py').read_bytes() +
        (ROOT / 'data/metadata.csv').read_bytes() +
        (ROOT / 'data/archive-provenance.json').read_bytes() +
        b'pooled80-v1').hexdigest()
    if path.exists():
        with np.load(path, allow_pickle=False) as cache:
            if str(cache['fingerprint']) == fingerprint:
                if cache['track_ids'].tolist() == [int(r['track_id']) for r in rows]:
                    print('Using verified feature cache', flush=True)
                    return cache['features'].copy()
    features = []
    start = time.monotonic()
    for i, row in enumerate(rows):
        features.append(record_features(wav_path(row)))
        if (i + 1) % 100 == 0 or i + 1 == len(rows):
            progress = {'status': 'extracting_features', 'done': i + 1, 'total': len(rows),
                        'elapsed_seconds': round(time.monotonic() - start, 1)}
            write_json(ROOT / 'artifacts/status.json', progress)
            print(json.dumps(progress), flush=True)
    x = np.asarray(features, dtype=np.float32)
    np.savez_compressed(path, features=x, track_ids=np.array([int(r['track_id']) for r in rows]),
                        fingerprint=np.array(fingerprint))
    return x


def metrics(y, prediction, rows):
    result = {'recordings': len(y), 'accuracy': float(accuracy_score(y, prediction)),
              'macro_f1': float(f1_score(y, prediction, labels=LABELS, average='macro', zero_division=0)),
              'label_order': LABELS, 'confusion_matrix': confusion_matrix(y, prediction, labels=LABELS).tolist(),
              'per_class': classification_report(y, prediction, labels=LABELS, output_dict=True, zero_division=0)}
    result['by_sound'] = {}
    for sound in sorted({r['type'] for r in rows}):
        indices = [i for i, r in enumerate(rows) if r['type'] == sound]
        result['by_sound'][sound] = {'recordings': len(indices),
            'accuracy': float(accuracy_score(y[indices], prediction[indices]))}
    return result


def train():
    started = datetime.now(timezone(timedelta(hours=9))).isoformat()
    with (ROOT / 'data/metadata.csv').open() as source:
        rows = sorted(list(csv.DictReader(source)), key=lambda r: int(r['track_id']))
    parts = partition_records(rows)
    assert [len(parts[k]) for k in parts] == [1200, 600, 1000, 150]
    # Freeze and publish the split before feature extraction or model selection.
    split = {'development': '0m and 6m; two directional spatial validation folds',
             'test': '12m; held out from all model selection',
             'extra_upper': '1000 upper-floor MB/HH recordings at other positions; stress test only',
             'excluded': '150 same-floor VC recordings; no other-floor VC counterparts',
             'records': {key: [dict(r, label=floor_label(r['position'])) for r in value]
                         for key, value in parts.items()}}
    write_json(ROOT / 'artifacts/split-manifest.json', split)
    x = feature_cache(rows)
    index = {r['track_id']: i for i, r in enumerate(rows)}
    def subset(key):
        selected = parts[key]
        return x[[index[r['track_id']] for r in selected]], np.array([floor_label(r['position']) for r in selected])
    dx, dy = subset('development')
    trials = []
    for candidate in CANDIDATES:
        scores = []
        for train_i, valid_i in spatial_folds(parts['development']):
            model = fit_candidate(candidate, dx[train_i], dy[train_i])
            pred = model.predict(dx[valid_i])
            scores.append(float(f1_score(dy[valid_i], pred, labels=LABELS, average='macro', zero_division=0)))
        trial = {'candidate': candidate, 'spatial_fold_macro_f1': scores, 'mean_macro_f1': float(np.mean(scores))}
        trials.append(trial)
        print(json.dumps(trial), flush=True)
    best = max(trials, key=lambda row: row['mean_macro_f1'])
    write_json(ROOT / 'artifacts/model-selection.json', {'selected': best, 'trials': trials,
        'selection_criterion': 'Mean macro F1 on spatial development folds only; test labels not consulted'})
    model = fit_candidate(best['candidate'], dx, dy)
    artifact = ROOT / 'artifacts/floor-model.joblib'
    joblib.dump(model, artifact, compress=3)
    # Reload the exported model before the single held-out evaluation.
    restored = joblib.load(artifact)
    np.testing.assert_array_equal(restored.predict(dx), model.predict(dx))
    tx, ty = subset('test')
    prediction = restored.predict(tx)
    result = metrics(ty, prediction, parts['test'])
    write_json(ROOT / 'artifacts/test-metrics.json', result)
    with (ROOT / 'artifacts/test-predictions.csv').open('w') as f:
        writer = csv.DictWriter(f, fieldnames=['track_id', 'type', 'position', 'actual', 'predicted'])
        writer.writeheader()
        for row, actual, pred in zip(parts['test'], ty, prediction):
            writer.writerow({key: row[key] for key in ['track_id', 'type', 'position']} | {'actual': actual, 'predicted': pred})
    ex, ey = subset('extra_upper')
    extra = restored.predict(ex)
    extra_result = {'recordings': len(ey), 'actual_class': 'above',
        'upper_floor_recall': float(np.mean(extra == 'above')),
        'prediction_counts': {label: int(np.sum(extra == label)) for label in LABELS},
        'warning': 'Only upper-floor examples; not a three-class accuracy estimate'}
    write_json(ROOT / 'artifacts/extra-upper-metrics.json', extra_result)
    card = {'model_id': 'knok-floor-snu-v1', 'status': 'trained_research_prototype', 'started_at_kst': started,
        'completed_at_kst': datetime.now(timezone(timedelta(hours=9))).isoformat(),
        'selected_candidate': best['candidate'], 'model_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
        'labels': LABELS_KO, 'receiver_floor': 2, 'training_recordings': len(dy), 'test_recordings': len(ty),
        'features': 'Supplied ZIP: 16kHz mono, 1s window/0.25s hop, log-mel mean40/std40; average windows into one 80D vector per recording',
        'validation': '0m->6m and 6m->0m spatial CV; final refit on 0m+6m; test at 12m',
        'test_metrics': result, 'extra_upper_metrics': extra_result,
        'limitations': ['One SNU building and one recording phone; KNOK hardware and other buildings untested.',
            'Known MB/HD/HH/CD inputs only; not a detector for arbitrary household sounds or silence.',
            'No vibration data, background class, household negative evaluation, or calibrated probabilities.',
            '80D window pooling loses temporal order; research baseline, not the original paper CNN.',
            'Independent recording session dates unavailable; spatial separation does not eliminate building/device confounds.',
            'Python/scikit-learn artifact; not installed into Android APK or ESP32 firmware.']}
    write_json(ROOT / 'artifacts/model-card.json', card)
    write_json(ROOT / 'artifacts/status.json', {'status': 'trained_and_evaluated', 'model_created': True,
        'test_accuracy': result['accuracy'], 'test_macro_f1': result['macro_f1']})
    print(json.dumps({'selected': best, 'test': result, 'extra_upper': extra_result}, ensure_ascii=False, indent=2), flush=True)


def predict(path):
    model = joblib.load(ROOT / 'artifacts/floor-model.joblib')
    vector = record_features(path)[None, :]
    label = str(model.predict(vector)[0])
    if hasattr(model, 'decision_function'):
        scores = model.decision_function(vector)[0]
        score_type = 'decision_function; not calibrated probabilities'
    else:
        scores = model.predict_proba(vector)[0]
        score_type = 'uncalibrated class scores'
    return {'label': label, 'label_ko': LABELS_KO[label], 'scores': dict(zip(model.classes_, map(float, scores))),
            'score_type': score_type, 'scope': 'Known sound types in the SNU research building; external input is unvalidated'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predict', type=Path)
    args = parser.parse_args()
    if args.predict:
        print(json.dumps(predict(args.predict), ensure_ascii=False, indent=2))
    else:
        train()
