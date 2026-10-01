const {test} = require("node:test");
const assert = require("node:assert/strict");
const {readInput, preparePcm, parseAnalysis} = require("../audio");

test("rejects oversized, malformed and unsupported uploads before any paid call", () => {
  for (const data of [{mime: "text/html", audio: "AAAA"}, {mime: "audio/wav", audio: "!"},
    {mime: "audio/wav", audio: "A".repeat(14 * 1024 * 1024)}, {mime: "audio/wav", audio: "AAAA"}]) {
    assert.throws(() => readInput(data));
  }
  const bytes = Buffer.alloc(2 * 1024 * 1024, 7);
  assert.deepEqual(readInput({mime: "audio/wav", audio: bytes.toString("base64")}), bytes);
});
test("silence bypasses model, normalization preserves the source and avoids clipping", () => {
  assert.equal(preparePcm(Buffer.alloc(32000)).silent, true);
  const pcm = Buffer.alloc(32000); pcm.writeInt16LE(100, 2); pcm.writeInt16LE(-100, 4);
  const result = preparePcm(pcm);
  assert.equal(pcm.readInt16LE(2), 100);
  assert.equal(result.wav.toString("ascii", 0, 4), "RIFF");
  assert.equal(result.wav.readInt16LE(46), 26214);
  assert.equal(result.wav.readInt16LE(48), -26214);
  assert.throws(() => preparePcm(Buffer.alloc(16000 * 301 * 2)));
});
test("rejects fabricated probability and incomplete results, forces unknown sound", () => {
  const value = {sound: "물소리", certainty: "unknown", evidence: "작은 소리", alternative: "다른 소리"};
  assert.equal(parseAnalysis(JSON.stringify(value)).sound, "판단 불가");
  assert.throws(() => parseAnalysis(JSON.stringify({...value, certainty: "99%"})));
  assert.throws(() => parseAnalysis("{}"));
});
