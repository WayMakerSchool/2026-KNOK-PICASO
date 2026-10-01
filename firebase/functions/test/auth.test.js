const {test} = require("node:test");
const assert = require("node:assert/strict");
const {analyzeRecordingSound} = require("../index");
test("rejects a missing Firebase login before processing an audio upload", async () => {
  await assert.rejects(analyzeRecordingSound.run({data: {audio: "not an audio"}}), {code: "unauthenticated"});
});
test("rejects malformed audio for an authenticated caller before Firestore or Gemini", async () => {
  await assert.rejects(analyzeRecordingSound.run({auth: {uid: "test-user"}, data: {mime: "audio/wav", audio: "!"}}),
    {code: "invalid-argument"});
});
