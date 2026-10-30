"""A source-calibrated binary impact stage; unrelated to the floor-direction SVM."""
import numpy as np


def redistribute(probabilities,hammer_probability):
    q=np.asarray(probabilities,dtype='float64');p=np.asarray(hammer_probability,dtype='float64')
    if q.ndim!=2 or q.shape[1]!=6 or p.shape!=(len(q),) or not np.isfinite(q).all() or not np.isfinite(p).all() or np.any((p<0)|(p>1)) or np.max(abs(q.sum(1)-1))>1e-4:raise ValueError('Invalid impact probabilities')
    result=q.copy();mass=q[:,2]+q[:,3];use=mass>=.8;result[use,2]=mass[use]*p[use];result[use,3]=mass[use]*(1-p[use]);return result.astype('float32')


def sample_weights(rows):
    from pathlib import Path
    import sys
    old=Path(__file__).resolve().parent.parent/'wednesday_two_models_20261028/tuesday_public_20261027';sys.path.insert(0,str(old/'round14'))
    from experiment_core import source_weights
    w=source_weights(rows);y=np.array([r['label'] for r in rows]);mic=np.array([r['domain']=='old_mic' for r in rows])
    for label in ('hammering','instant_impact'):
        for side in (True,False):
            ix=(y==label)&(mic==side)
            if not ix.any():raise ValueError('Missing binary class/domain training cell')
            w[ix]*=.25/w[ix].sum()
    return w*len(rows)/w.sum()


def fit_margin(rows,x,c):
    from sklearn.preprocessing import StandardScaler
    from sklearn.svm import SVC
    w=sample_weights(rows);scale=StandardScaler().fit(x,sample_weight=w);geometry=np.r_[np.ones(768)/np.sqrt(768),np.ones(64)/np.sqrt(64)]/np.sqrt(2);z=scale.transform(x)*geometry;y=np.array([r['label']=='hammering' for r in rows],dtype=int)
    svc=SVC(C=c,kernel='rbf',gamma='scale',probability=False,cache_size=256,random_state=20261028);svc.fit(z,y,sample_weight=w);return {'scaler':scale,'geometry':geometry,'svc':svc}


def margin(model,x):return model['svc'].decision_function(model['scaler'].transform(x)*model['geometry'])


def fit_calibrated(rows,x,folds,c):
    from sklearn.linear_model import LogisticRegression
    inner=np.zeros(len(rows));audit=[]
    for f in np.unique(folds):
        ti=np.flatnonzero(folds!=f);vi=np.flatnonzero(folds==f)
        if set(rows[i]['group'] for i in ti)&set(rows[i]['group'] for i in vi):raise ValueError('Binary calibrator source crosses folds')
        m=fit_margin([rows[i] for i in ti],x[ti],c);inner[vi]=margin(m,x[vi]);audit.append({'fold':int(f),'train':len(ti),'validation':len(vi),'group_overlap':0})
    y=np.array([r['label']=='hammering' for r in rows],dtype=int);cal=LogisticRegression(C=1.,solver='lbfgs',max_iter=1000,random_state=20261028);cal.fit(inner[:,None],y,sample_weight=sample_weights(rows));model=fit_margin(rows,x,c);model.update(calibrator=cal,inner_source_held=audit,C=c,task='hammering_vs_instant_impact_NOT_floor',threshold_group_mass=.8,feature_schema='meanAST768-plus-whole-recording-temporal64-balanced-RBF-distance')
    return model


def predict(model,x):return model['calibrator'].predict_proba(margin(model,x)[:,None])[:,list(model['calibrator'].classes_).index(1)]
