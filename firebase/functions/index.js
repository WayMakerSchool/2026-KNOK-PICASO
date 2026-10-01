"use strict";

const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");
const {initializeApp} = require("firebase-admin/app");
const {getFirestore} = require("firebase-admin/firestore");
const {createHash} = require("node:crypto");
const {mkdtemp, writeFile, readFile, rm} = require("node:fs/promises");
const {tmpdir} = require("node:os");
const {join} = require("node:path");
const {promisify} = require("node:util");
const execFile = promisify(require("node:child_process").execFile);
const {readInput, preparePcm, parseAnalysis} = require("./audio");
const prompt = require("./prompt.json");
initializeApp();
const key = defineSecret("GEMINI_SOUND_API_KEY");
const MODEL = "gemini-3.5-flash-lite";

exports.analyzeRecordingSound = onCall({
  region: "asia-northeast3", secrets: [key], enforceAppCheck: true,
  timeoutSeconds: 120, memory: "512MiB", maxInstances: 2, concurrency: 1,
}, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "로그인 후 소리 분석을 사용할 수 있습니다.");
  let bytes;
  try { bytes = readInput(request.data); }
  catch (error) { throw new HttpsError("invalid-argument", error.message); }
  const db = getFirestore();
  const hash = createHash("sha256").update(MODEL + ":v1:").update(bytes).digest("hex");
  const user = db.collection("_soundAnalysis").doc(request.auth.uid);
  const resultRef = user.collection("results").doc(hash);
  const cached = await resultRef.get();
  if (cached.exists) return cached.data().analysis;
  // Server-side limits protect the paid key even if clients bypass UI limits.
  await db.runTransaction(async (tx) => {
    const previous = (await tx.get(user)).data() || {};
    const now = Date.now();
    const day = new Date(now).toISOString().slice(0, 10);
    const count = previous.day === day ? previous.count || 0 : 0;
    if (now - (previous.lastAt || 0) < 30000 || count >= 20) {
      throw new HttpsError("resource-exhausted", "30초 간격, 하루 20회까지 분석할 수 있습니다.");
    }
    tx.set(user, {day, count: count + 1, lastAt: now});
  });
  const folder = await mkdtemp(join(tmpdir(), "knok-audio-"));
  try {
    const input = join(folder, "input");
    const output = join(folder, "decoded.pcm");
    await writeFile(input, bytes);
    // A 301-second bound detects overlong files rather than silently truncating them.
    await execFile(require("ffmpeg-static"), ["-nostdin", "-hide_banner", "-loglevel", "error",
      "-protocol_whitelist", "file", "-i", input, "-t", "301", "-vn", "-ac", "1", "-ar", "16000",
      "-f", "s16le", output], {timeout: 20000, maxBuffer: 65536});
    const prepared = preparePcm(await readFile(output));
    let analysis;
    if (prepared.silent) {
      analysis = {sound: "판단 불가", certainty: "unknown", evidence: "녹음에 식별할 수 있는 소리가 없습니다.",
        alternative: "뚜렷한 대안 없음", model: "local-silence-check", analyzedAt: Date.now()};
    } else {
      const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`, {
        method: "POST", signal: AbortSignal.timeout(60000),
        headers: {"Content-Type": "application/json", "x-goog-api-key": key.value().trim()},
        body: JSON.stringify({contents: [{parts: [
          {inlineData: {mimeType: "audio/wav", data: prepared.wav.toString("base64")}}, {text: prompt}
        ]}], generationConfig: {temperature: 0.1, maxOutputTokens: 512,
          thinkingConfig: {thinkingLevel: "MINIMAL"}, responseMimeType: "application/json",
          responseSchema: {type: "OBJECT", properties: {
            sound: {type: "STRING"}, certainty: {type: "STRING", enum: ["clear", "uncertain", "unknown"]},
            evidence: {type: "STRING"}, alternative: {type: "STRING"}
          }, required: ["sound", "certainty", "evidence", "alternative"]}}})
      });
      if (!response.ok) {
        if ([402, 429].includes(response.status)) throw new HttpsError("resource-exhausted", "Gemini 크레딧 또는 요청 한도를 확인해 주세요.");
        throw new HttpsError("unavailable", "소리 분석 서버 연결을 확인해 주세요.");
      }
      const body = await response.json();
      const text = (body.candidates?.[0]?.content?.parts || []).filter(part => !part.thought).map(part => part.text || "").join("");
      analysis = {...parseAnalysis(text), model: MODEL, analyzedAt: Date.now()};
    }
    await resultRef.set({analysis});
    return analysis;
  } catch (error) {
    if (error instanceof HttpsError) throw error;
    // Do not log audio, credentials, upstream bodies or ffmpeg's input metadata.
    throw new HttpsError("unavailable", "녹음 파일을 분석하지 못했습니다. 형식을 확인하고 다시 시도해 주세요.");
  } finally { await rm(folder, {recursive: true, force: true}); }
});
